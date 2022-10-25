(ns bzg.core
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure-mail.parser :as parser]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [bzg.fetch :as fetch]
            [bzg.db :as db]
            [mount.core :as mount]
            [tea-time.core :as tt]
            [postal.core :as postal]
            [postal.support]
            [clojure.core.async :as async]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [taoensso.timbre.appenders (postal :as postal-appender)]
            [datalevin.core :as d]
            [clojure.edn :as edn]))

(defn set-defaults []
  (d/transact! db/conn [(merge {:defaults "init"} (:defaults db/config))]))

(def priority 0)

;; Utility functions

(defn- action-re [& [list-id]]
  (let [watch            (or (:watch (get (:sources db/config) list-id))
                             (:watch db/config))
        subject-prefix   #(:subject-prefix (% watch))
        subject-match    #(:subject-match (% watch))
        build-pattern    #(re-pattern
                           (str (format %1 (string/join "|" (subject-prefix %3)))
                                (when-let [match (seq (subject-match %3))]
                                  (str "|" (string/join "|" match)))))
        prefix-re        "(?:^\\[(?:%s)\\])"
        change-prefix-re "(?:^\\[(?:%s)\\s+([^]]+)\\])"
        any-re           ".*%s.*"]
    {:patch        (build-pattern
                    "(?:^\\[(?:%s)(?: [^\\s]+)?(?: [0-9]+/[0-9]+)?\\])"
                    any-re
                    :patch)
     :bug          (build-pattern prefix-re any-re :bug)
     :request      (build-pattern prefix-re any-re :request)
     :announcement (build-pattern prefix-re any-re :announcement)
     :change       (build-pattern change-prefix-re any-re :change)
     :release      (build-pattern change-prefix-re any-re :release)}))

(def action-prefixes
  (flatten (map :subject-prefix (vals (:watch db/config)))))

(defn un-ify [l]
  (concat l (map #(str "Un" (string/lower-case %)) l)))

(def report-words-all
  (->> db/config
       :watch
       vals
       (map :triggers)
       (into #{})
       (map vals)
       flatten
       distinct
       un-ify))

(def report-words-re
  (->> report-words-all
       (string/join "|")
       re-pattern))

(defn- report-words [report-type]
  (let [original-report-words
        (flatten (vals (:triggers (report-type (:watch db/config)))))]
    (into #{} (un-ify original-report-words))))

(defn- make-to [username address]
  (str username " <" address ">"))

(defn- true-id [^String id]
  (peek (re-matches #"^<?(.+[^>])>?$" id)))

(defn- trim-subject [^String s]
  (-> s
      (string/replace #"^(R[Ee] ?: ?)+" "")
      (string/replace #" *\([^)]+\)" "")
      (string/trim)))

(defn- trim-url-brackets [^String s]
  (-> s (string/replace #"^<?([^>]+)>?$" "$1")))

(defn- trim-subject-prefix [^String s]
  (let [p (re-pattern
           (format "^\\[(?:%s).*\\] .*$"
                   (string/join "|" action-prefixes)))]
    (if-let [s (re-matches p s)]
      s
      (string/replace s #"^\[[^]]+\] " ""))))

(defn slug-to-list-id [^String slug]
  (when (not-empty slug)
    (key (first (filter #(= (:slug (val %)) slug)
                        (:sources db/config))))))

(defn list-id-to-slug [^String list-id]
  (when (not-empty list-id)
    (-> db/config :sources (get list-id) :slug)))

(defn archived-message [{:keys [list-id message-id archived-at]}]
  (if archived-at (trim-url-brackets archived-at)
      (if-let [fmt (not-empty
                    (:archived-message-format
                     (get (:sources db/config) list-id)))]
        (format fmt message-id)
        (if-let [fmt (:archived-list-message-format db/config)]
          (format fmt list-id message-id)
          ""))))

(def email-re #"[^<@\s;,]+@[^>@\s;,]+")

(defn- true-email-address [s]
  (re-find email-re (string/replace s #"mailto:" "")))

;; (defn format-email-notification
;;   [{:keys [notification-type from id list-id
;;            action-string status-string]}]
;;   (str
;;    (condp = notification-type
;;      :new
;;      (str (format "Thanks for sharing this %s!\n\n" action-string)
;;           (when (and (:support-url db/config)
;;                      (some #{"bug" "request"} (list action-string)))
;;             (str (or (:support-cta-email db/config)
;;                      (:support-cta db/config)
;;                      "Please support this project")
;;                  ":\n"
;;                  (:support-url db/config)
;;                  "\n\n")))
;;      :action-reporter
;;      (format "Thanks for marking this %s as %s.\n\n"
;;              action-string status-string)
;;      :action-op
;;      (format "%s marked your %s as %s.\n\n"
;;              from action-string status-string))

;;    (when-let [archived-at
;;               (not-empty (archived-message
;;                           {:list-id list-id :message-id id}))]
;;      (format "You can find your email here:\n%s\n\n" archived-at))

;;    (when-let [contribute-url (not-empty (:contribute-url db/config))]
;;      (str (or (:contribute-cta-email db/config)
;;               (:contribute-cta db/config)
;;               (format "Please contribute to %s"
;;                       (:project-name db/config)))
;;           ":\n"
;;           contribute-url))))

;; Main reports functions

;; Core db functions to add and update entities

(defn- add-log! [date msg]
  (d/transact! db/conn [{:log date :msg msg}]))

(defn- add-mail! [{:keys [id from subject] :as msg} & private]
  (let [{:keys [List-Post X-BeenThere References Archived-At]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        id          (true-id id)
        list-id     (when-let [lid (or List-Post X-BeenThere)]
                      (true-email-address lid))
        refs-string References
        refs        (if refs-string
                      (into #{} (string/split refs-string #"\s")) #{})]
    ;; Add the email
    (d/transact! db/conn [{:message-id id
                           :list-id    list-id
                           :archived-at
                           (archived-message {:list-id     list-id
                                              :archived-at Archived-At
                                              :message-id  id})
                           :subject    (trim-subject-prefix subject)
                           :references refs
                           :private    (or private false)
                           :from       (:address (first from))
                           :username   (:name (first from))
                           :date       (java.util.Date.)
                           :backrefs   1}])
    ;; Return the added mail eid
    (:db/id (d/entity db/db [:message-id id]))))

(defn- add-mail-private! [msg]
  (add-mail! msg (java.util.Date.)))

(defn update-person! [{:keys [email username role]} & [action]]
  ;; An email is enough to update a person
  (let [existing-person    (d/entity db/db [:email email])
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
    (when (d/transact! db/conn [person])
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

(def permissions
  {:admin       #{:add-admin :remove-admin
                  :add-feature :remove-feature
                  :remove-maintainer :undelete :unignore
                  :global-notifications}
   :maintainer  #{:maintenance :add-maintainer :delete :ignore}
   :contributor #{:notifications :home :support}})

(def config-strings-re
  (let [{:keys [admin maintainer contributor]} permissions]
    (->> (concat admin maintainer contributor)
         (map #(% (:admin-report-words db/config)))
         (string/join "|")
         (format "(%s): (.+)\\s*$")
         re-pattern)))

;; Check whether a report is an action against a known entity
(defn- is-in-a-known-thread? [references]
  (doseq [i (filter #(seq (d/q `[:find ?e :where [?e :message-id ~%]] db/db))
                    references)]
    (let [backrefs (:backrefs (d/entity db/db [:message-id i]))]
      (d/transact! db/conn [{:message-id i :backrefs (inc backrefs)}]))))

(defn- is-report-update? [report-type body-report references]
  ;; Is there a known trigger (e.g. "Canceled") for this report type
  ;; in the body of the email?
  (when (some (report-words report-type) (list body-report))
    ;; Is this trigger against a known report, and if so, which one?
    (when-let [e (-> #(ffirst (d/q `[:find ?e
                                     :where
                                     [?e ~report-type ?ref]
                                     [?ref :message-id ~%]]
                                   db/db))
                     (map references)
                     ;; FIXME: Is this below really needed?
                     (as-> refs (remove nil? refs))
                     first)]
      (let [triggers (:triggers (report-type (:watch db/config)))
            ;; Is this about :acked, :owned, :closed?
            status   (key (first (filter
                                  #(some (into #{} (un-ify (val %)))
                                         (list body-report))
                                  triggers)))]
        {:status
         (if (re-find #"(?i)Un" body-report)
           ;; Maybe return :unacked, :unowned or :unclosed
           (keyword (str "un" (name status)))
           status)
         :upstream-report-eid e}))))

;; Setup logging

(defn- datalevin-appender []
  {:enabled?   true
   :async?     false
   :min-level  :info
   :rate-limit nil
   :output-fn  nil
   :fn
   (fn [data] (add-log! (java.util.Date.) (force (:msg_ data))))})

(let [appenders
      (cond-> {:println (appenders/println-appender {:stream :auto})}
        ;; Shall we log in log-file?
        (and (some #{:file} (:log db/config)) (not-empty (:log-file db/config)))
        (conj {:spit (appenders/spit-appender {:fname (:log-file db/config)})})
        ;; Shall we log in db too?
        (some #{:db} (:log db/config))
        (conj {:datalevin-appender (datalevin-appender)})
        ;; Shall we log as mails?
        (some #{:mail} (:log db/config))
        ( conj
         {:postal (merge (postal-appender/postal-appender ;; :min-level :warn
                          ^{:host (:smtp-host db/config)
                            :user (:smtp-login db/config)
                            :port (:smtp-port db/config)
                            :pass (:smtp-password db/config)
                            :tls  (:smtp-use-tls db/config)}
                          {:from (:smtp-login db/config)
                           :to   (make-to
                                  (:admin-username db/config)
                                  (:admin-address db/config))}))}))]
  (timbre/set-config!
   {:level     :debug
    :output-fn (partial timbre/default-output-fn {:stacktrace-fonts {}})
    :appenders appenders}))

;; Email notifications

(defn- send-email [{:keys [msg body purpose new-subject reply-to]}]
  (let  [{:keys [id from subject references]}
         msg
         to (make-to (:username (d/entity db/db [:email from])) from)]
    (try
      (when-let
          [res (postal/send-message
                {:host (:smtp-host db/config)
                 :port (:smtp-port db/config)
                 :tls  (:smtp-use-tls db/config)
                 :user (:smtp-login db/config)
                 :pass (:smtp-password db/config)}
                (merge
                 {:from       (:smtp-login db/config)
                  :message-id #(postal.support/message-id (:hostname db/config))
                  :reply-to   (or reply-to (make-to (:admin-username db/config)
                                                    (:admin-address db/config)))
                  :to         to
                  :subject    (or new-subject (str "Re: " (trim-subject subject)))
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
  (if (:global-notifications (d/entity db/db [:defaults "init"]))
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

(defn- new? [what list-id msg]
  (let [action-re (action-re list-id)]
    (condp = what
      :patch        (or
                     ;; New patches with a subject starting with "[PATCH"
                     (re-find (:patch action-re) (:subject msg))
                     ;; New patches with a text/x-diff or text/x-patch MIME part
                     (and (:multipart? msg)
                          (not-empty
                           (filter #(re-matches #"^text/x-(diff|patch).*" %)
                                   (map :content-type (:body msg))))))
      :bug          (re-find (:bug action-re) (:subject msg))
      :request      (re-find (:request action-re) (:subject msg))
      :announcement (re-find (:announcement action-re) (:subject msg))
      :change       (when-let [m (re-find (:change action-re) (:subject msg))]
                      (peek m))
      :release      (when-let [m (re-find (:release action-re) (:subject msg))]
                      (peek m)))))

(defn- add-admin! [cmd-val from]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [person (not-empty (into {} (d/entity db/db [:email email])))]
        (let [output (d/transact! db/conn [(conj person [:admin (java.util.Date.)])])]
          (mail nil (format "Hi %s,\n\n%s added you as an admin.
\nSee this page on how to use Woof! as an admin:\n%s/howto\n\nThanks!"
                            (:username person)
                            from
                            (:hostname db/config))
                :add-admin
                (format "[%s] You are now a Woof! admin"
                        (:project-name db/config))
                from)
          (timbre/info (format "%s has been granted admin permissions" email))
          output)))))

(defn- remove-admin! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (let [admin-entity (d/entity db/db [:email email])]
        (if (true? (:root admin-entity))
          (timbre/error "Trying to remove the root admin: ignore")
          (when-let [output
                     (d/transact!
                      db/conn [[:db/retract (d/entity db/db [:email email]) :admin]])]
            (timbre/info (format "%s has been denied admin permissions" email))
            output))))))

(defn- add-maintainer! [cmd-val from]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [person (not-empty (into {} (d/entity db/db [:email email])))]
        (let [output (d/transact! db/conn [(conj person [:maintainer (java.util.Date.)])])]
          (mail nil (format "Hi %s,\n\n%s added you as an maintainer.
\nSee this page on how to use Woof! as an maintainer:\n%s/howto\n\nThanks!"
                            (:username person)
                            from
                            (:hostname db/config))
                :add-maintainer
                (format "[%s] You are now a Woof! maintainer"
                        (:project-name db/config))
                from)
          (timbre/info (format "%s has been granted maintainer permissions" email))
          output)))))

(defn- remove-maintainer! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [output
                 (d/transact!
                  db/conn [[:db/retract (d/entity db/db [:email email]) :maintainer]])]
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
                        db/db)
                  ;; Delete all but changes and releases, even if the
                  ;; email being deleted is from a maintainer
                  [:bug :patch :request :announcement])
                 (map concat) flatten)]
        (when (seq reports)
          (doseq [r reports]
            (d/transact! db/conn [{:db/id r :deleted (java.util.Date.)}]))
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
                        db/db)
                  [:bug :patch :request :announcement])
                 (map concat) flatten)]
        (when (seq reports)
          (doseq [r reports]
            (d/transact! db/conn [[:db/retract r :deleted]]))
          (timbre/info (format "Past mails from %s are not deleted anymore" email))
          true)))))

(defn- unignore! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [output
                 (d/transact!
                  db/conn [[:db/retract (d/entity db/db [:email email]) :ignored]])]
        (timbre/info (format "Mails from %s won't be ignored anymore" email))
        output))))

(defn- ignore! [cmd-val]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (let [person     (into {} (d/entity db/db [:email email]))
            as-ignored (conj person [:ignored (java.util.Date.)])]
        ;; Never ignore the root admin
        (when-not (true? (:root person))
          (when-let [output (d/transact! db/conn [as-ignored])]
            (timbre/info (format "Mails from %s will now be ignored" email))
            output))))))

(defn- config-maintenance! [status]
  (d/transact! db/conn [{:defaults "init" :maintenance status}])
  (timbre/info (format "Maintenance is now: %s" status)))

(defn- config-notifications! [status]
  (d/transact! db/conn [{:defaults "init" :notifications status}])
  (timbre/info (format "Notifications are now: %s" status)))

(defn- config! [{:keys [commands msg]}]
  (let [from  (:address (first (:from msg)))
        roles (select-keys (d/entity db/db [:email from])
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
          "Add maintainer"       (add-maintainer! cmd-val from)
          "Delete"               (delete! cmd-val)
          "Global notifications" (config-notifications! (edn/read-string cmd-val))
          "Home"                 (update-person! {:email from} [:home cmd-val])
          "Ignore"               (ignore! cmd-val)
          "Maintenance"          (config-maintenance! (edn/read-string cmd-val))
          "Notifications"        (update-person! {:email from} [:notifications cmd-val])
          "Remove admin"         (remove-admin! cmd-val)
          "Remove maintainer"    (remove-maintainer! cmd-val)
          "Support"              (update-person! {:email from} [:support cmd-val])
          "Undelete"             (undelete! cmd-val)
          "Unignore"             (unignore! cmd-val)
          nil)))
    (add-mail-private! msg)))

;;;; TODO
;; (defn- report-notify! [report-type msg-eid status-report-eid]
;;   ;; If status is about undoing, retract existing status, otherwise
;;   ;; add
;;   (when-let [s status]
;;     (if (re-matches #"^un(.+)" (name s))
;;       (d/transact! db/conn [[:db/retract [report-type (:db/id op-report-mail)] s]])
;;       (do (d/transact! db/conn [report-type msg-eid])
;;           (d/transact! db/conn [(d/entity db/db [report-type msg-eid] status true)]))))
;;   (if status-string
;;     ;; Report against a known entry
;;     (do
;;       ;; Timbre logging
;;       (timbre/info
;;        (format "%s (%s) marked %s reported by %s (%s) as %s"
;;                from msgid action-string op-from op-msgid (name status-string)))
;;       ;; Send email to the action reporter, if he's not an admin/maintainer
;;       (if-not admin-or-maintainer?
;;         (mail msg (format-email-notification
;;                    (merge msg action-status {:notification-type :action-reporter}))
;;               :ack-reporter)
;;         (timbre/info "Skipping email ack for admin or maintainer"))
;;       ;; Send email to the original poster, unless it is the action reporter
;;       (if-not (= from op-from)
;;         (mail op-msg (format-email-notification
;;                       (merge msg action-status {:notification-type :action-op}))
;;               :ack-op-reporter)
;;         (timbre/info "Do not ack original poster, same as reporter")))
;;     ;; Report a new entry
;;     (do
;;       ;; Timbre logging
;;       (timbre/info
;;        (format "%s (%s) reported a new %s" from msgid action-string))
;;       ;; Send email to the original poster
;;       (if-not admin-or-maintainer?
;;         (mail op-msg (format-email-notification
;;                       (merge msg {:notification-type :new} action-status))
;;               :ack-op)
;;         (timbre/info "Skipping email ack for admin or maintainer")))))

(defn- report! [{:keys [report-type status-trigger report-eid version] :as report}]
  (let [;; If there is a status, add or update a report
        status               (some #{:acked :owned :closed
                                  :unacked :unowned :unclosed} (keys report))
        status-name          (and status (name status))
        status-report-eid    (and status (status report))
        from                 (or (:from (d/entity db/db report-eid))
                              (:from (d/entity db/db status-report-eid)))
        username             (or (:username (d/entity db/db report-eid))
                              (:username (d/entity db/db status-report-eid)))
        effective?           (let [closed-statuses
                                   ;; First closed status indicates an
                                   ;; "effective" report (e.g. Fixed,
                                   ;; Applied, Done.)
                                   (-> db/config :watch report-type :triggers :closed)]
                            (when (and status status-trigger (< 1 (count closed-statuses)))
                              (true? (= status-trigger
                                        (first closed-statuses)))))
        admin-or-maintainer? (when-let [person (d/entity db/db [:email from])]
                               (or (:admin person) (:maintainer person)))]
    ;; Possibly add a new person
    (update-person! {:email from :username username})
    (if status-report-eid
      ;; This is a status update about an existing report
      (if (re-matches #"^un(.+)" status-name)
        ;; Status is about undoing, retract attribute
        (d/transact! db/conn [[:db/retract [report-type report-eid]
                               (keyword (string/replace status-name #"^un" ""))]])
        ;; Status is a positive statement, set it to true
        (d/transact! db/conn [{:db/id     report-eid
                               status     status-report-eid
                               :effective effective?
                               }]))
      ;; This is a change or a or a release
      (if (and admin-or-maintainer? version)
        (d/transact! db/conn [{report-type report-eid :version version}])
        (d/transact! db/conn [{report-type report-eid}])))))

(defn- release-changes! [list-id version release-id]
  (let [changes-reports
        (->> (fetch/reports {:list-id list-id :report-type :change})
             (filter #(= version (:version %)))
             (map #(get % :db/id)))]
    (doseq [r changes-reports]
      (d/transact! db/conn [{:db/id r :released release-id}]))))

;; FIXME: where to use?
;; (defn- unrelease-changes! [list-id release-id]
;;   (let [changes-to-unrelease
;;         (->> (filter #(= release-id (:released %))
;;                      (get-reports {:list-id list-id :report-type :change}))
;;              (map #(get % :db/id)))]
;;     (doseq [r changes-to-unrelease]
;;       (d/transact! db/conn [[:db/retract r :released]]))))

(defn process-mail [{:keys [from] :as msg}]
  (let [{:keys [List-Post X-BeenThere References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        references  (when (not-empty References)
                      (->> (string/split References #"\s")
                           (keep not-empty)
                           (map true-id)))
        list-id     (when-let [lid (or List-Post X-BeenThere)]
                      (true-email-address lid))
        from        (:address (first from))
        admins      (fetch/admins)
        maintainers (fetch/maintainers)
        defaults    (d/entity db/db [:defaults "init"])
        watched     (:watch db/config)]

    (when (and
           ;; First check whether this user should be ignored
           (not (:ignored (d/entity db/db [:email from])))
           ;; Always process messages from an admin
           (or (some admins (list from))
               (and
                ;; Don't process anything when under maintenance
                (not (:maintenance defaults))
                ;; When not under maintenance, always process direct
                ;; mails from maintainers
                (or (some maintainers (list from))
                    ;; A mailing list, only process mails sent there
                    (some #{list-id}
                          (keys (:sources db/config)))))))

      ;; Possibly increment backrefs count in known emails
      (is-in-a-known-thread? references)

      (or
       ;; Detect a new bug/patch/request
       (let [done (atom nil)]
         (doseq [w      [:patch :bug :request]
                 :while (nil? @done)]
           (when (and (w watched) (new? w list-id msg))
             (reset! done (report! {:report-type w :report-eid (add-mail! msg)}))))
         @done)
       ;; Or detect new release/change/announcement by a maintainer
       (when (some maintainers (list from))
         (or
          (when (:announcement watched)
            (when (new? :announcement list-id msg)
              (report! {:report-type :announcement
                        :report-eid  (add-mail! msg)})))
          (when (:change watched)
            (when-let [version (new? :change list-id msg)]
              (if (some (fetch/released-versions list-id) (list version))
                (timbre/error
                 (format "%s tried to announce a change against released version %s"
                         from version))
                (report! {:report-type :change
                          :report-eid  (add-mail! msg)
                          :version     version}))))
          (when (:release watched)
            (when-let [version (new? :release list-id msg)]
              (let [release-report-eid (add-mail! msg)]
                (report! {:report-type :release
                          :report-eid  release-report-eid
                          :version     version})
                (release-changes! list-id version release-report-eid))))))

       ;; Or a command or new actions against known reports
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

         (if (empty? references)

           ;; This is a configuration command
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
                     (map #(re-find report-words-re %))
                     (remove nil?)
                     first)]

             (or
              ;; New action against a known patch/bug/request
              (let [done (atom nil)]
                (doseq [w      [:patch :bug :request]
                        :while (nil? @done)]
                  (when (w watched)
                    (when-let [{:keys [upstream-report-eid status priority]}
                               (is-report-update? w body-report references)]
                      (reset! done
                              (report! {:report-type    w
                                        :report-eid     upstream-report-eid
                                        :status-trigger body-report
                                        status          (add-mail! msg)
                                        :priority       priority})))))
                @done)

              ;; Or an action against existing changes/releases by a maintainer
              (if (some maintainers (list from))
                (let [done (atom nil)]
                  (doseq [w      [:change :release :announcement]
                          :while (nil? @done)]
                    (when (w (:announcement watched))
                      (when-let [{:keys [upstream-report-eid status priority]}
                                 (is-report-update? w body-report references)]
                        (reset! done
                                (report! {:report-type    w
                                          :report-eid     upstream-report-eid
                                          :status-trigger body-report
                                          status          (add-mail! msg)
                                          :priority       priority}))))))
                (timbre/warn
                 (format
                  "%s tried to update a change or a release while not a maintainer"
                  from)))))))))))

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
                                  (:inbox-server db/config)
                                  (:inbox-user db/config)
                                  (:inbox-password db/config))
         folder       (mail/open-folder mystore (:inbox-folder db/config)
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
             (timbre/info "Woof email monitoring started"))
  :stop (when woof-manager
          (events/stop woof-inbox-monitor)
          (timbre/info "Woof email monitoring stopped")))
