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
            [datalevin.core :as d]))

;; Set up the database

(def schema
  {:log          {:db/valueType :db.type/instant
                  :db/unique    :db.unique/identity}
   :message-id   {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :email        {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :references   {:db/cardinality :db.cardinality/many}
   :aliases      {:db/cardinality :db.cardinality/many}
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

(def conn (d/get-conn (:db-dir config/woof) schema))

(def db (d/db conn))

;; Small utility functions

(defn make-to [username address]
  (str username " <" address ">"))

(defn get-id [^String id]
  (peek (re-matches #"^<?(.+[^>])>?$" id)))

(defn get-subject [^String s]
  (-> s
      (string/replace #"^(R[Ee] ?: ?)+" "")
      (string/replace #" *\([^)]+\)" "")
      (string/trim)))

;; Main reports functions

(defn- get-reports [report-type]
  (->> (d/q `[:find ?e :where [?e ~report-type ?msg-eid]] db)
       (map first)
       (map #(d/pull db '[*] %))
       ;; Always remove canceled reports, we never need them
       (remove :canceled)))

(defn- get-reports-msgs [report-type reports]
  (->> (map #(d/touch (d/entity db (:db/id (report-type %)))) reports)
       (map #(dissoc (into {} %) :db/id))))

(defn get-mails []
  (->> (d/q `[:find ?e :where [?e :message-id _]] db)
       (map first)
       (map #(d/pull db '[*] %))
       (sort-by :date)
       ;; FIXME: Allow to configure?
       (take 100)))

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
       ;; FIXME: allow to configure?
       (take 10)))

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

(defn get-unreleased-changes []
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
    (get-bugs)
    (get-patches)
    (get-requests)
    (get-changes)
    (get-announcements)
    (get-releases))))

;; Main admin functions

(defn- get-persons []
  (->> (d/q '[:find ?p :where [?p :email ?_]] db)
       (map first)
       (map #(d/pull db '[*] %))))

(defn get-contributors []
  (->> (filter :contributor (get-persons))
       (remove :banned)
       (map :email)
       (into #{})))

(defn get-admins []
  (->> (filter :admin (get-persons))
       (remove :banned)
       (map :email)
       (into #{})))

(defn get-maintainers []
  (->> (filter :admin (get-persons))
       (remove :banned)
       (map :email)
       (into #{})))

(defn get-banned []
  (->> (filter :banned (get-persons))
       (map :email)
       (into #{})))

;; Core db functions to add and update entities

(defn add-log [date msg]
  (d/transact! conn [{:log date :msg msg}])
  (d/touch (d/entity db [:log date])))

(defn add-mail [{:keys [id from subject] :as msg}]
  (let [headers     (walk/keywordize-keys (apply conj (:headers msg)))
        id          (get-id id)
        refs-string (:References headers)
        refs        (if refs-string
                      (into #{} (string/split refs-string #"\s")) #{})]
    (d/transact! conn [{:message-id id
                        :subject    subject
                        :references refs
                        :from       (:address (first from))
                        :date       (java.util.Date.)
                        :backrefs   1}])
    (d/touch (d/entity db [:message-id id]))))

(defn update-person [{:keys [email username role aliases]}]
  (let [timestamp (java.util.Date.)
        username  (or username email)
        roles     (condp = role
                    :maintainer {:contributor timestamp
                                 :maintainer  timestamp}
                    :admin      {:contributor timestamp
                                 :maintainer  timestamp
                                 :admin       timestamp}
                    {:contributor timestamp})
        person    (merge {:email    email
                          :username username
                          :aliases  (or aliases #{})}
                         roles)]
    (d/transact! conn [person])
    (d/touch (d/entity db [:email email]))))

;; Check whether a report is an action against a known entity

(def report-strings
  {:bug          #{"Confirmed" "Canceled" "Fixed"}
   :patch        #{"Approved" "Canceled" "Applied"}
   :request      #{"Handled" "Canceled" "Done"}
   :change       #{"Canceled"}
   :announcement #{"Canceled"}
   :release      #{"Canceled"}})

(defn report-strings-all [report-type]
  (let [report-do   (report-type report-strings)
        report-undo (map #(string/capitalize (str "Un" %)) report-do)]
    (into #{} (concat report-do report-undo))))

(def report-strings-re
  (let [all-do   (into #{} (flatten (map concat (map val report-strings))))
        all-undo (map #(string/capitalize (str "Un" %)) all-do)
        all      (into #{} (concat all-do all-undo))]
    (->> all
         (string/join "|")
         (format "(%s)[;,:.].*")
         (re-pattern))))

(defn is-in-a-known-thread? [references]
  (doseq [i (filter #(seq (d/q `[:find ?e :where [?e :message-id ~%]] db))
                    references)]
    (let [backrefs (:backrefs (d/entity db [:message-id i]))]
      (d/transact! conn [{:message-id i :backrefs (inc backrefs)}]))))

(defn is-report-update? [report-type body-woof-lines references]
  ;; Is there a known action (e.g. "Canceled") for this report type
  ;; in the body of the email?
  (when-let [action (some (report-strings-all report-type) body-woof-lines)]
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

(defn datalevin-appender []
  {:enabled?   true
   :async?     false
   :min-level  :info
   :rate-limit nil
   :output-fn  nil
   :fn
   (fn [data] (add-log (java.util.Date.) (force (:msg_ data))))})

(timbre/set-config!
 {:level     :debug
  :output-fn (partial timbre/default-output-fn {:stacktrace-fonts {}})
  :appenders
  {:datalevin-appender (datalevin-appender)
   :println            (appenders/println-appender {:stream :auto})
   :spit               (appenders/spit-appender {:fname (:log-file config/woof)})
   :postal             (merge (postal-appender/postal-appender ;; :min-level :warn
                               ^{:host (:smtp-host config/woof)
                                 :user (:smtp-login config/woof)
                                 :pass (:smtp-password config/woof)
                                 :tls  true}
                               {:from (:smtp-login config/woof)
                                :to   (make-to
                                       (:admin-username config/woof)
                                       (:admin-address config/woof))})
                              {:min-level :warn})}})

;; Email notifications

(defn send-email [{:keys [msg body purpose]}]
  (let  [{:keys [id from subject references]}
         msg
         to (make-to (:username (d/entity db [:email from])) from)]
    (try
      (when-let
          [res (postal/send-message
                {:host (:smtp-host config/woof)
                 :port 587
                 ;; FIXME: Always assume a tls connection (or configure)?
                 :tls  true
                 :user (:smtp-login config/woof)
                 :pass (:smtp-password config/woof)}
                {:from        (:smtp-login config/woof)
                 :message-id  #(postal.support/message-id (:base-url config/woof))
                 :reply-to    (make-to (:admin-username config/woof)
                                       (:admin-address config/woof))
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

(defn mail [msg body purpose]
  (async/put! mail-chan {:msg msg :body body :purpose purpose}))

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

(defn report! [action & status]
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

(defn release-changes! [version release-id]
  (let [changes-reports
        (->> (filter #(= version (:version %))
                     (get-reports :change))
             (map #(get % :db/id)))]
    (doseq [r changes-reports]
      (d/transact! conn [{:db/id r :released release-id}]))))

(defn unrelease-changes! [release-id]
  (let [changes-to-unrelease
        (->> (filter #(= release-id (:released %))
                     (get-reports :change))
             (map #(get % :db/id)))]
    (doseq [r changes-to-unrelease]
      (d/transact! conn [[:db/retract r :released]]))))

(defn process-incoming-message [msg]
  (let [{:keys [X-Original-To X-BeenThere To References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        references   (when (not-empty References)
                       (->> (string/split References #"\s")
                            (keep not-empty)
                            (map get-id)))
        from         (first (:from msg))
        from-address (:address from)]

    ;; Only process emails if they are sent directly from the release
    ;; manager or from the mailing list.
    (when (and
           ;; Ignore messages from banned persons
           (not (some (get-banned) (list from-address)))
           ;; Always process messages from admin
           (or (= from (:admin-address config/woof))
               ;; Check relevant "To" headers
               (some (->>
                      (list X-Original-To X-BeenThere
                            (when (string? To)
                              (re-seq #"[^<@\s;,]+@[^>@\s;,]+" To)))
                      flatten
                      (remove nil?)
                      (into #{}))
                     (list (:mailing-list-address config/woof)))))

      ;; Possibly add a new person
      (update-person {:email from-address :username (:name from)})

      ;; Possibly increment backrefs count in known emails
      (is-in-a-known-thread? references)

      ;; Detect a new bug/patch/request
      (cond
        (new-patch? msg)
        (report! {:patch (:db/id (add-mail msg))})

        (new-bug? msg)
        (report! {:bug (:db/id (add-mail msg))})

        (new-request? msg)
        (report! {:request (:db/id (add-mail msg))})

        (new-announcement? msg)
        (report! {:announcement (:db/id (add-mail msg))})

        :else
        ;; Or detect a new change or a new release
        (or
         ;; Only allow maintainers to push changes
         (if-not (some (get-maintainers) (list from-address))
           (timbre/warn
            (format "%s tried to update a change/release while not a maintainer"
                    from-address))
           (do
             (when-let [version (new-change? msg)]
               (if (some (get-all-releases) (list version))
                 (timbre/error
                  (format "%s tried to announce a change against released version %s"
                          from-address version))
                 (report! {:change  (:db/id (add-mail msg))
                           :version version})))

             (when-let [version (new-release? msg)]
               (let [release-id (:db/id (add-mail msg))]
                 (report! {:release release-id
                           :version version})
                 (release-changes! version release-id)))))

         ;; Or detect new actions
         (when (not-empty references)
           (let [body-parts (if (:multipart? msg) (:body msg) (list (:body msg)))
                 body-seq
                 (map
                  #(condp
                       (fn [a b] (re-matches (re-pattern (str "text/" a ".*")) b))
                       (:content-type %)
                     "plain" (:body %)
                     "html"  (parser/html->text (:body %)))
                  body-parts)]
             (when-let
                 [body-woof-lines
                  (->>
                   (map
                    #(when-let [m (re-matches report-strings-re %)] (peek m))
                    (->> body-seq
                         (string/join "\n")
                         string/split-lines
                         (map string/trim)
                         (filter not-empty)))
                   (remove nil?))]

               (or
                ;; New action against a known patch?
                (when-let [{:keys [report-eid status]}
                           (is-report-update? :patch body-woof-lines references)]
                  (report! {:patch report-eid status (:db/id (add-mail msg))}
                           status))

                ;; New action against a known bug?
                (when-let [{:keys [report-eid status]}
                           (is-report-update? :bug body-woof-lines references)]
                  (report! {:bug report-eid status (:db/id (add-mail msg))}
                           status))

                ;; New action against a known help request?
                (when-let [{:keys [report-eid status]}
                           (is-report-update? :request body-woof-lines references)]
                  (report! {:request report-eid status (:db/id (add-mail msg))}
                           status))

                ;; New action against a known announcement?
                (when-let [{:keys [report-eid status]}
                           (is-report-update? :announcement body-woof-lines references)]
                  (report! {:announcement report-eid status (:db/id (add-mail msg))}
                           status))

                ;; Maintainers can also perform actions against changes/releases
                (if-not (some (get-maintainers) (list from-address))
                  (timbre/warn
                   (format "%s tried to update a change/release while not a maintainer"
                           from-address))
                  (do 
                    ;; New action against a known change announcement?
                    (when-let [{:keys [report-eid status]}
                               (is-report-update? :change body-woof-lines references)]
                      (report! {:change report-eid status (:db/id (add-mail msg))}
                               status))

                    ;; New action against a known release announcement?
                    (when-let [{:keys [report-eid status]}
                               (is-report-update? :release body-woof-lines references)]
                      (report! {:release report-eid status (:db/id (add-mail msg))}
                               status)
                      (unrelease-changes! report-eid)))))))))))))

;;; Inbox monitoring

(def woof-inbox-monitor (atom nil))

(defn- start-inbox-monitor! []
  (reset!
   woof-inbox-monitor
   (let [session      (mail/get-session "imaps")
         mystore      (mail/store "imaps" session
                                  (:inbox-server config/woof)
                                  (:inbox-user config/woof)
                                  (:inbox-password config/woof))
         folder       (mail/open-folder mystore (:inbox-folder config/woof)
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
