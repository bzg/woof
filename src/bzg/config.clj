(ns bzg.config)

(def env
  {
   ;; General application settings
   :port     (or (System/getenv "WOOF_PORT") "3000")
   :db-dir   (or (System/getenv "WOOF_DB_DIR") ".db")
   :log-file (or (System/getenv "WOOF_LOG_FILE") "logs.txt")
   :base-url (or (System/getenv "WOOF_BASE_URL") "https://localhost:3000")

   ;; Set the application admin
   :admin-address  (System/getenv "WOOF_ADMIN_ADDRESS")
   :admin-username (System/getenv "WOOF_ADMIN_NAME")

   ;; Set the mailbox to monitor
   :inbox-user     (System/getenv "WOOF_INBOX_USER")
   :inbox-server   (System/getenv "WOOF_INBOX_SERVER")
   :inbox-password (System/getenv "WOOF_INBOX_PASSWORD")
   :inbox-folder   (System/getenv "WOOF_INBOX_FOLDER")

   ;; The mailing list address that Woof! to which the monitored
   ;; mailbox is subscribed.
   :mailing-list-address (System/getenv "WOOF_MAILING_LIST_ADDRESS")

   ;; The formatting string for links.  It should contain one and only
   ;; one %s occurrence that will be filled with the Message-Id of the
   ;; report.  This can be the URL of the mailing list archive, e.g.
   ;; https://list.orgmode.org/%s/
   :mail-url-format (System/getenv "WOOF_MAIL_URL_FORMAT")

   ;; Send notification emails
   :smtp-host     (System/getenv "WOOF_SMTP_HOST")
   :smtp-login    (System/getenv "WOOF_SMTP_LOGIN")
   :smtp-password (System/getenv "WOOF_SMTP_PASSWORD")

   ;; Configure HTML and RSS contents
   :theme                (or (System/getenv "WOOF_THEME") "default")
   :title                (System/getenv "WOOF_TITLE")
   :project-name         (System/getenv "WOOF_PROJECT_NAME")
   :project-url          (System/getenv "WOOF_PROJECT_URL")
   :contribute-url       (System/getenv "WOOF_CONTRIBUTE_URL")
   :contribute-cta       (System/getenv "WOOF_CONTRIBUTE_CTA")
   :contribute-cta-email (System/getenv "WOOF_CONTRIBUTE_CTA_EMAIL")
   :support-url          (System/getenv "WOOF_SUPPORT_URL")
   :support-cta          (System/getenv "WOOF_SUPPORT_CTA")
   :support-cta-email    (System/getenv "WOOF_SUPPORT_CTA_EMAIL")
   :feed-title           (System/getenv "WOOF_FEED_TITLE")
   :feed-description     (System/getenv "WOOF_FEED_DESCRIPTION")
   })

(def defaults
  {:theme         (:theme env)
   :admin         (:admin-address env)
   :maintenance   false
   :notifications true
   :features      {;; Each feature with a dedicated tab
                   :announcement true
                   :bug          true
                   :patch        true
                   ;; Features on the homepage
                   :change       true
                   :release      true
                   :request      true
                   ;; Also on the homepage, mostly for testing puropse
                   :mail         false
                   }
   ;; Show only x latest releases/mails/announcements
   :max           {:releases      4
                   :mails         100
                   :announcements 10}
   :export        {:rss  true
                   :json true
                   :org  true
                   :md   true
                   }})

(def report-strings
  {:applied   "Applied"
   :approved  "Approved"
   :canceled  "Canceled"
   :confirmed "Confirmed"
   :done      "Done"
   :fixed     "Fixed"
   :handled   "Handled"})

(def reports
  {:bug          #{:confirmed :canceled :fixed}
   :patch        #{:approved :canceled :applied}
   :request      #{:handled :canceled :done}
   :change       #{:canceled}
   :announcement #{:canceled}
   :release      #{:canceled}})

(def admin-report-strings
  {;; Contributors actions
   :notifications     "Notifications"
   ;; Maintainers actions
   :add-maintainer    "Add maintainer"
   :maintenance       "Maintenance"
   ;; Admin actions
   :add-admin         "Add admin"
   :add-export        "Add export"
   :delete            "Delete"
   :remove-feature    "Remove feature"
   :add-feature       "Add feature"
   :ignore            "Ignore"
   :remove-admin      "Remove admin"
   :remove-export     "Remove export"
   :remove-maintainer "Remove maintainer"
   :set-theme         "Set theme"
   :undelete          "Undelete"
   })

;; Admin permissions include maintainer permissions which include
;; contributors ones.
(def permissions
  {:admin       #{:add-admin :remove-admin
                  :add-feature :remove-feature
                  :remove-maintainer :undelete :unignore
                  :add-export :remove-export :set-theme}
   :maintainer  #{:maintenance :add-maintainer :delete :ignore}
   :contributor #{:notifications}})

(defn format-email-notification
  [{:keys [notification-type from id
           action-string status-string]}]
  (str
   (condp = notification-type
     :new
     (str (format "Thanks for sharing this %s!\n\n" action-string)
          (when (and (:support-url env)
                     (some #{"bug" "request"} (list action-string)))
            (str (or (:support-cta-email env)
                     (:support-cta env)
                     "Please support this project")
                 ":\n"
                 (:support-url env)
                 "\n\n")))
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
     (str (or (:contribute-cta-email env)
              (:contribute-cta env)
              (format "Please contribute to %s"
                      (:project-name env)))
          ":\n"
          contribute-url))))
