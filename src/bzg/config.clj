(ns bzg.config)

(def env
  {;; Configure the mailbox to monitor
   :inbox-user     (System/getenv "WOOF_INBOX_USER")
   :inbox-server   (System/getenv "WOOF_INBOX_SERVER")
   :inbox-password (System/getenv "WOOF_INBOX_PASSWORD")
   :inbox-folder   (System/getenv "WOOF_INBOX_FOLDER")

   ;; General application options
   :admin-address        (System/getenv "WOOF_ADMIN_ADDRESS")
   :admin-username       (System/getenv "WOOF_ADMIN_NAME")
   :port                 (or (System/getenv "WOOF_PORT") "3000")
   :db-dir               (or (System/getenv "WOOF_DB_DIR") ".db")
   :log-file             (or (System/getenv "WOOF_LOG_FILE") "logs.txt")
   :mailing-list-address (System/getenv "WOOF_MAILING_LIST_ADDRESS")
   :base-url             (or (System/getenv "WOOF_BASE_URL")
                             "https://localhost:3000")

   ;; Configuration to send notification emails
   :smtp-host       (System/getenv "WOOF_SMTP_HOST")
   :smtp-login      (System/getenv "WOOF_SMTP_LOGIN")
   :smtp-password   (System/getenv "WOOF_SMTP_PASSWORD")
   :mail-url-format (System/getenv "WOOF_MAIL_URL_FORMAT")

   ;; Configuring the HTML page
   :theme            (or (System/getenv "WOOF_THEME") "default")
   :title            (System/getenv "WOOF_TITLE")
   :project-name     (System/getenv "WOOF_PROJECT_NAME")
   :project-url      (System/getenv "WOOF_PROJECT_URL")
   :contribute-url   (System/getenv "WOOF_CONTRIBUTE_URL")
   :contribute-cta   (System/getenv "WOOF_CONTRIBUTE_CTA")
   :feed-title       (System/getenv "WOOF_FEED_TITLE")
   :feed-description (System/getenv "WOOF_FEED_DESCRIPTION")
   })

(def defaults
  {:theme          (:theme env)
   :maintenance    false
   :notifications  true
   :features       {:bug    true :request true :announcement true
                    :change true :release true :mail         true
                    :patch  true}
   :max            {:releases 4 :mails 100 :announcements 10}
   :export-formats {:rss true :json true :org true :md true}})

(def report-strings
  {:applied   "Applied"
   :approved  "Approved"
   :canceled  "Canceled"
   :confirmed "Confirmed"
   :done      "Done"
   :fixed     "Fixed"
   :handled   "Handled"})

(def admin-report-strings
  {;; Contributors actions
   :notifications     "Notifications"
   ;; Maintainers actions
   :add-maintainer    "Add maintainer"
   :ban               "Ban"
   :maintenance       "Maintenance"
   ;; Admin actions
   :add-admin         "Add admin"
   :ignore            "Ignore"
   :remove-admin      "Remove admin"
   :remove-maintainer "Remove maintainer"
   :unban             "Unban"
   :unignore          "Unignore"})

(def reports
  {:bug          #{:confirmed :canceled :fixed}
   :patch        #{:approved :canceled :applied}
   :request      #{:handled :canceled :done}
   :change       #{:canceled}
   :announcement #{:canceled}
   :release      #{:canceled}})

(def permissions
  {:admin       #{:add-admin :remove-admin :remove-maintainer
                  :unban :unignore}
   :maintainer  #{
                  ;; FIXME: Todo
                  ;; :maintainance
                  :add-maintainer :ban :ignore}
   :contributor #{:notifications}})

(defn format-email-notification
  [{:keys [notification-type
           from id
           action-string status-string]}]
  (str
   (condp = notification-type
     :new
     (format "Thanks for sharing this %s!\n\n" action-string)
     :action-reporter
     (format "Thanks for marking this %s as %s.\n\n"
             action-string status-string)
     :action-op
     (format "%s marked your %s as %s.\n\n"
             from action-string status-string))
   (when-let [link-format (not-empty (:mail-url-format env))]
     (format "You can find your email here:\n%s\n\n"
             (format link-format id)))
   (when-let [contribute-url (not-empty (:contribute-url env))]
     (format "More on how to contribute to %s:\n%s"
             (:project-name env) contribute-url))))
