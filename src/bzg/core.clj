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
  {:defaults     {:db/unique :db.unique/identity}
   :log          {:db/valueType :db.type/instant
                  :db/unique    :db.unique/identity}
   :message-id   {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :email        {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :references   {:db/cardinality :db.cardinality/many}
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

(defn- get-reports-msgs [report-type reports]
  (->> (map #(d/touch (d/entity db (:db/id (report-type %)))) reports)
       (map #(dissoc (into {} %) :db/id))
       (remove :deleted)))

(defn get-mails []
  (->> (d/q `[:find ?e :where [?e :message-id _]] db)
       (map first)
       (map #(d/pull db '[*] %))
       (remove :private)
       (remove :deleted)
       (sort-by :date)
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
       (map (juxt #(select-keys (d/touch (d/entity db (:db/id (first %))))
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
  (flatten
   (list
    (get-confirmed-bugs)
    (get-approved-patches)
    (get-unhandled-requests)
    (get-upcoming-changes)
    (get-announcements)
    (get-releases))))

;; Main admin functions

(defn- get-persons []
  (->> (d/q '[:find ?p :where [?p :email ?_]] db)
       (map first)
       (map #(d/pull db '[*] %))))

(defn- get-contributors []
  (->> (filter :contributor (get-persons))
       (map :email)
       (into #{})))

(defn- get-admins []
  (->> (filter :admin (get-persons))
       (map :email)
       (into #{})))

(defn- get-maintainers []
  (->> (filter :admin (get-persons))
       (map :email)
       (into #{})))

;; Core db functions to add and update entities

(defn- add-log! [date msg]
  (d/transact! conn [{:log date :msg msg}])
  (d/touch (d/entity db [:log date])))

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
    ;; Also return the username as we use it in `report!`
    (d/touch (d/entity db [:message-id id]))))

(defn- add-mail-private! [msg]
  (add-mail! msg (java.util.Date.)))

(defn update-person! [{:keys [email username role]} & [action]]
  ;; An email is enough to update a person
  (let [timestamp (java.util.Date.)
        username  (or username email)
        roles     (condp = role
                    :maintainer {:contributor timestamp
                                 :maintainer  timestamp}
                    :admin      {:contributor timestamp
                                 :maintainer  timestamp
                                 :admin       timestamp}
                    {:contributor timestamp})
        person    (merge {:email email :username username}
                         action roles)]
    (d/transact! conn [person])
    (d/touch (d/entity db [:email email]))))

;; Check whether a report is an action against a known entity

(def admin-strings-re
  (let [{:keys [admin maintainer contributor]} config/permissions]
    (->> (concat admin maintainer contributor)
         (map #(% config/admin-report-strings))
         (string/join "|")
         (format "(%s): ([^\\s]+).*")
         re-pattern)))

(defn- report-strings-all [report-type]
  (let [report-do   (map #(% config/report-strings)
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
         (format "(%s)[;,:.].*")
         re-pattern)))

(defn- is-in-a-known-thread? [references]
  (doseq [i (filter #(seq (d/q `[:find ?e :where [?e :message-id ~%]] db))
                    references)]
    (let [backrefs (:backrefs (d/entity db [:message-id i]))]
      (d/transact! conn [{:message-id i :backrefs (inc backrefs)}]))))

(defn- is-report-update? [report-type body-report references]
  ;; Is there a known action (e.g. "Canceled") for this report type
  ;; in the body of the email?
  (when-let [action (some (report-strings-all report-type) body-report)]
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
       :report-eid (:db/id (report-type (d/touch (d/entity db e))))})))

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
                                 :pass (:smtp-password config/env)
                                 :tls  true}
                               {:from (:smtp-login config/env)
                                :to   (make-to
                                       (:admin-username config/env)
                                       (:admin-address config/env))})
                              {:min-level :warn})}})

;; Email notifications

(defn- send-email [{:keys [msg body purpose]}]
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
                {:from        (:smtp-login config/env)
                 :message-id  #(postal.support/message-id (:base-url config/env))
                 :reply-to    (make-to (:admin-username config/env)
                                       (:admin-address config/env))
                 :references  (string/join " " (remove nil? (list references id)))
                 :in-reply-to id
                 :to          to
                 :subject     (str "Re: " (get-subject subject))
                 :body        body})]
        (when (= (:error res) :SUCCESS)
          (timbre/info
           (format
            (condp = purpose
              :ack-reporter    "Sent mail to %s: ack report against known report"
              :ack-op-reporter "Sent mail to %s: ack report against initial report"
              :ack-op          "Sent mail to %s: ack initial report")
            to))))
      (catch Exception e
        (timbre/error (str "Cannot send email to %s: " to
                           (:cause (Throwable->map e) "\n")))))))

(def mail-chan (async/chan))

(defn- mail [msg body purpose]
  (if (:notifications (d/entity db [:defaults "init"]))
    (async/put! mail-chan {:msg msg :body body :purpose purpose})
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
   (re-matches #"^\[PATCH(?: [0-9]+/[0-9]+)?].*$" (:subject msg))
   ;; New patches with a text/x-diff or text/x-patch MIME part
   (and (:multipart? msg)
        (not-empty
         (filter #(re-matches #"^text/x-(diff|patch).*" %)
                 (map :content-type (:body msg)))))))

(defn- new-bug? [msg]
  (re-matches #"^\[BUG].*$" (:subject msg)))

(defn- new-request? [msg]
  (re-matches #"^\[HELP].*$" (:subject msg)))

(defn- new-announcement? [msg]
  (re-matches #"^\[ANN].*$" (:subject msg)))

(defn- new-change? [msg]
  (when-let [m (re-matches #"^\[CHANGE\s*([^]]+)].*$" (:subject msg))]
    (peek m)))

(defn- new-release? [msg]
  (when-let [m (re-matches #"^\[RELEASE\s*([^]]+)].*$" (:subject msg))]
    (peek m)))

(defn- add-admin! [email]
  (let [person (into {} (d/touch (d/entity db [:email email])))]
    (when-let [output (d/transact! conn [person])]
      (timbre/info (format "%s has been granted admin permissions" email))
      output)))

(defn- remove-admin! [email]
  (let [admin-entity (d/entity db [:email email])]
    (if (true? (:root admin-entity))
      (timbre/error "Trying to remove the root admin: ignore")
      (when-let [output
                 (d/transact!
                  conn [(d/retract (d/entity db [:email email]) :admin)])]
        (timbre/info (format "%s has been denied admin permissions" email))
        output))))

(defn- add-maintainer! [email]
  (let [person (into {} (d/touch (d/entity db [:email email])))]
    (when-let [output (d/transact! conn [person])]
      (timbre/info (format "%s has been granted maintainer permissions" email))
      output)))

(defn- remove-maintainer! [email]
  (when-let [output
             (d/transact!
              conn [(d/retract (d/entity db [:email email]) :maintainer)])]
    (timbre/info (format "%s has been removed maintainer permissions" email))
    output))

(defn- delete! [email]
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
      true)))

(defn- undelete! [email]
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
      true)))

(defn- unignore! [email]
  (when-let [output
             (d/transact!
              conn [(d/retract (d/entity db [:email email]) :ignored)])]
    (timbre/info (format "Mails from %s won't be ignored anymore" email))
    output))

(defn- ignore! [email]
  (let [person     (into {} (d/touch (d/entity db [:email email])))
        as-ignored (conj person [:ignored (java.util.Date.)])]
    ;; Never ignore the root admin
    (when-not (true? (:root person))
      (when-let [output (d/transact! conn [as-ignored])]
        (timbre/info (format "Mails from %s will now be ignored" email))
        output))))

(defn- add-feature! [feature & disable?]
  (let [defaults     (d/entity db [:defaults "init"])
        new-defaults (update-in
                      defaults
                      [:features (keyword feature)] (fn [_] (empty? disable?)))]
    (when (d/transact! conn [new-defaults])
      (timbre/info
       (format "Feature \"%s\" is %s"
               feature
               (if disable? "disabled" "enabled"))))))

(defn- remove-feature! [feature]
  (add-feature! feature :disable))

(defn- add-export-format! [export-format & remove?]
  (let [defaults     (d/entity db [:defaults "init"])
        new-defaults (update-in
                      defaults
                      [:export (keyword export-format)] (fn [_] (empty? remove?)))]
    (when (d/transact! conn [new-defaults])
      (timbre/info
       (format "Export format \"%s\" is %s"
               export-format
               (if remove? "removed" "added"))))))

(defn- remove-export-format! [export-format]
  (add-export-format! export-format :remove))

(defn- set-theme! [theme]
  (let [defaults     (d/entity db [:defaults "init"])
        new-defaults (assoc defaults :theme theme)]
    (when (d/transact! conn [new-defaults])
      (timbre/info
       (format "Now using theme \"%s\"" theme)))))

(defn- update-maintenance! [status]
  (d/transact! conn [{:defaults "init" :maintenance status}])
  (timbre/info (format "Maintenance mode is now: %s" status)))

(defn- update-notifications! [status & email]
  (if-let [address (first email)]
    (update-person! {:email address} [:notifications status])
    (d/transact! conn [{:defaults      "init"
                        :notifications status}])))

(defn- admin-report! [{:keys [commands msg]}]
  (let [from (:address (first (:from msg)))]
    (doseq [[cmd cmd-val] commands]
      (let [err (format "%s could not run \"%s: %s\""
                        from cmd cmd-val)]
        (if-not (or
                 ;; This is an admin toggle command
                 (re-matches
                  #"(Maintenance|Notifications): (true|false).*"
                  (str cmd ": " cmd-val))
                 (re-matches
                  #"(Add|Remove) export: (rss|json|org|md).*"
                  (str cmd ": " cmd-val))
                 (re-matches #"Set theme: .*" (str cmd ": " cmd-val))
                 (re-matches
                  #"(Add|Remove) feature: (bug|request|patch|announcement|change|release|mail).*"
                  (str cmd ": " cmd-val))
                 ;; The command's value is an email address
                 (re-matches email-re cmd-val))
          (timbre/error
           (format "%s sent an ill-formatted command: %s: %s"
                   from cmd cmd-val))
          (when (condp some (list from)
                  (get-admins)
                  (condp = cmd
                    "Add admin"         (add-admin! cmd-val)
                    "Add export"        (add-export-format! cmd-val)
                    "Add maintainer"    (add-maintainer! cmd-val)
                    "Delete"            (delete! cmd-val)
                    "Remove feature"    (remove-feature! cmd-val)
                    "Add feature"       (add-feature! cmd-val)
                    "Ignore"            (ignore! cmd-val)
                    "Maintenance"       (update-maintenance! (edn/read-string cmd-val))
                    "Notifications"     (update-notifications! (edn/read-string cmd-val))
                    "Remove admin"      (remove-admin! cmd-val)
                    "Remove export"     (remove-export-format! cmd-val)
                    "Remove maintainer" (remove-maintainer! cmd-val)
                    "Set theme"         (set-theme! cmd-val)
                    "Undelete"          (undelete! cmd-val)
                    "Unignore"          (unignore! cmd-val)
                    (timbre/error err))
                  (get-maintainers)
                  (condp = cmd
                    "Add maintainer" (add-maintainer! cmd-val)
                    "Delete"         (delete! cmd-val)
                    "Ignore"         (ignore! cmd-val)
                    (timbre/error err)))
            (add-mail-private! msg)))))))

(defn- report! [action & status]
  (let [action-type   (cond (:bug action)          :bug
                            (:patch action)        :patch
                            (:request action)      :request
                            (:change action)       :change
                            (:announcement action) :announcement
                            (:release action)      :release)
        action-string (name action-type)
        status-string (when-let [s (first status)] (name s))
        ;; Get the original report db entity
        op-report-msg (d/touch (d/entity db (action-type action)))
        op-from       (:from op-report-msg)
        op-msgid      (:message-id op-report-msg)
        ;; Get the report entity from the new status
        report-msg    (when status
                        (->> status first (get action) (d/entity db) d/touch))
        from          (or (:from report-msg) op-from)
        username      (or (:username report-msg)
                          (:username op-report-msg) from)
        msgid         (or (:message-id report-msg) op-msgid)
        msg           {:id   msgid :subject    (:subject report-msg)
                       :from from  :references (:references report-msg)}
        op-msg        {:id   op-msgid :subject    (:subject op-report-msg)
                       :from op-from  :references (:references op-report-msg)}
        action-status {:action-string action-string
                       :status-string status-string}
        admin-or-maintainer?
        (or (:admin (d/entity db [:email from]))
            (:maintainer (d/entity db [:email from])))]

    ;; Possibly add a new person
    (update-person! {:email from :username username})

    ;; Add or retract status
    (if-let [m (when (not-empty status-string)
                 (re-matches #"un(.+)" status-string))]
      (d/transact! conn [[:db/retract
                          (:db/id (d/entity db [action-type (action-type action)]))
                          (keyword (peek m))]])
      (d/transact! conn [action]))

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

(defn- process-incoming-message [{:keys [from] :as msg}]
  (let [{:keys [To X-Original-To References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        references   (when (not-empty References)
                       (->> (string/split References #"\s")
                            (keep not-empty)
                            (map get-id)))
        to           (when (string? To) (re-matches email-re To))
        from         (:address (first from))
        admins       (get-admins)
        maintainers  (get-maintainers)
        contributors (get-contributors)
        defaults     (d/entity db [:defaults "init"])]

    ;; Only process emails if they are sent directly from the release
    ;; manager or from the mailing list.
    (when
        (and
         ;; First check whether this user should be ignored
         (not (:ignored (d/entity db [:email from])))
         ;; Always process messages from the admin
         (or (= from (:admin-address config/env))
             (and
              ;; Don't process anything when under maintenance
              (not (:maintenance defaults))
              ;; Check relevant "To" headers
              (some #{to X-Original-To}
                    (list (:mailing-list-address config/env))))))

      ;; Possibly increment backrefs count in known emails
      (is-in-a-known-thread? references)

      (cond
        ;; Detect a new bug/patch/request/announcement
        (and (-> defaults :features :patch) (new-patch? msg))
        (report! {:patch (:db/id (add-mail! msg))})

        (and (-> defaults :features :bug) (new-bug? msg))
        (report! {:bug (:db/id (add-mail! msg))})

        (and (-> defaults :features :request) (new-request? msg))
        (report! {:request (:db/id (add-mail! msg))})

        (and (-> defaults :features :announcement)
             (new-announcement? msg))
        (report! {:announcement (:db/id (add-mail! msg))})

        :else
        ;; Or detect new release/change
        (or
         ;; Only maintainers can push changes and releases
         (if-not (some maintainers (list from))
           (timbre/warn
            (format "%s tried to update a change/release while not a maintainer"
                    from))
           (do
             (when (-> defaults :features :change)
               (when-let [version (new-change? msg)]
                 (if (some (get-all-releases) (list version))
                   (timbre/error
                    (format "%s tried to announce a change against released version %s"
                            from version))

                   (report! {:change  (:db/id (add-mail! msg))
                             :version version}))))
             (when (-> defaults :features :release)
               (when-let [version (new-release? msg)]
                 (let [release-id (:db/id (add-mail! msg))]
                   (report! {:release release-id
                             :version version})
                   (release-changes! version release-id))))))

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

           (if (empty? references) ;; FIXME: Needed?
             ;; Check whether this is a maintainer report sent to the admin
             (when-let [cmds
                        (->> body-seq
                             (map #(when-let [m (re-matches admin-strings-re %)]
                                     (rest m)))
                             (remove nil?))]
               (when (some (into #{} (concat admins maintainers)) (list from))
                 (admin-report! {:commands cmds :msg msg}))

               (when (some contributors (list from))
                 ;; Updating notifications is the only action for contributors
                 (update-notifications! from (second (first cmds))))
               
               (when-not (some contributors (list from))
                 (timbre/error
                  (format "%s (unknown) tried this command: %s"
                          from (second (first cmds))))))

             ;; Or a report against a known patch, bug, etc
             (when-let
                 [body-report
                  (->> body-seq
                       (map #(when-let [m (re-matches report-strings-re %)]
                               (peek m)))
                       (remove nil?))]

               (or
                ;; New action against a known patch
                (when (-> defaults :features :patch)
                  (when-let [{:keys [report-eid status]}
                             (is-report-update? :patch body-report references)]
                    (report! {:patch report-eid status (:db/id (add-mail! msg))}
                             status)))

                ;; New action against a known bug
                (when (-> defaults :features :bug)
                  (when-let [{:keys [report-eid status]}
                             (is-report-update? :bug body-report references)]
                    (report! {:bug report-eid status (:db/id (add-mail! msg))}
                             status)))

                ;; New action against a known help request
                (when  (-> defaults :features :request)
                  (when-let [{:keys [report-eid status]}
                             (is-report-update? :request body-report references)]
                    (report! {:request report-eid status (:db/id (add-mail! msg))}
                             status)))

                ;; New action against a known announcement
                (when  (-> defaults :features :announcement)
                  (when-let [{:keys [report-eid status]}
                             (is-report-update? :announcement body-report references)]
                    (report! {:announcement report-eid status (:db/id (add-mail! msg))}
                             status)))

                ;; Finally, maintainers can perform actions against
                ;; existing changes/releases
                (if-not (some maintainers (list from))
                  (timbre/warn
                   (format
                    "%s tried to update a change/release while not a maintainer"
                    from))
                  (do
                    ;; New action against a known change announcement?
                    (when (-> defaults :features :change)
                      (when-let [{:keys [report-eid status]}
                                 (is-report-update? :change body-report references)]
                        (report! {:change report-eid status (:db/id (add-mail! msg))}
                                 status)))

                    ;; New action against a known release announcement?
                    (when (-> defaults :features :release)
                      (when-let [{:keys [report-eid status]}
                                 (is-report-update? :release body-report references)]
                        (report! {:release report-eid status (:db/id (add-mail! msg))}
                                 status)
                        (unrelease-changes! report-eid))))))))))))))

;;; Inbox monitoring

(def woof-inbox-monitor (atom nil))

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
      (fn [e]
        (doall
         (remove nil?
                 (->> e :messages
                      (map message/read-message)
                      (map process-incoming-message)))))
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
             (timbre/info "Woof started"))
  :stop (when woof-manager
          (events/stop woof-inbox-monitor)
          (timbre/info "Woof stopped")))
