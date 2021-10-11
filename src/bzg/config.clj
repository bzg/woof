(ns bzg.config)

(def woof
  {;; Configure the mailbox to monitor
   ;; FIXME: Shall we always assume tls connection?
   :inbox-user     (System/getenv "WOOF_INBOX_USER")
   :inbox-server   (System/getenv "WOOF_INBOX_SERVER")
   :inbox-password (System/getenv "WOOF_INBOX_PASSWORD")
   :inbox-folder   (System/getenv "WOOF_INBOX_FOLDER")

   ;; General application options
   :admin                (System/getenv "WOOF_ADMIN")
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

(defn format-email-notification
  [{:keys [notification-type
           ;; op-from op-msgid
           from msgid
           ;; subject references
           action-string status-string]}]
  (str
   (condp = notification-type
     :new
     (format "Thanks for sharing this %s!\n\n" action-string)
     :action-reporter
     (format "Thanks for marking this %s as \"%s\".\n\n"
             action-string status-string)
     :action-op
     (format "%s marked your %s as %s.\n\n"
             from action-string status-string))
   (when-let [link (not-empty (format (:mail-url-format woof) msgid))]
     (format "You can find your email here:\n%s\n\n" link))
   (when-let [contribute-url (not-empty (:contribute-url woof))]
     (format "More on how to contribute to %s:\n%s"
             (:project-name woof) contribute-url))))
