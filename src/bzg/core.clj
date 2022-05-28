(ns bzg.core
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure-mail.parser :as parser]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [mount.core :as mount]
            [bzg.config :as config]
            [tea-time.core :as tt]
            [postal.core :as postal]
            [postal.support]
            [clojure.core.async :as async]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [taoensso.timbre.appenders (postal :as postal-appender)]
            [datalevin.core :as d]
            [clojure.edn :as edn]))

;; Set up the database

(def schema
  {:defaults {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}
   :log      {:db/valueType :db.type/instant
              :db/unique    :db.unique/identity}

   :confirmed {:db/valueType :db.type/ref}
   :canceled  {:db/valueType :db.type/ref}
   :applied   {:db/valueType :db.type/ref}
   :approved  {:db/valueType :db.type/ref}
   :done      {:db/valueType :db.type/ref}
   :fixed     {:db/valueType :db.type/ref}
   :handled   {:db/valueType :db.type/ref}

   :message-id   {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :email        {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :references   {:db/valueType   :db.type/string
                  :db/cardinality :db.cardinality/many}
   :bug          {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :patch        {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :request      {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :change       {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :announcement {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :release      {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}})

(def conn (d/get-conn (:db-dir config/env) schema))

(def db (d/db conn))

;; Set config defaults

(defn set-defaults []
  (d/transact! conn [(merge {:defaults "init"} config/defaults)]))

;; Small utility functions

(def email-re #"[^<@\s;,]+@[^>@\s;,]+")

(defn- make-to [username address]
  (str username " <" address ">"))

(defn- get-id [^String id]
  (peek (re-matches #"^<?(.+[^>])>?$" id)))

(defn- get-subject [^String s]
  (-> s
      (string/replace #"^(R[Ee] ?: ?)+" "")
      (string/replace #" *\([^)]+\)" "")
      (string/trim)))

;; Main reports functions

(defn- get-reports [report-type]
  (->> (d/q `[:find ?e :where [?e ~report-type _]] db)
       (map first)
       (map #(d/pull db '[*] %))
       ;; Always remove canceled reports, we never need them
       (remove :deleted)
       (remove :canceled)))

(defn- add-role [e]
  (let [roles (count (select-keys
                      (d/entity db [:email (:from e)])
                      [:admin :maintainer]))]
    (merge e {:role roles})))

(defn- get-reports-msgs [report-type reports]
  (->> (map #(assoc (into {} (d/entity db (:db/id (report-type %))))
                    :priority (:priority %)) reports)
       (remove :deleted)
       (map add-role)))

(defn get-mails []
  (->> (d/q '[:find ?e :where [?e :message-id _]] db)
       (map first)
       (map #(d/pull db '[*] %))
       (remove :private)
       (remove :deleted)
       (sort-by :date)
       (map add-role)
       (take (-> (d/entity db [:defaults "init"]) :max :mails))))

(defn get-bugs [] (get-reports-msgs :bug (get-reports :bug)))
(defn get-patches [] (get-reports-msgs :patch (get-reports :patch)))
(defn get-requests [] (get-reports-msgs :request (get-reports :request)))
(defn get-releases [] (get-reports-msgs :release (get-reports :release)))

(defn get-changes []
  (->> (get-reports :change)
       (remove :released)
       (get-reports-msgs :change)))

(defn get-logs []
  (->> (d/q '[:find ?e :where [?e :log _]] db)
       (map first)
       (map #(d/pull db '[*] %))))

(defn get-announcements []
  (->> (get-reports :announcement)
       (remove :canceled)
       (get-reports-msgs :announcement)
       (take (-> (d/entity db [:defaults "init"]) :max :announcements))))

;; FIXME: Handle priority:
;; (remove #(if-let [p (:priority %)] (< p 2) true))
(defn get-confirmed-bugs []
  (->> (get-reports :bug)
       (filter :confirmed)
       (remove :fixed)
       (get-reports-msgs :bug)))

(defn get-unconfirmed-bugs []
  (->> (get-reports :bug)
       (remove :confirmed)
       (remove :fixed)
       (get-reports-msgs :bug)))

(defn get-unfixed-bugs []
  (->> (get-reports :bug)
       (remove :fixed)
       (get-reports-msgs :bug)))

(defn get-approved-patches []
  (->> (get-reports :patch)
       (filter :approved)
       (remove :applied)
       (get-reports-msgs :patch)))

(defn get-unapproved-patches []
  (->> (get-reports :patch)
       (remove :approved)
       (remove :applied)
       (get-reports-msgs :patch)))

(defn get-unapplied-patches []
  (->> (get-reports :patch)
       (remove :applied)
       (get-reports-msgs :patch)))

(defn get-handled-requests []
  (->> (get-reports :request)
       (filter :handled)
       (remove :done)
       (get-reports-msgs :request)))

(defn get-unhandled-requests []
  (->> (get-reports :request)
       (remove :handled)
       (get-reports-msgs :request)))

(defn get-undone-requests []
  (->> (get-reports :request)
       (remove :done)
       (get-reports-msgs :request)))

(defn get-upcoming-changes []
  (->> (get-reports :change)
       (remove :released)
       (get-reports-msgs :change)))

(defn get-latest-release []
  (->> (d/q '[:find ?e :where [?e :release _]] db)
       (map first)
       (map #(d/pull db '[*] %))
       (remove :canceled)
       (map (juxt :release #(hash-map :version (:version %))))
       (map (juxt #(select-keys (d/entity db (:db/id (first %)))
                                [:date])
                  second))
       (map #(conj (first %) (second %)))
       (sort-by :date)
       last))

(defn get-all-releases []
  (->> (d/q '[:find ?e :where [?e :release _]] db)
       (map first)
       (map #(d/pull db '[*] %))
       (remove :canceled)
       (map :version)
       (into #{})))

(defn get-latest-released-changes []
  (let [latest-version (:version (get-latest-release))]
    (->> (filter #(and (= latest-version (:version %))
                       (:released %))
                 (get-reports :change))
         (get-reports-msgs :change))))

(defn get-updates []
  (let [features (:features (d/entity db [:defaults "init"]))]
    (->> (list
          (when (:bugs features) (get-confirmed-bugs))
          (when (:patches features) (get-approved-patches))
          (when (:requests features) (get-unhandled-requests))
          (when (:changes features) (get-upcoming-changes))
          (when (:releases features) (get-releases))
          (get-announcements))
         (remove nil?)
         flatten)))

;; Main admin functions

(defn- get-persons []
  (->> (d/q '[:find ?p :where [?p :email ?_]] db)
       (map first)
       (map #(d/pull db '[*] %))))

(defn get-admins []
  (->> (filter :admin (get-persons))
       (map :email)
       (into #{})))

(defn- get-maintainers []
  (->> (filter :admin (get-persons))
       (map :email)
       (into #{})))

;; Top functions

(defn- grouped-from-reports [reports]
  (->> reports
       flatten
       (map #(d/pull db '[*] %))
       (map :from)
       (group-by identity)
       (map (fn [[key val]]
              (let [e (d/entity db [:email key])]
                {:email    key
                 :username (:username e)
                 :role     (count (select-keys e [:admin :maintainer]))
                 :home     (:home e)
                 :support  (:support e)
                 :cnt      (count val)})))
       (sort-by :cnt)
       reverse))

(defn get-top-bug-contributors []
  (let [bugs-confirmed
        (d/q '[:find ?br ?r :where [?b :bug ?br] [?b :confirmed ?r]] db)
        bugs-fixed
        (d/q '[:find ?br ?r :where [?b :bug ?br] [?b :fixed ?r]] db)]
    ;; FIXME: Factor out
    (grouped-from-reports (concat bugs-confirmed bugs-fixed))))

(defn get-top-patch-contributors []
  (let [patches-approved
        (d/q '[:find ?br ?r :where [?b :patch ?br] [?b :approved ?r]] db)
        patches-applied
        (d/q '[:find ?br ?r :where [?b :patch ?br] [?b :applied ?r]] db)]
    (grouped-from-reports (concat patches-approved patches-applied))))

(defn get-top-request-contributors []
  (let [requests-handled
        (d/q '[:find ?r :where [?b :request _] [?b :handled ?r]] db)
        requests-done
        (d/q '[:find ?r :where [?b :request _] [?b :done ?r]] db)]
    (grouped-from-reports (concat requests-handled requests-done))))

(defn get-top-announcement-contributors []
  (grouped-from-reports
   (concat
    (d/q '[:find ?r :where
           [?b :announcement ?r]
           (not [?b :canceled _])] db))))

;; Core db functions to add and update entities

(defn- add-log! [date msg]
  (d/transact! conn [{:log date :msg msg}]))

(defn- add-mail! [{:keys [id from subject] :as msg} & private]
  (let [headers     (walk/keywordize-keys (apply conj (:headers msg)))
        id          (get-id id)
        refs-string (:References headers)
        refs        (if refs-string
                      (into #{} (string/split refs-string #"\s")) #{})]
    (d/transact! conn [{:message-id id
                        :subject    subject
                        :references refs
                        :private    (not (nil? private))
                        :from       (:address (first from))
                        :username   (:name (first from))
                        :date       (java.util.Date.)
                        :backrefs   1}])
    (d/entity db [:message-id id])))

(defn- add-mail-private! [msg]
  (add-mail! msg (java.util.Date.)))

(defn update-person! [{:keys [email username role]} & [action]]
  ;; An email is enough to update a person
  (let [existing-person    (d/entity db [:email email])
        ts                 (java.util.Date.)
        contributor-since? (or (:contributor existing-person) ts)
        maintainer-since?  (or (:maintainer existing-person) ts)
        admin-since?       (or (:admin existing-person) ts)
        username           (or username email)
        new-role           (condp = role
                             :maintainer {:contributor contributor-since?
                                          :maintainer  maintainer-since?}
                             :admin      {:contributor contributor-since?
                                          :maintainer  maintainer-since?
                                          :admin       admin-since?}
                             {:contributor contributor-since?})
        new-role-str       (string/join ", " (map name (keys new-role)))
        person             (merge {:email email :username username}
                                  new-role action)]
    ;; FIXME: Clumsy
    (when (d/transact! conn [person])
      (let [msg (cond
                  (and existing-person role action)
                  (format "Updated %s (%s) as %s: %s"
                          username email new-role-str action)
                  (and existing-person action)
                  (format "Updated %s (%s): %s"
                          username email action)
                  (and existing-person role)
                  (format "Updated %s (%s): %s"
                          username email new-role-str)
                  (and role action)
                  (format "Added %s (%s) as %s: %s"
                          username email new-role-str action)
                  action
                  (format "Added %s (%s): %s"
                          username email action)
                  role
                  (format "Added %s (%s) as %s"
                          username email new-role-str)
                  :else
                  (format "%s (%s) already known as %s"
                          username email new-role-str))]
        (timbre/info msg)))))

;; Check whether a report is an action against a known entity

(def config-strings-re
  (let [{:keys [admin maintainer contributor]} config/permissions]
    (->> (concat admin maintainer contributor)
         (map #(% config/admin-report-strings))
         (string/join "|")
         (format "(%s): (.+)\\s*$")
         re-pattern)))

(defn- report-strings-all [report-type]
  (let [report-type
        (condp = report-type
          :bug           :bugs    :patch
          :patches       :request :requests
          :change        :changes :announcement
          :announcements :release :releases
          nil)
        report-do   (map #(% config/report-strings)
                         (report-type config/reports))
        report-undo (map #(string/capitalize (str "Un" %)) report-do)]
    (into #{} (concat report-do report-undo))))

(def report-strings-re
  (let [all-do   (->> config/reports
                      (map val)
                      (map concat)
                      flatten
                      (map #(% config/report-strings)))
        all-undo (map #(string/capitalize (str "Un" %)) all-do)
        all      (into #{} (concat all-do all-undo))]
    (->> all
         (string/join "|")
         (format "(%s)(?: \\(([^)\\s]+)\\))?[;,:.].*")
         re-pattern)))

(defn- is-in-a-known-thread? [references]
  (doseq [i (filter #(seq (d/q `[:find ?e :where [?e :message-id ~%]] db))
                    references)]
    (let [backrefs (:backrefs (d/entity db [:message-id i]))]
      (d/transact! conn [{:message-id i :backrefs (inc backrefs)}]))))

(defn- is-report-update? [report-type body-report references]
  ;; Is there a known action (e.g. "Canceled") for this report type
  ;; in the body of the email?
  (when-let [action (some (report-strings-all report-type)
                          (list (first body-report)))]
    ;; Is this action against a known report, and if so, which one?
    (when-let [e (-> #(ffirst (d/q `[:find ?e
                                     :where
                                     [?e ~report-type ?ref]
                                     [?ref :message-id ~%]]
                                   db))
                     (map references)
                     (as-> refs (remove nil? refs))
                     first)]
      {:status     (keyword (string/lower-case action))
       :priority   (if-let [p (last body-report)]
                     (condp = p "high" 2 "medium" 1 0) 0)
       :report-eid (report-type (d/entity db e))})))

;; Setup logging

(defn- datalevin-appender []
  {:enabled?   true
   :async?     false
   :min-level  :info
   :rate-limit nil
   :output-fn  nil
   :fn
   (fn [data] (add-log! (java.util.Date.) (force (:msg_ data))))})

(timbre/set-config!
 {:level     :debug
  :output-fn (partial timbre/default-output-fn {:stacktrace-fonts {}})
  :appenders
  {:datalevin-appender (datalevin-appender)
   :println            (appenders/println-appender {:stream :auto})
   :spit               (appenders/spit-appender {:fname (:log-file config/env)})
   :postal             (merge (postal-appender/postal-appender ;; :min-level :warn
                               ^{:host (:smtp-host config/env)
                                 :user (:smtp-login config/env)
                                 :port 587
                                 :pass (:smtp-password config/env)
                                 :tls  true}
                               {:from (:smtp-login config/env)
                                :to   (make-to
                                       (:admin-username config/env)
                                       (:admin-address config/env))}))}})

;; Email notifications

(defn- send-email [{:keys [msg body purpose new-subject reply-to]}]
  (let  [{:keys [id from subject references]}
         msg
         to (make-to (:username (d/entity db [:email from])) from)]
    (try
      (when-let
          [res (postal/send-message
                {:host (:smtp-host config/env)
                 :port 587
                 ;; FIXME: Always assume a tls connection (or configure)?
                 :tls  true
                 :user (:smtp-login config/env)
                 :pass (:smtp-password config/env)}
                (merge
                 {:from       (:smtp-login config/env)
                  :message-id #(postal.support/message-id (:base-url config/env))
                  :reply-to   (or reply-to (make-to (:admin-username config/env)
                                                    (:admin-address config/env)))
                  :to         to
                  :subject    (or new-subject (str "Re: " (get-subject subject)))
                  :body       body}
                 (when references
                   {:references (string/join " " (remove nil? (list references id)))})
                 (when id {:in-reply-to id})))]
        (when (= (:error res) :SUCCESS)
          (timbre/info
           (format
            (condp = purpose
              :ack-reporter    "Sent mail to %s to ack report against known report"
              :ack-op-reporter "Sent mail to %s to ack report against initial report"
              :ack-op          "Sent mail to %s to ack initial report"
              :add-admin       "Sent mail to %s to ack as admin"
              :add-maintainer  "Sent mail to %s to ack as maintainer")
            to))))
      (catch Exception e
        (timbre/error (str "Cannot send email to %s: " to
                           (:cause (Throwable->map e) "\n")))))))

(def mail-chan (async/chan))

(defn- mail [msg body purpose & new-msg]
  (if (:global-notifications (d/entity db [:defaults "init"]))
    (async/put! mail-chan {:msg         msg
                           :body        body
                           :purpose     purpose
                           :new-subject (first new-msg)
                           :reply-to    (second new-msg)})
    (timbre/info "Notifications are disabled, do not send email")))

(defn start-mail-loop! []
  (async/go
    (loop [e (async/<! mail-chan)]
      (send-email e)
      (recur (async/<! mail-chan)))))

;;; Core functions to return db entries

(defn- new-patch? [msg]
  (or
   ;; New patches with a subject starting with "[PATCH"
   (re-matches (:patches config/action-re) (:subject msg))
   ;; New patches with a text/x-diff or text/x-patch MIME part
   (and (:multipart? msg)
        (not-empty
         (filter #(re-matches #"^text/x-(diff|patch).*" %)
                 (map :content-type (:body msg)))))))

(defn- new-bug? [msg]
  (re-matches (:bugs config/action-re) (:subject msg)))

(defn- new-request? [msg]
  (re-matches (:requests config/action-re) (:subject msg)))

(defn- new-announcement? [msg]
  (re-matches (:announcements config/action-re) (:subject msg)))

(defn- new-change? [msg]
  (when-let [m (re-matches (:changes config/action-re) (:subject msg))]
    (peek m)))

(defn- new-release? [msg]
  (when-let [m (re-matches (:releases config/action-re) (:subject msg))]
    (peek m)))

(defn- add-admin! [cmd-val from]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [person (not-empty (into {} (d/entity db [:email email])))]
        (let [output (d/transact! conn [(conj person [:admin (java.util.Date.)])])]
          (mail nil (format "Hi %s,\n\n%s added you as an admin.
\nSee this page on how to use Woof! as an admin:\n%s/howto\n\nThanks!"
                            (:username person)
                            from
                            (:base-url config/env))
                :add-admin
                (format "[%s] You are now a Woof! admin"
                        (:project-name config/env))
                from)
          (timbre/info (format "%s has been granted admin permissions" email))
          output)))))

(defn- remove-admin! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (let [admin-entity (d/entity db [:email email])]
        (if (true? (:root admin-entity))
          (timbre/error "Trying to remove the root admin: ignore")
          (when-let [output
                     (d/transact!
                      conn [[:db/retract (d/entity db [:email email]) :admin]])]
            (timbre/info (format "%s has been denied admin permissions" email))
            output))))))

(defn- add-maintainer! [cmd-val from]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [person (not-empty (into {} (d/entity db [:email email])))]
        (let [output (d/transact! conn [(conj person [:maintainer (java.util.Date.)])])]
          (mail nil (format "Hi %s,\n\n%s added you as an maintainer.
\nSee this page on how to use Woof! as an maintainer:\n%s/howto\n\nThanks!"
                            (:username person)
                            from
                            (:base-url config/env))
                :add-maintainer
                (format "[%s] You are now a Woof! maintainer"
                        (:project-name config/env))
                from)
          (timbre/info (format "%s has been granted maintainer permissions" email))
          output)))))

(defn- remove-maintainer! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [output
                 (d/transact!
                  conn [[:db/retract (d/entity db [:email email]) :maintainer]])]
        (timbre/info (format "%s has been removed maintainer permissions" email))
        output))))

(defn- delete! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (let [reports
            (->> (map
                  #(d/q `[:find ?mail-id :where
                          [?report-id ~% ?mail-id]
                          [?mail-id :from ~email]]
                        db)
                  ;; Delete all but changes and releases, even if the
                  ;; email being deleted is from a maintainer
                  [:bug :patch :request :announcement])
                 (map concat) flatten)]
        (when (seq reports)
          (doseq [r reports]
            (d/transact! conn [{:db/id r :deleted (java.util.Date.)}]))
          (timbre/info (format "Past mails from %s are now deleted" email))
          true)))))

(defn- undelete! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (let [reports
            (->> (map
                  #(d/q `[:find ?mail-id
                          :where
                          [?report-id ~% ?mail-id]
                          [?mail-id :deleted _]
                          [?mail-id :from ~email]]
                        db)
                  [:bug :patch :request :announcement])
                 (map concat) flatten)]
        (when (seq reports)
          (doseq [r reports]
            (d/transact! conn [[:db/retract r :deleted]]))
          (timbre/info (format "Past mails from %s are not deleted anymore" email))
          true)))))

(defn- unignore! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [output
                 (d/transact!
                  conn [[:db/retract (d/entity db [:email email]) :ignored]])]
        (timbre/info (format "Mails from %s won't be ignored anymore" email))
        output))))

(defn- ignore! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (let [person     (into {} (d/entity db [:email email]))
            as-ignored (conj person [:ignored (java.util.Date.)])]
        ;; Never ignore the root admin
        (when-not (true? (:root person))
          (when-let [output (d/transact! conn [as-ignored])]
            (timbre/info (format "Mails from %s will now be ignored" email))
            output))))))

(defn- add-feature! [cmd-val & disable?]
  (let [features (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [feature features]
      (let [defaults     (d/entity db [:defaults "init"])
            new-defaults (update-in
                          defaults
                          [:features (keyword feature)] (fn [_] (empty? disable?)))]
        (when (d/transact! conn [new-defaults])
          (timbre/info
           (format "Feature \"%s\" is %s"
                   feature
                   (if disable? "disabled" "enabled"))))))))

(defn- remove-feature! [cmd-val]
  (add-feature! cmd-val :disable))

(defn- add-export-format! [cmd-val & remove?]
  (let  [formats (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [export-format formats]
      (let [defaults     (d/entity db [:defaults "init"])
            new-defaults (update-in
                          defaults
                          [:export (keyword export-format)] (fn [_] (empty? remove?)))]
        (when (d/transact! conn [new-defaults])
          (timbre/info
           (format "Export format \"%s\" is %s"
                   export-format
                   (if remove? "removed" "added"))))))))

(defn- remove-export-format! [cmd-val]
  (add-export-format! cmd-val :remove))

(defn- set-theme! [theme]
  (let [defaults     (d/entity db [:defaults "init"])
        new-defaults (assoc defaults :theme theme)]
    (when (d/transact! conn [new-defaults])
      (timbre/info
       (format "Now using theme \"%s\"" theme)))))

(defn- config-maintenance! [status]
  (d/transact! conn [{:defaults "init" :maintenance status}])
  (timbre/info (format "Maintenance is now: %s" status)))

(defn- config-notifications! [status]
  (d/transact! conn [{:defaults "init" :notifications status}])
  (timbre/info (format "Notifications are now: %s" status)))

(defn- config! [{:keys [commands msg]}]
  (let [from  (:address (first (:from msg)))
        roles (select-keys (d/entity db [:email from])
                           [:admin :contributor :maintainer])
        role  (cond (:admin roles)      :admin
                    (:maintainer roles) :maintainer
                    :else               :contributor)]
    (doseq [[cmd cmd-val] commands]
      ;; FIXME: avoid redundancy?
      (condp = role
        :contributor
        (condp = cmd
          "Home"          (update-person! {:email from} [:home cmd-val])
          "Notifications" (update-person! {:email from} [:notifications cmd-val])
          "Support"       (update-person! {:email from} [:support cmd-val])
          nil)
        :maintainer
        (condp = cmd
          "Add maintainer" (add-maintainer! cmd-val from)
          "Delete"         (delete! cmd-val)
          "Ignore"         (ignore! cmd-val)
          nil)
        :admin
        (condp = cmd
          "Add admin"            (add-admin! cmd-val from)
          "Add export"           (add-export-format! cmd-val)
          "Add feature"          (add-feature! cmd-val)
          "Add maintainer"       (add-maintainer! cmd-val from)
          "Delete"               (delete! cmd-val)
          "Global notifications" (config-notifications! (edn/read-string cmd-val))
          "Home"                 (update-person! {:email from} [:home cmd-val])
          "Ignore"               (ignore! cmd-val)
          "Maintenance"          (config-maintenance! (edn/read-string cmd-val))
          "Notifications"        (update-person! {:email from} [:notifications cmd-val])
          "Remove admin"         (remove-admin! cmd-val)
          "Remove export"        (remove-export-format! cmd-val)
          "Remove feature"       (remove-feature! cmd-val)
          "Remove maintainer"    (remove-maintainer! cmd-val)
          "Set theme"            (set-theme! cmd-val)
          "Support"              (update-person! {:email from} [:support cmd-val])
          "Undelete"             (undelete! cmd-val)
          "Unignore"             (unignore! cmd-val)
          nil)))
    (add-mail-private! msg)))

(defn- contained 
  "Return the first element from l that m contains."
  [m l]
  (->> (map #(when (contains? m %) %) l) (remove nil?) first))

;; FIXME: Factor out report-update! from report! ?
(defn- report! [action]
  (let [action-type   (contained action config/report-types)
        action-status (contained action config/report-keywords-all)
        action-string (name action-type)
        status-string (when action-status (name action-status))

        ;; Get the original report
        op-report-msg (d/entity db (:db/id (action-type action)))
        op-from       (:from op-report-msg)
        op-msgid      (:message-id op-report-msg)
        op-msg        {:id         op-msgid
                       :subject    (:subject op-report-msg)
                       :from       op-from
                       :references (:references op-report-msg)}
        ;; Get the report against an existing one
        report-msg    (when action-status
                        (d/entity db (:db/id (action-status action))))
        from          (or (:from report-msg) op-from)
        msgid         (or (:message-id report-msg) op-msgid)
        msg           {:id         msgid
                       :subject    (:subject report-msg)
                       :from       from
                       :references (:references report-msg)}
        ;; Get other info
        username      (or (:username report-msg)
                          (:username op-report-msg) from)
        action-status {:action-string action-string
                       :status-string status-string}
        ;; ;; Maybe add default priority
        ;; action        (if-let [p (:priority action)]
        ;;                 action
        ;;                 (assoc action :priority config/priority))
        admin-or-maintainer?
        (or (:admin (d/entity db [:email from]))
            (:maintainer (d/entity db [:email from])))]

    ;; Possibly add a new person
    (update-person! {:email from :username username})

    ;; Add or retract status
    (if-let [m (when (not-empty status-string)
                 (re-matches #"^un(.+)" status-string))]
      (d/transact! conn [[:db/retract [action-type (:db/id op-report-msg)] (keyword (peek m))]])
      (d/transact! conn [(->> action
                              (map (fn [[k v]] [k (if (integer? v) v (:db/id v))]))
                              (into {}))]))

    (if status-string
      ;; Report against a known entry
      (do
        ;; Timbre logging
        (timbre/info
         (format "%s (%s) marked %s reported by %s (%s) as %s"
                 from msgid action-string op-from op-msgid (name status-string)))

        ;; Send email to the action reporter, if he's not an admin/maintainer
        (if-not admin-or-maintainer?
          (mail msg (config/format-email-notification
                     (merge msg action-status {:notification-type :action-reporter}))
                :ack-reporter)
          (timbre/info "Skipping email ack for admin or maintainer"))

        ;; Send email to the original poster, unless it is the action reporter
        (if-not (= from op-from)
          (mail op-msg (config/format-email-notification
                        (merge msg action-status {:notification-type :action-op}))
                :ack-op-reporter)
          (timbre/info "Do not ack original poster, same as reporter")))

      ;; Report a new entry
      (do
        ;; Timbre logging
        (timbre/info
         (format "%s (%s) reported a new %s" from msgid action-string))
        ;; Send email to the original poster
        (if-not admin-or-maintainer?
          (mail op-msg (config/format-email-notification
                        (merge msg {:notification-type :new} action-status))
                :ack-op)
          (timbre/info "Skipping email ack for admin or maintainer"))))))

(defn- release-changes! [version release-id]
  (let [changes-reports
        (->> (filter #(= version (:version %))
                     (get-reports :change))
             (map #(get % :db/id)))]
    (doseq [r changes-reports]
      (d/transact! conn [{:db/id r :released release-id}]))))

(defn- unrelease-changes! [release-id]
  (let [changes-to-unrelease
        (->> (filter #(= release-id (:released %))
                     (get-reports :change))
             (map #(get % :db/id)))]
    (doseq [r changes-to-unrelease]
      (d/transact! conn [[:db/retract r :released]]))))

(defn- process-mail [{:keys [from] :as msg}]
  (let [{:keys [To X-Original-To References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        references  (when (not-empty References)
                      (->> (string/split References #"\s")
                           (keep not-empty)
                           (map get-id)))
        to          (when (string? To) (re-matches email-re To))
        from        (:address (first from))
        admins      (get-admins)
        maintainers (get-maintainers)
        defaults    (d/entity db [:defaults "init"])]

    (when
        (and
         ;; First check whether this user should be ignored
         (not (:ignored (d/entity db [:email from])))
         ;; Always process messages from an admin
         (or (some admins (list from))
             (and
              ;; Don't process anything when under maintenance
              (not (:maintenance defaults))
              ;; When not under maintenance, always process direct
              ;; mails from maintainers
              (or (some maintainers (list from))
                  ;; A mailing list, only process mails sent there
                  (some #{to X-Original-To}
                        (list (:mailing-list-address config/env)))))))

      ;; Possibly increment backrefs count in known emails
      (is-in-a-known-thread? references)

      (cond
        ;; Detect a new bug/patch/request/announcement
        (and (-> defaults :features :patches) (new-patch? msg))
        (report! {:patch (add-mail! msg)})

        (and (-> defaults :features :bugs) (new-bug? msg))
        (report! {:bug (add-mail! msg)})

        (and (-> defaults :features :requests) (new-request? msg))
        (report! {:request (add-mail! msg)})

        (and (-> defaults :features :announcements) (new-announcement? msg))
        (report! {:announcement (add-mail! msg)})

        :else
        ;; Or detect new release/change
        (or
         ;; Only maintainers can push changes and releases
         (when (some maintainers (list from))
           (when (-> defaults :features :changes)
             (when-let [version (new-change? msg)]
               (if (some (get-all-releases) (list version))
                 (timbre/error
                  (format "%s tried to announce a change against released version %s"
                          from version))
                 (report! {:change (add-mail! msg) :version version}))))
           (when (-> defaults :features :releases)
             (when-let [version (new-release? msg)]
               (let [release-id (add-mail! msg)]
                 (report! {:release release-id :version version})
                 (release-changes! version release-id)))))

         ;; Or detect admin commands or new actions against known reports
         (let [body-parts
               (if (:multipart? msg) (:body msg) (list (:body msg)))
               body-seq
               (->> body-parts
                    (map #(condp (fn [a b]
                                   (re-matches
                                    (re-pattern (str "text/" a ".*")) b))
                              (:content-type %)
                            "plain" (:body %)
                            "html"  (parser/html->text (:body %))))
                    (string/join "\n")
                    string/split-lines
                    (map string/trim)
                    (filter not-empty))]

           (if (empty? references) ;; FIXME: required?

             ;; Check whether this is a configuration command
             (when-let [cmds
                        (->> body-seq
                             (map #(when-let [m (re-matches config-strings-re %)]
                                     (rest m)))
                             (remove nil?))]
               (config! {:commands cmds :msg msg}))

             ;; Or a report against a known patch, bug, etc
             (when-let
                 [body-report
                  (->> body-seq
                       (map #(when-let [m (re-matches report-strings-re %)]
                               (take-last 2 m)))
                       (remove nil?)
                       first)]

               (or
                ;; New action against a known patch
                (when (-> defaults :features :patches)
                  (when-let [{:keys [report-eid status priority]}
                             (is-report-update? :patch body-report references)]
                    (report! {:patch    report-eid status (add-mail! msg)
                              :priority priority})))

                ;; New action against a known bug
                (when (-> defaults :features :bugs)
                  (when-let [{:keys [report-eid status priority]}
                             (is-report-update? :bug body-report references)]
                    (report! {:bug      report-eid status (add-mail! msg)
                              :priority priority})))

                ;; New action against a known help request
                (when  (-> defaults :features :requests)
                  (when-let [{:keys [report-eid status priority]}
                             (is-report-update? :request body-report references)]
                    (report! {:request  report-eid status (add-mail! msg)
                              :priority priority})))

                ;; New action against a known announcement
                (when  (-> defaults :features :announcements)
                  (when-let [{:keys [report-eid status priority]}
                             (is-report-update? :announcement body-report references)]
                    (report! {:announcement report-eid status (add-mail! msg)
                              :priority     priority})))

                ;; Finally, maintainers can perform actions against
                ;; existing changes/releases
                (if-not (some maintainers (list from))
                  (timbre/warn
                   (format
                    "%s tried to update a change/release while not a maintainer"
                    from))
                  (do
                    ;; New action against a known change announcement?
                    (when (-> defaults :features :changes)
                      (when-let [{:keys [report-eid status priority]}
                                 (is-report-update? :change body-report references)]
                        (report! {:change   report-eid status (add-mail! msg)
                                  :priority priority})))

                    ;; New action against a known release announcement?
                    (when (-> defaults :features :releases)
                      (when-let [{:keys [report-eid status priority]}
                                 (is-report-update? :release body-report references)]
                        (report! {:release  report-eid status (add-mail! msg)
                                  :priority priority})
                        (unrelease-changes! report-eid))))))))))))))

;;; Inbox monitoring

(def woof-inbox-monitor (atom nil))

(defn read-and-process-mail [mails]
  (->> mails
       (map message/read-message)
       (map process-mail)
       doall))

(defn- start-inbox-monitor! []
  (reset!
   woof-inbox-monitor
   (let [session      (mail/get-session "imaps")
         mystore      (mail/store "imaps" session
                                  (:inbox-server config/env)
                                  (:inbox-user config/env)
                                  (:inbox-password config/env))
         folder       (mail/open-folder mystore (:inbox-folder config/env)
                                        :readonly)
         idle-manager (events/new-idle-manager session)]
     (events/add-message-count-listener
      ;; Process incoming mails
      (fn [e] (->> e :messages read-and-process-mail))
      ;; Don't process deleted mails
      nil
      folder
      idle-manager)
     idle-manager)))

(defn- start-tasks! []
  (tt/every! 1200 ;; 20 minutes
             (fn []
               (try
                 (events/stop @woof-inbox-monitor)
                 (catch Exception _ nil))
               (start-inbox-monitor!))))

(def woof-manager) ;; FIXME: Needed?
(mount/defstate woof-manager
  :start (do (start-tasks!)
             (timbre/info
              (format "Woof started on %s (port %s)"
                      (:base-url config/env)
                      (:port config/env))))
  :stop (when woof-manager
          (events/stop woof-inbox-monitor)
          (timbre/info "Woof stopped")))
