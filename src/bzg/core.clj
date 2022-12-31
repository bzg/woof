;; Copyright (c) 2022 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns bzg.core
  (:require [clojure.java.io :as io]
            [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure-mail.parser :as parser]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [bzg.fetch :as fetch]
            [bzg.db :as db]
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

;; Utility functions

(defn- action-re [& [source-id]]
  (let [watch          (or (:watch (get (:sources db/config) source-id))
                           (:watch db/config))
        subject-prefix #(:subject-prefix (% watch))
        subject-match  #(:subject-match (% watch))
        build-pattern  #(re-pattern
                         (str (format "^\\[(%s)(?:\\s+([^\\]]+))?\\]"
                                      (string/join "|" (subject-prefix %)))
                              (when-let [match (seq (subject-match %))]
                                (str "\\s" (string/join "|" match)))))]
    {:patch        (build-pattern :patch)
     :bug          (build-pattern :bug)
     :request      (build-pattern :request)
     :announcement (build-pattern :announcement)
     :blog         (build-pattern :blog)
     :change       (build-pattern :change)
     :release      (build-pattern :release)}))

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
       un-ify
       (concat '("\\+1" "-1"))))

(def report-words-re
  (->> (concat
        report-words-all
        (:priority-words-all db/config))
       (string/join "|")
       (str "^")
       re-pattern))

(defn- report-words [report-type]
  (let [original-report-words
        (flatten (vals (:triggers (report-type (:watch db/config)))))]
    (into #{} (un-ify original-report-words))))

(defn make-to [^String username ^String address]
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

(defn slug-to-source-id [^String slug]
  (when (not-empty slug)
    (key (first (filter #(= (:slug (val %)) slug)
                        (:sources db/config))))))

(defn source-id-to-slug [^String source-id]
  (when (not-empty source-id)
    (-> db/config :sources (get source-id) :slug)))

(defn archived-message [{:keys [source-id message-id archived-at]}]
  (if archived-at
    (trim-url-brackets archived-at)
    (if-let [fmt (not-empty
                  (:archived-message-format
                   (get (:sources db/config) source-id)))]
      (format fmt message-id)
      (if-let [fmt (:archived-message-format db/config)]
        (format fmt source-id message-id)
        ""))))

(defn archived-message-raw [{:keys [source-id message-id archived-at]}]
  (let [fmt         (not-empty
                     (:archived-message-raw-format
                      (get (:sources db/config) source-id)))
        default-fmt (:archived-list-message-raw-format db/config)]
    (cond fmt         (format fmt message-id)
          default-fmt (format default-fmt source-id message-id)
          archived-at (str (trim-url-brackets archived-at) "/raw"))))

(def email-re #"[^<@\s;,]+@[^>@\s;,]+\.[^>@\s;,]+")

(defn- true-email-address [^String s]
  (when s (re-find email-re (string/replace s #"mailto:" ""))))

;; Core db functions to add and update entities

(defn- add-log! [date msg]
  (d/transact! db/conn [{:log date :msg msg}]))

(defn- get-mail-body [msg]
  (->> (if (:multipart? msg) (:body msg) (list (:body msg)))
       (map #(condp (fn [a b]
                      (re-matches
                       (re-pattern (str "text/" a ".*")) b))
                 (:content-type %)
               "plain" (:body %)
               "html"  (parser/html->text (:body %))))
       (string/join "\n")))

(defn- get-mail-patch [msg]
  (let [patch (atom nil)]
    (when (:multipart? msg)
      (doseq [part (:body msg) :while (nil? @patch)]
        (when (re-matches #"^text/x-(diff|patch).*" (:content-type part))
          (with-open [r (io/reader (:body part))]
            (reset! patch (slurp r)))))
      (string/replace @patch #"^>" ""))))

(defn- get-mail-body-as-seq [msg]
  (->> msg get-mail-body
       string/split-lines
       (map string/trim)
       (filter not-empty)))

(defn- mails-from-refs [references]
  (->> (d/q '[:find ?mid :where
              [?e :message-id ?mid]
              [?e :vote false]
              [?e :config false]]
            db/db)
       (map first)
       (filter #(some references (list %)))))

(defn- update-related-refs [message-id references]
  (let [related-mails (mails-from-refs references)
        updated-mails (atom #{message-id})]
    (doseq [m related-mails]
      (let [e          (d/entity db/db [:message-id m])
            e-msg-id   (:message-id e)
            e-rel-refs (:related-refs e)]
        (swap! updated-mails conj e-msg-id)
        (d/transact! db/conn [{:message-id m :related-refs (conj e-rel-refs message-id)}])))
    (d/transact! db/conn [{:message-id message-id :related-refs @updated-mails}])))

(defn- parse-refs [^String s]
  (if (not-empty s)
    (->> (string/split s #"\s")
         (keep not-empty)
         (map true-id)
         (into #{}))
    #{}))

(defn- add-mail! [{:keys [id from subject source-id] :as msg}
                  & [with-body? config-mail? vote-mail?]]
  (let [{:keys [References Archived-At X-Mailer]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        id              (true-id id)
        now             (java.util.Date.)
        msg-infos       {:source-id   source-id
                         :message-id  id
                         :archived-at Archived-At}
        references      (parse-refs References)
        archived-at-url (archived-message msg-infos)
        trimmed-subject (trim-subject-prefix subject)]
    ;; Add the email
    (d/transact! db/conn [{:message-id  id
                           :source-id   source-id
                           :subject     trimmed-subject
                           :archived-at archived-at-url
                           :references  references
                           :config      (or config-mail? false)
                           :vote        (or vote-mail? false)
                           :from        (:address (first from))
                           :username    (:name (first from))
                           :date        now
                           :refs-count  1}])
    ;; Possibly add body
    (when-let [body (and with-body? (get-mail-body msg))]
      (d/transact! db/conn [{:message-id id :body body}]))
    ;; Possibly add patch/patch-url
    (let [patch (get-mail-patch msg)
          patch-url
          (cond (re-matches #"^git-send-email.*" (or X-Mailer ""))
                (str archived-message-raw msg-infos)
                patch (str (:baseurl db/config) "/patch/" id))]
      (when patch-url
        (d/transact! db/conn [{:message-id id
                               :patch-body patch
                               :patch-url  patch-url}])))
    ;; Update related references for relevant emails
    (when-not vote-mail?
      (update-related-refs id references))
    ;; Return the added mail eid
    (:db/id (d/entity db/db [:message-id id]))))

(defn- add-config-mail! [msg]
  (add-mail! msg nil (java.util.Date.)))

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
                                  new-role action)
        transaction        (d/transact! db/conn [person])]
    (when (seq (:tx-data transaction))
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
                  (format "Added %s (%s) as a contributor"
                          username email))]
        (timbre/info msg)))))

(def config-strings-re
  (let [{:keys [admin maintainer contributor]} (:permissions db/config)]
    (->> (concat admin maintainer contributor)
         (map #(% (:admin-report-words db/config)))
         (remove nil?)
         (string/join "|")
         (format "(%s): (.+)\\s*$")
         re-pattern)))

;; Check whether a report is an action against a known entity
(defn- is-in-a-known-thread? [references]
  (doseq [i (mails-from-refs references)]
    (let [refs (:refs-count (d/entity db/db [:message-id i]))]
      (d/transact! db/conn [{:message-id i :refs-count (inc refs)}]))))

(defn- is-report-update? [report-type body-report references]
  ;; Is there a known trigger (e.g. "Canceled") for this report type
  ;; in the body of the email?
  (let [body-report-list (list body-report)
        priority-word?   (some (:priority-words-all db/config) body-report-list)
        vote?            (some #{"-1" "+1"} body-report-list)]
    (when (or priority-word?
              vote?
              (some (report-words report-type) (list body-report)))
      ;; Does this email triggers an action against a known report,
      ;; and if so, which one?
      (when-let [e (-> #(ffirst (d/q `[:find ?e
                                       :where
                                       [?e ~report-type ?ref]
                                       [?ref :message-id ~%]]
                                     db/db))
                       (map references)
                       ;; FIXME: Is this below really needed?
                       (as-> refs (remove nil? refs))
                       first)]
        (let [status ;; :important :urgent :acked, :owned or :closed ?
              (cond priority-word?
                    (if (re-find #"(?i)important" body-report) :important :urgent)
                    vote?
                    :last-vote
                    :else
                    (key (first (filter
                                 #(some (into #{} (un-ify (val %)))
                                        (list body-report))
                                 (:triggers (report-type (:watch db/config)))))))]
          {:status
           (if (re-find #"(?i)Un|No[tn]-?" body-report)
             ;; Maybe return :un-[status]
             (keyword (str "un" (name status)))
             status)
           :upstream-report-eid e})))))

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
                  :message-id #(postal.support/message-id (:baseurl db/config))
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
              ;; FIXME: check accuracy
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

(defn- new? [what source-id msg]
  (let [action-re (action-re source-id)
        subject   (:subject msg)]
    (condp = what
      :patch        (or
                     ;; New patches with a subject starting with "[PATCH..."
                     (re-find (:patch action-re) subject)
                     ;; New patches with a text/x-diff or text/x-patch MIME part
                     (and (:multipart? msg)
                          (not-empty
                           (filter #(re-matches #"^text/x-(diff|patch).*" %)
                                   (map :content-type (:body msg))))))
      :bug          (re-find (:bug action-re) subject)
      :request      (re-find (:request action-re) subject)
      :blog         (re-find (:blog action-re) subject)
      :announcement (re-find (:announcement action-re) subject)
      :change       (re-find (:change action-re) subject)
      :release      (re-find (:release action-re) subject))))

(defn- add-admin! [cmd-val from]
  (let [emails (->> (string/split cmd-val #"\s") (remove empty?))]
    (doseq [email emails]
      (when-let [person (not-empty (into {} (d/entity db/db [:email email])))]
        (let [output (d/transact! db/conn [(conj person [:admin (java.util.Date.)])])]
          (mail nil (format "Hi %s,\n\n%s added you as an admin.
\nSee this page on how to use Woof! as an admin:\n%s/howto\n\nThanks!"
                            (:username person)
                            from
                            (:baseurl db/config))
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
                            (:baseurl db/config))
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
                  [:bug :patch :request :announcement :blog])
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
                  [:bug :patch :request :announcement :blog])
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

(defn- user-allowed? [user action]
  (let [allowed-roles-for-action
        (->> (:permissions db/config)
             (map #(when (some (val %) (list action)) (key %)))
             (remove nil?)
             (into #{}))]
    (if-not user
      (:contributor allowed-roles-for-action )
      (some user allowed-roles-for-action))))

(defn- config! [{:keys [commands msg]}]
  (let [from (:address (first (:from msg)))
        user (d/entity db/db [:email from])]
    (doseq [[cmd cmd-val] commands]
      (when (user-allowed?
             user (->> (:admin-report-words db/config)
                       (filter (fn [[_ v]] (= v cmd)))
                       first key))
        (condp = cmd
          "Home"                 (update-person! {:email from} [:home cmd-val])
          "Support"              (update-person! {:email from} [:support cmd-val])
          "Notifications"        (update-person! {:email from} [:notifications cmd-val])
          "Add maintainer"       (add-maintainer! cmd-val from)
          "Delete"               (delete! cmd-val)
          "Ignore"               (ignore! cmd-val)
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
          "Unignore"             (unignore! cmd-val))))
    (add-config-mail! msg)))

;; Main reports functions

(defn- report! [{:keys [report-type status-trigger report-eid version] :as report}]
  (let [;; If there is a status, add or update a report
        status            (some #{:urgent :important
                                  :unurgent :unimportant
                                  :last-vote
                                  :acked :owned :closed
                                  :unacked :unowned :unclosed} (keys report))
        status-name       (and status (name status))
        status-report-eid (and status (status report))
        from              (or (:from (d/entity db/db report-eid))
                              (:from (d/entity db/db status-report-eid)))
        username          (or (:username (d/entity db/db report-eid))
                              (:username (d/entity db/db status-report-eid)))
        effective?        (let [closed-statuses
                                ;; First closed status indicates an
                                ;; "effective" report (e.g. Fixed,
                                ;; Applied, Done.)
                                (-> db/config :watch report-type :triggers :closed)]
                            (if (and status status-trigger (< 1 (count closed-statuses)))
                              (true? (= status-trigger (first closed-statuses)))
                              false))]
    ;; Possibly add a new person
    (update-person! {:email from :username username})
    (let [user (d/entity db/db [:email from])]
      (if status-report-eid
        ;; This is a status update about an existing report
        (cond
          ;; Status is about undoing, retract attribute
          (re-matches #"^un(.+)" status-name)
          (d/transact! db/conn [[:db/retract report-eid
                                 (keyword (string/replace status-name #"^un" ""))]])
          ;; Status is about voting, update :up or :down set with the email address
          (= status-name "last-vote")
          (d/transact! db/conn [(merge {:db/id report-eid status status-report-eid}
                                       (let [eid (d/entity db/db report-eid)]
                                         (if (re-matches #"^-1" status-trigger)
                                           {:down (into #{} (conj (:down eid) from))}
                                           {:up (into #{} (conj (:up eid) from))})))])
          ;; Status is a positive statement, set it to the eid of the report
          :else
          (d/transact! db/conn [{:db/id     report-eid
                                 status     status-report-eid
                                 :effective effective?}]))
        ;; This is a change or a or a release
        (when (user-allowed? user report-type)
          (if version
            (d/transact! db/conn [{report-type report-eid :version version}])
            (d/transact! db/conn [{report-type report-eid}])))))))

(defn- release-changes! [source-id version release-id]
  (let [changes-reports
        (->> (fetch/reports {:source-id source-id :report-type :change})
             (filter #(= version (:version %)))
             (map #(get % :db/id)))]
    (doseq [r changes-reports]
      (d/transact! db/conn [{:db/id r :released release-id}]))))

;; FIXME: where to use?
;; (defn- unrelease-changes! [source-id release-id]
;;   (let [changes-to-unrelease
;;         (->> (filter #(= release-id (:released %))
;;                      (get-reports {:source-id source-id :report-type :change}))
;;              (map #(get % :db/id)))]
;;     (doseq [r changes-to-unrelease]
;;       (d/transact! db/conn [[:db/retract r :released]]))))

(defn process-mail [{:keys [from to] :as msg}]
  (let [{:keys [References List-Post X-BeenThere]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        references     (parse-refs References)
        tos            (map :address to)
        to-all         (->> (concat tos (list List-Post X-BeenThere))
                            (map true-email-address)
                            (remove nil?)
                            (into #{}))
        source-id      (some to-all (keys (:sources db/config)))
        msg            (assoc msg :source-id source-id)
        to-woof-inbox? (some (into #{} tos) (:inbox-user db/config))
        from           (:address (first from))
        user           (d/entity db/db [:email from])
        defaults       (d/entity db/db [:defaults "init"])
        watched        (keys (:watch db/config))]

    (when (and
           ;; Don't process anything when under maintenance
           (not (:maintenance defaults))
           ;; Don't process emails from ignored users
           (not (:ignored user))
           ;; Process mails sent to an identified source or to Woof inbox
           (or source-id to-woof-inbox?))

      ;; Possibly increment refs-count in known reports
      (is-in-a-known-thread? references)

      (or
       ;; Detect a new report
       (let [done (atom nil)]
         (doseq [w watched :while (nil? @done)]
           (when (user-allowed? user w)
             (when-let [new-info (new? w source-id msg)]
               (let [version (when-not (= w :patch) (peek new-info))]
                 (if (and (some (into #{w}) [:change :release])
                          (some (fetch/released-versions source-id) (list version)))
                   (timbre/error
                    (format "%s tried to announce a change/release against an existing version %s"
                            from version))
                   (let [report-eid (add-mail! msg)]
                     (reset! done
                             (report!
                              {:report-type w
                               ;; Don't store version for patches, blog and announcement
                               :version     (when-not (some #{:patch :blog :announcement} (list w))
                                              version)
                               :report-eid  report-eid}))
                     (when (= w :release)
                       (release-changes! source-id version report-eid))))))))
         @done)

       ;; Or a command or new actions against known reports
       (let [body-seq (get-mail-body-as-seq msg)]
         (if (empty? references)
           ;; This is a configuration command
           (when-let [cmds
                      (->> body-seq
                           (map #(when-let [m (re-matches config-strings-re %)] (rest m)))
                           (remove nil?))]
             (config! {:commands cmds :msg msg}))
           ;; Or a report against a known patch, bug, etc
           (when-let [body-reports
                      (->> body-seq
                           (map #(re-find report-words-re %))
                           (remove nil?))]
             ;; New action against a known patch/bug/request
             (let [done (atom nil)]
               (doseq [w watched :while (nil? @done)]
                 (doseq [body-report body-reports]
                   (when-let [{:keys [upstream-report-eid status]}
                              (is-report-update? w body-report references)]
                     (reset! done
                             (report! {:report-type    w
                                       :report-eid     upstream-report-eid
                                       :status-trigger body-report
                                       status          (add-mail!
                                                        msg nil nil
                                                        (some #{"-1" "+1"} (list body-report)))})))))))))))))

;;; Inbox monitoring
(defn read-and-process-mail [mails]
  (->> mails
       (map message/read-message)
       (map process-mail)
       doall))

(def woof-inbox-monitor (atom nil))
(defn start-inbox-monitor! [{:keys [server user password folder]}]
  (reset!
   woof-inbox-monitor
   (let [session      (mail/get-session "imaps")
         mystore      (mail/store "imaps" session server user password)
         folder       (mail/open-folder mystore folder :readonly)
         idle-manager (events/new-idle-manager session)]
     (events/add-message-count-listener
      ;; Process incoming mails
      (fn [e] (->> e :messages read-and-process-mail))
      ;; Don't process deleted mails
      nil
      folder
      idle-manager)
     idle-manager)))

(defn reload-monitor! [opts]
  (tt/every! 1200 ;; 20 minutes
             (fn []
               (try
                 (events/stop @woof-inbox-monitor)
                 (catch Exception _ nil))
               (start-inbox-monitor! opts))))
