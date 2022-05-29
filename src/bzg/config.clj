(ns bzg.config
  (:require [clojure.edn :as edn]))

(def env
  {
   ;; General application settings
   :port     (or (System/getenv "WOOF_PORT") "3000")
   :db-dir   (or (System/getenv "WOOF_DB_DIR") ".db")
   :log-file (or (System/getenv "WOOF_LOG_FILE") "log.txt")
   :base-url (or (System/getenv "WOOF_BASE_URL") "http://localhost:3000")

   ;; Set the application admin
   :admin-address  (System/getenv "WOOF_ADMIN_ADDRESS")
   :admin-username (System/getenv "WOOF_ADMIN_NAME")

   ;; Set the mailbox to monitor
   :inbox-user     (System/getenv "WOOF_INBOX_USER")
   :inbox-server   (System/getenv "WOOF_INBOX_SERVER")
   :inbox-password (System/getenv "WOOF_INBOX_PASSWORD")
   :inbox-folder   (or (System/getenv "WOOF_INBOX_FOLDER") "inbox")

   ;; The mailing list address that Woof! to which the monitored
   ;; mailbox is subscribed.
   :mailing-list-address (System/getenv "WOOF_MAILING_LIST_ADDRESS")

   ;; The formatting string for links.  It should contain one and only
   ;; one %s occurrence that will be filled with the Message-Id of the
   ;; report.  This can be the URL of the mailing list archive, e.g.
   ;; https://list.orgmode.org/%s/
   :mail-url-format (System/getenv "WOOF_MAIL_URL_FORMAT")

   ;; Send notification emails
   :smtp-host    (System/getenv "WOOF_SMTP_HOST")
   :smtp-port    (edn/read-string (System/getenv "WOOF_SMTP_PORT"))
   :smtp-use-tls (edn/read-string (System/getenv "WOOF_SMTP_USE_TLS"))

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
   })

(def defaults
  {:theme         (:theme env)
   :maintenance   false
   :notifications true
   :features      {;; Each feature with a dedicated tab
                   ;; FIXME: prevent from removing announcements?
                   :announcements true
                   :bugs          true
                   :patches       true
                   :changes       true
                   :releases      true
                   :requests      true
                   :tops          false
                   :mails         false
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

(def action-words
  {:patches       "PATCH"
   :bugs          "BUG"
   :requests      "HELP"
   :announcements "ANN"
   :changes       "CHANGE"
   :releases      "RELEASE"})

(def action-re
  {:patches       (re-pattern
                   (format "^\\[%s(?: [^\\s]+)?(?: [0-9]+/[0-9]+)?\\].*$" (:patches action-words)))
   :bugs          (re-pattern (format "^\\[%s\\].*$" (:bugs action-words)))
   :requests      (re-pattern (format "^\\[%s\\].*$" (:requests action-words)))
   :announcements (re-pattern (format "^\\[%s\\].*$" (:announcements action-words)))
   :changes       (re-pattern (format "^\\[%s\\s*([^]]+)\\].*$" (:changes action-words)))
   :releases      (re-pattern (format "^\\[%s\\s*([^]]+)\\].*$" (:releases action-words)))})

(def report-strings
  {:applied   "Applied"
   :approved  "Approved"
   :canceled  "Canceled"
   :confirmed "Confirmed"
   :done      "Done"
   :fixed     "Fixed"
   :handled   "Handled"})

(def report-keywords-all
  (let [ks (keys report-strings)]
    (concat ks (map #(keyword (str "un" (name %))) ks))))

(def reports
  {:bugs          #{:confirmed :canceled :fixed}
   :patches       #{:approved :canceled :applied}
   ;; FIXME: Allow to approve requests and announcements
   :requests      #{:handled :canceled :done}
   :announcements #{:canceled}
   :changes       #{:canceled}
   :releases      #{:canceled}})

(def report-types
  [:bug :patch :request :change :announcement :release])

(def admin-report-strings
  {;; Contributors actions
   :notifications        "Notifications"
   :home                 "Home"
   :support              "Support"
   ;; Maintainers actions
   :add-maintainer       "Add maintainer"
   :delete               "Delete"
   :ignore               "Ignore"
   ;; Admin actions
   :add-admin            "Add admin"
   :add-export           "Add export"
   :add-feature          "Add feature"
   :remove-admin         "Remove admin"
   :remove-export        "Remove export"
   :remove-feature       "Remove feature"
   :remove-maintainer    "Remove maintainer"
   :maintenance          "Maintenance"
   :set-theme            "Set theme"
   :undelete             "Undelete"
   :unignore             "Unignore"
   :global-notifications "Global notifications"
   })

;; Admin permissions include maintainer permissions which include
;; contributors ones.
(def permissions
  {:admin       #{:add-admin :remove-admin
                  :add-feature :remove-feature
                  :remove-maintainer :undelete :unignore
                  :add-export :remove-export :set-theme
                  :global-notifications}
   :maintainer  #{:maintenance :add-maintainer :delete :ignore}
   :contributor #{:notifications :home :support}})

;; Set default priority for all reports
(def priority 0)

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
