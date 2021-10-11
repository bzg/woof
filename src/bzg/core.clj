(ns bzg.core
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
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
   :versions     {:db/cardinality :db.cardinality/many}
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

(defn get-from [from]
  (:address (first from)))

(defn get-id [^String id]
  (peek (re-matches #"^<?(.+[^>])>?$" id)))

(defn get-subject [^String s]
  (-> s
      (string/replace #"^(R[Ee] ?: ?)+" "")
      (string/replace #" *\([^)]+\)" "")
      (string/trim)))

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
       (take 100)))

(defn get-bugs [] (get-reports-msgs :bug (get-reports :bug)))
(defn get-patches [] (get-reports-msgs :patch (get-reports :patch)))
(defn get-requests [] (get-reports-msgs :request (get-reports :request)))
(defn get-changes [] (get-reports-msgs :change (get-reports :change)))
(defn get-releases [] (get-reports-msgs :release (get-reports :release)))

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

(defn get-updates []
  (flatten
   (list
    (get-bugs)
    (get-patches)
    (get-requests)
    (get-changes)
    (get-announcements)
    (get-releases))))

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
                        :from       (get-from from)
                        :date       (java.util.Date.)
                        :backrefs   1}])
    (d/touch (d/entity db [:message-id id]))))

(defn update-person [{:keys [email role aliases]}]
  (let [role (or role :contributor)]
    (d/transact! conn [{:email   email
                        role     (java.util.Date.)
                        :aliases (or aliases #{})}])
    (d/touch (d/entity db [:email email]))))

;; Check whether a report is an action against a known entity

(def update-strings
  {:bug          #{"confirmed" "canceled" "fixed"}
   :patch        #{"approved" "canceled" "applied"}
   :request      #{"handled" "canceled" "done"}
   :change       #{"canceled" "released"}
   :announcement #{"canceled"}
   :release      #{"canceled"}})

(defn is-in-a-known-thread? [references]
  (doseq [i (filter #(seq (d/q `[:find ?e :where [?e :message-id ~%]] db))
                    references)]
    (let [backrefs (:backrefs (d/entity db [:message-id i]))]
      (d/transact! conn [{:message-id i :backrefs (inc backrefs)}]))))

(defn is-report-update? [report-type body-woof-lines references]
  ;; Is there a known action (e.g. "!canceled") for this report type
  ;; in the body of the email?
  (when-let [action (some (report-type update-strings) body-woof-lines)]
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
                                :to   (:admin config/woof)})
                              {:min-level :warn})}})

;; Email notifications

(defn send-email [{:keys [msg body]}]
  (let  [{:keys [id from subject references]} msg]
    (try
      (when-let
          [res (postal/send-message
                {:host (:smtp-host config/woof)
                 :port 587
                 :tls  true
                 :user (:smtp-login config/woof)
                 :pass (:smtp-password config/woof)}
                {:from        (:smtp-login config/woof)
                 :message-id  #(postal.support/message-id (:base-url config/woof))
                 :reply-to    (:admin config/woof)
                 :references  (string/join " " (remove nil? (list references id)))
                 :in-reply-to id
                 :to          from
                 :subject     (str "Re: " (get-subject subject))
                 :body        body})]
        (when (= (:error res) :SUCCESS)
          (timbre/info (str "Sent email to " from))))
      (catch Exception e
        (timbre/error (str "Can't send email: "
                           (:cause (Throwable->map e) "\n")))))))

(def mail-chan (async/chan))

(defn mail [msg body]
  (async/put! mail-chan {:msg msg :body body}))

(defn start-mail-loop! []
  (async/go
    (loop [e (async/<! mail-chan)]
      (send-email e)
      (recur (async/<! mail-chan)))))

;;; Core functions to return db entries

(defn- new-patch? [msg]
  (or
   ;; New patches with a subject starting with "[PATCH"
   (re-matches #"(?i)^\[PATCH(?: [0-9]+/[0-9]+)?].*$" (:subject msg))
   ;; New patches with a text/x-diff or text/x-patch MIME part
   (and (:multipart? msg)
        (not-empty
         (filter #(re-matches #"^text/x-(diff|patch).*" %)
                 (map :content-type (:body msg)))))))

(defn- new-bug? [msg]
  (re-matches #"(?i)^\[BUG].*$" (:subject msg)))

(defn- new-request? [msg]
  (re-matches #"(?i)^\[HELP].*$" (:subject msg)))

(defn- new-announcement? [msg]
  (re-matches #"(?i)^\[ANN].*$" (:subject msg)))

(defn- new-change? [msg]
  (when-let [m (re-matches #"(?i)^\[CHANGE\s*([^]]+)].*$" (:subject msg))]
    (into #{} (string/split (second m) #"\s"))))

(defn- new-release? [msg]
  (when-let [m (re-matches #"(?i)^\[RELEASE\s*([^]]+)].*$" (:subject msg))]
    (into #{} (string/split (second m) #"\s"))))

(defn report! [action & status]
  (d/transact! conn [action])
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
                       :status-string status-string}]

    (if status-string
      ;; Report against a known entry
      (do
        ;; Timbre logging
        (timbre/info
         (format "%s (%s) marked %s reported by %s (%s) as %s"
                 from msgid action-string op-from op-msgid (name status-string)))

        ;; Send email to the action reporter
        (mail msg (config/format-email-notification
                   (merge msg action-status {:notification-type :action-reporter})))

        ;; Send email to the original poster
        (mail op-msg (config/format-email-notification
                      (merge msg action-status {:notification-type :action-op}))))

      ;; Report a new entry
      (do
        ;; Timbre logging
        (timbre/info
         (format "%s (%s) reported a new %s" from msgid action-string))
        ;; Send email to the original poster
        (mail op-msg (config/format-email-notification
                      (merge msg {:notification-type :new} action-status)))))))

(defn process-incoming-message [msg]
  (let [{:keys [X-Original-To X-BeenThere To References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        references (when (not-empty References)
                     (->> (string/split References #"\s")
                          (keep not-empty)
                          (map get-id)))
        ;; FIXME: Also get and store the name, if any?
        from       (get-from (:from msg))]

    ;; Only process emails if they are sent directly from the release
    ;; manager or from the mailing list.
    (when (or (= from (:admin config/woof))
              (some (->>
                     (list X-Original-To X-BeenThere
                           (when (string? To)
                             (re-seq #"[^<@\s;,]+@[^>@\s;,]+" To)))
                     flatten
                     (remove nil?)
                     (into #{}))
                    (into #{} (list (:mailing-list-address config/woof)))))

      ;; Possibly add a new person
      (update-person {:email from})

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
        ;; Or detect a new announcement, change and release
        (or
         (when-let [versions (new-change? msg)]
           (report! {:change   (:db/id (add-mail msg))
                     :versions versions}))

         (when-let [versions (new-release? msg)]
           (report! {:release  (:db/id (add-mail msg))
                     :versions versions}))

         ;; Or detect new actions
         (when (not-empty references)
           ;; FIXME: Check against multiple text/plain parts?
           (when-let [body-woof-lines
                      (->>
                       (map
                        #(when-let [m (re-find #"^!([^!\s]+)" %)] (peek m))
                        (-> msg :body :body string/trim string/split-lines))
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

              ;; New action against a known change announcement?
              (when-let [{:keys [report-eid status]}
                         (is-report-update? :change body-woof-lines references)]
                (report! {:change report-eid status (:db/id (add-mail msg))}
                         status))

              ;; New action against a known announcement?
              (when-let [{:keys [report-eid status]}
                         (is-report-update? :announcement body-woof-lines references)]
                (report! {:announcement report-eid status (:db/id (add-mail msg))}
                         status))
              
              ;; New action against a known release announcement?
              (when-let [{:keys [report-eid status]}
                         (is-report-update? :release body-woof-lines references)]
                (report! {:release report-eid status (:db/id (add-mail msg))}
                         status))))))))))

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
