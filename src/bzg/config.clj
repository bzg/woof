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

(defn format-mail [s t]
  (str (format (str "%s\n\n"
                    (when-not (= t :none)
                      (format "You can find it here:\n%s%s"
                              (:base-url woof)
                              (condp = t
                                :help    "/help"
                                :change  "/changes"
                                :bug     "/bugs"
                                :patch   "/patches"
                                :release "/"))))
               s)
       (when (= t :patch)
         (str "\n\nFor details on how to submit a patch, read this page:\n"
              (:contribute-url woof)))))

;; FIXME: Allow configuration?
(def mails
  {:email-link "Follow the discussion on the mailing list:"
   :add
   {:bug     (format-mail "Thanks for reporting this bug!" :bug)
    :patch   (format-mail "Thanks for submitting this patch!" :patch)
    :help    (format-mail "Thanks for adding this call for help!" :help)
    :change  (format-mail "Thanks for announcing this new change!" :change)
    :release (format-mail "Thanks for announcing this new release!" :release)}
   :fix
   {:bug    (format-mail "Thanks for closing this bug report." :none)
    :patch  (format-mail "Thanks for handling this patch." :none)
    :help   (format-mail "Thanks for closing this call for help." :none)
    :change (format-mail "Thanks for cancelling this change." :none)}})

;; FIXME: be more strict and allow only t|nil?
(def actions-regexps
  {:confirmed #"(?i)^confirm(ed)?|t(rue)?"
   :closed    #"(?i)^(cancell?(ed)?|done|closed?|fix(ed)?|nil|applied)"})
