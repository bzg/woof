(ns bzg.core
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure.string :as string]
            [clojure.set :as set]
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
            [datalevin.core :as d])
  (:import [javax.mail.internet MimeUtility]))

;; Setup logging

(defn dl-appender []
  {:enabled?   true
   :async?     false
   :min-level  :info
   :rate-limit nil
   :output-fn  nil
   :fn
   (fn [data]
     (let [{:keys [msg_]} data]
       (d/transact! conn [{:type "log"
                           :msg  (force msg_)
                           :date (java.util.Date.)}])))})

(timbre/set-config!
 {:level     :debug
  :output-fn (partial timbre/default-output-fn {:stacktrace-fonts {}})
  :appenders
  {:dl-appender (dl-appender)
   :println     (appenders/println-appender {:stream :auto})
   :spit        (appenders/spit-appender {:fname (:log-file config/woof)})
   :postal      (merge (postal-appender/postal-appender ;; :min-level :warn
                        ^{:host (:smtp-host config/woof)
                          :user (:smtp-login config/woof)
                          :pass (:smtp-password config/woof)
                          :tls  true}
                        {:from (:smtp-login config/woof)
                         :to   (:admin config/woof)})
                       {:min-level :warn})}})

;; Set up the database

(def conn (d/get-conn (:db-dir config/woof)))

(def db (d/db conn))

(defn get-db []
  (->> (map first (d/q `[:find ?e :where [?e :type ?a]] db))
       (map #(d/pull db '[*] %))))

(defn- all-refs []
  (->> (d/q '[:find ?refs :where [_ :refs ?refs]] db)
       (map first)
       (apply set/union)))

(defn- get-logs []
  (->> (d/q '[:find ?logs :where [?logs :type "log"]] db)
       (map first)))

;;; Utility functions

(defn- mime-decode [^String s]
  (when (string? s) (MimeUtility/decodeText s)))

(defn get-from [from]
  (:address (first from)))

(defn get-id [^String id]
  (peek (re-matches #"^<?(.+[^>])>?$" id)))

(defn get-subject [^String s]
  (-> s
      (string/replace #"^(R[Ee] ?: ?)+" "")
      (string/replace #" *\([^)]+\)" "")
      (string/trim)))

;; FIXME: only used in feeds
;; Email functions

(defn send-email
  "Send an email."
  [{:keys [msg body]}]
  (let  [{:keys [id from subject]} msg
         op                        (get-from from)]
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
                 :In-Reply-To id
                 :to          op
                 :subject     (str "Re: " (get-subject subject))
                 :body        (str body "\n\n"
                                   (:email-link config/mails) "\n"
                                   (format (:mail-url-format config/woof)
                                           (get-id id)))})]
        (when (= (:error res) :SUCCESS)
          (timbre/info (str "Sent email to " op))))
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

;;; Handle refs

(defn- update-refs [id new-refs]
  (loop [refs new-refs
         ref  (some (all-refs) refs)]
    (when ref
      (doseq [e (get-db)]
        (when-let [rfs (:refs e)]
          (when (rfs ref)
            (d/transact!
             conn [{:db/id (:db/id e)
                    :refs  (conj rfs (get-id id))}])))))
    (when-let [rest-refs (last (next (partition-by #{ref} refs)))]
      (recur rest-refs
             (some (all-refs) rest-refs)))))

(defn- some-db-refs? [refs]
  (some (all-refs) refs))

;;; Core functions to return db entries

(defn get-entries [entry-type]
  (->> (map first (d/q `[:find ?bug :where [?bug :type ~entry-type]] db))
       (map #(d/pull db '[*] %))))

(defn get-bugs [] (get-entries "bug"))
(defn get-patches [] (get-entries "patch"))
(defn get-help-requests [] (get-entries "help"))
(defn get-changes [] (get-entries "change"))
(defn get-releases [] (get-entries "release"))

(defn get-unfixed-entries [entries]
  (filter #(not (get % :fixed)) entries))

(defn get-unfixed-bugs [] (get-unfixed-entries (get-bugs)))
(defn get-unapplied-patches [] (get-unfixed-entries (get-patches)))
(defn get-pending-help-requests [] (get-unfixed-entries (get-help-requests)))
(defn get-unreleased-changes []
  (filter #(and (not (get % :released))
                (not (get % :canceled)))
          (get-changes)))

(defn get-released-versions []
  (into #{} (map :version (get-releases))))

(defn- confirmed? [{:keys [msg header what]}]
  (let [header-confirmation
        (and (seq header)
             (not (re-matches (:closed config/actions-regexps)
                              (string/trim header)))
             (if (re-matches (:confirmed config/actions-regexps)
                             (string/trim header))
               true
               (mime-decode header)))
        body (:body (:body msg))]
    (if (and body (what #{:bug}))
      (or (seq (->> (map #(re-matches #"^Confirmed.*" %)
                         (string/split-lines body))
                    (remove nil?)))
          header-confirmation)
      header-confirmation)))

(defn- closed? [{:keys [msg header what]}]
  (let [header-confirmation
        (and header
             (re-matches (:closed config/actions-regexps)
                         (string/trim header)))
        body (:body (:body msg))]
    (if (and body (what #{:patch :bug}))
      (or (seq (->> (map #(re-matches
                           (condp = what
                             :patch #"^Applied.*"
                             :bug   #"^Fixed.*")
                           %)
                         (string/split-lines body))
                    (remove nil?)))
          header-confirmation)
      header-confirmation)))

(defn- new-patch? [msg]
  (or
   ;; Messages with a subject starting with "[PATCH" and that have no
   ;; references are patches:
   (and (re-matches #"(?i)^\[PATCH(?: ([0-9]+)/[0-9]+)?].*$"
                    (:subject msg))
        (empty? (:References (walk/keywordize-keys
                              (apply conj (:headers msg))))))
   ;; Also messages with a text/x-diff or text/x-patch MIME part:
   (and (:multipart? msg)
        (not-empty
         (filter #(re-matches #"^text/x-(diff|patch).*" %)
                 (map :content-type (:body msg)))))))

(defn- applying-patch? [msg refs X-Woof-Patch]
  (and refs
       (or (closed? {:header X-Woof-Patch :what :patch})
           (when-let [body (:body (:body msg))]
             (seq (remove nil? (map #(re-matches #"^Applied.*" %)
                                    (string/split-lines body))))))))

;;; Core functions to update the db

(defn- add-change [{:keys [id from subject date-sent] :as msg} refs X-Woof-Change]
  (let [c-specs   (string/split X-Woof-Change #"\s")
        commit    (when (< 1 (count c-specs)) (first c-specs))
        versions  (into #{} (if commit (next c-specs) (first c-specs)))
        released  (get-released-versions)
        true-from (get-from from)
        true-id   (get-id id)]
    (if (and released (some released versions))
      (timbre/error
       (format "%s tried to add a change against a past release, ignoring %s"
               true-from true-id))
      (do (d/transact! conn [{:msgid    true-id
                              :type     "change"
                              :from     true-from
                              :commit   commit
                              :refs     (into #{} (conj refs true-id))
                              :versions versions
                              :summary  (get-subject subject)
                              :date     date-sent}])
          (timbre/info (format "%s added a change for version %s via %s"
                               true-from (first versions) true-id))
          (mail msg (:change (:add config/mails)))))))

(defn- cancel-change [{:keys [id from date-sent] :as msg} refs]
  (let [true-from (get-from from)
        true-id   (get-id id)]
    ;; Prevent release when not from the release manager
    (doseq [e (get-unreleased-changes)]
      (when (some (:refs e) refs)
        (d/transact! conn [{:db/id       (:db/id e)
                            :canceled    true-id
                            :canceled-by true-from
                            :canceled-at date-sent}])))
    (timbre/info
     (format "%s canceled a change announcement via %s" true-from true-id))
    (mail msg (:change (:fix config/woof)))))

(defn- add-entry [{:keys [id from subject date-sent] :as msg} refs what]
  (let [{:keys [X-Woof-Help X-Woof-Bug] }
        (walk/keywordize-keys (apply conj (:headers msg)))
        X-Woof-Help (mime-decode X-Woof-Help)
        X-Woof-Bug  (mime-decode X-Woof-Bug)
        what-type   (name what)
        what-msg    (condp = what
                      :bug   "%s added a bug via %s"
                      :patch "%s sent a patch via %s"
                      :help  "%s added a call for help via %s"
                      "")
        note        (condp = what
                      :bug  (when (string? (confirmed? {:header X-Woof-Bug}))
                              X-Woof-Bug)
                      :help (when (string? (confirmed? {:header X-Woof-Help}))
                              X-Woof-Help)
                      nil)
        summary     (or note (get-subject subject))
        true-from   (get-from from)
        true-id     (get-id id)]
    (d/transact! conn [{:id      true-id
                        :type    what-type
                        :from    true-from
                        :refs    (into #{} (conj refs true-id))
                        :summary summary
                        :date    date-sent}])
    (timbre/info (format what-msg true-from true-id))
    (mail msg (get (:add config/mails) what))))

(defn- add-bug [msg refs]
  (add-entry msg refs :bug))

(defn- add-patch [msg refs]
  (add-entry msg refs :patch))

(defn- add-help [msg refs]
  (add-entry msg refs :help))

(defn- fix-entry [{:keys [id from subject date-sent]} refs what]
  (let [msg          (condp = what
                       :bug   "%s marked bug fixed via %s"
                       :patch "%s marked patch applied via %s"
                       :help  "%s marked help fixed via %s"
                       "")
        get-what     (condp = what
                       :bug   get-unfixed-bugs
                       :patch get-unapplied-patches
                       :help  get-pending-help-requests
                       nil)
        true-subject (get-subject subject)
        true-from    (get-from from)
        true-id      (get-id id)]
    (doseq [e (get-what (get-db))]
      (let [e-refs (:refs e)]
        (when (and (some e-refs refs)
                   (if (= what :patch)
                     (= (:summary e) true-subject)
                     true))
          (d/transact! conn [{:db/id    (:db/id e)
                              :fixed    true-id
                              :fixed-by true-from
                              :fixed-at date-sent}]))))
    (format msg true-from true-id)
    (mail msg (get (:fix config/mails) what))))

(defn- fix-bug [msg refs]
  (fix-entry msg refs :bug))

(defn- cancel-help [msg refs]
  (fix-entry msg refs :help))

(defn- add-release [{:keys [id from subject date-sent] :as msg} X-Woof-Release]
  (let [released  (get-released-versions)
        true-from (get-from from)
        true-id   (get-id id)]
    (cond
      ;; Prevent release when not from the release manager
      (not (= true-from (:admin config/woof)))
      (format "%s tried to release via %s while not being release manager"
              true-from true-id)
      ;; Prevent duplicate release
      (and released (some released #{X-Woof-Release}))
      (format "%s tried to release with a known version number via %s"
              true-from true-id)
      ;; Add the release to the db
      :else
      (do (d/transact! conn [{:id      true-id
                              :type    "release"
                              :from    true-from
                              :version X-Woof-Release
                              :summary (get-subject subject)
                              :date    date-sent}])
          ;; Mark related changes as released
          (doseq [e (get-unreleased-changes)]
            (when ((:versions e) X-Woof-Release)
              (d/transact! conn [{:db/id    (:db/id e)
                                  :released X-Woof-Release}])))
          (timbre/info
           (format "%s released %s via %s" true-from X-Woof-Release true-id))
          (mail msg (:release (:add config/mails)))))))

(defn process-incoming-message
  [{:keys [id from] :as msg}]
  (let [{:keys [X-Woof-Bug X-Woof-Release X-Woof-Change
                X-Woof-Help X-Woof-Patch
                X-Original-To X-BeenThere To References] }
        (walk/keywordize-keys (apply conj (:headers msg)))
        X-Woof-Help (mime-decode X-Woof-Help)
        X-Woof-Bug  (mime-decode X-Woof-Bug)
        refs
        (when (not-empty References)
          (->> (string/split References #"\s")
               (keep not-empty)
               (map get-id)
               (into #{})))]
    ;; Only process emails if they are sent directly from the release
    ;; manager or from the mailing list.
    (when (or (= (get-from from) (:admin config/woof))
              (some (into #{} (list X-Original-To X-BeenThere
                                    (when (string? To)
                                      (peek (re-matches #"^.*<(.*[^>])>.*$" To)))))
                    (into #{} (list (:mailing-list-address config/woof)))))
      ;; If any email with references contains in its references the id
      ;; of a known bug, add the message-id of this mail to the refs of
      ;; this bug.
      ;; FIXME_
      (when refs (update-refs (get-id id) refs))
      (cond
        ;; Detect and add a patch (anyone).
        (new-patch? msg)
        (add-patch msg refs)

        ;; Detect applied patch (anyone).
        (applying-patch? msg refs X-Woof-Patch)
        (fix-entry msg refs :patch)

        ;; Confirm a bug and add it to the registry (anyone).
        (confirmed? {:msg msg :header X-Woof-Bug :what :bug})
        (add-bug msg refs)

        ;; Mark a bug as fixed (anyone).  If an email contains
        ;; X-Woof-Bug: fixed, we scan all refs from this email and see
        ;; if we can find a matching ref in those of a bug, and if
        ;; yes, then we mark the bug as :fixed by the message id.
        (and (some-db-refs? refs)
             (closed? {:msg msg :header X-Woof-Bug :what :bug}))
        (fix-bug msg refs)

        ;; Call for help (anyone).
        (confirmed? {:header X-Woof-Help})
        (add-help msg refs)

        ;; Cancel a call for help (anyone).
        (and (some-db-refs? refs)
             (closed? {:header X-Woof-Help}))
        (cancel-help msg refs)

        ;; Mark a change as canceled (anyone).
        (and (some-db-refs? refs)
             (closed? {:header X-Woof-Change}))
        (cancel-change msg refs)

        ;; Announce a breaking change in the current development
        ;; branches and associate it with future version(s) (anyone).
        X-Woof-Change
        (add-change msg refs X-Woof-Change)

        ;; Make a release (only the release manager).
        X-Woof-Release
        (add-release msg X-Woof-Release)
        :else nil))))

;;; Monitoring
(def woof-monitor (atom nil))

(defn- start-inbox-monitor! []
  (reset!
   woof-monitor
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
                 (events/stop @woof-monitor)
                 (catch Exception _ nil))
               (start-inbox-monitor!))))

(def woof-manager) ;; FIXME: Needed?
(mount/defstate woof-manager
  :start (do (start-tasks!)
             (timbre/info "Woof started"))
  :stop (when woof-manager
          (events/stop woof-monitor)
          (timbre/info "Woof stopped")))
