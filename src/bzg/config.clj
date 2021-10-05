(ns bzg.config)

(def woof
  {:user              (System/getenv "WOOF_MAIL_USER")
   :server            (System/getenv "WOOF_MAIL_SERVER")
   :password          (System/getenv "WOOF_MAIL_PASSWORD")
   :mailing-list      (System/getenv "WOOF_MAILING_LIST")
   :admin             (System/getenv "WOOF_ADMIN")
   :mail-url-format   (System/getenv "WOOF_MAIL_URL_FORMAT")
   :folder            (System/getenv "WOOF_MAIL_FOLDER")
   :project-name      (System/getenv "WOOF_PROJECT_NAME")
   :project-url       (System/getenv "WOOF_PROJECT_URL")
   :title             (System/getenv "WOOF_TITLE")
   :feed-title        (System/getenv "WOOF_FEED_TITLE")
   :feed-description  (System/getenv "WOOF_FEED_DESCRIPTION")
   :commit-url-format (System/getenv "WOOF_COMMIT_URL_FORMAT")
   :smtp-host         (System/getenv "WOOF_SMTP_HOST")
   :smtp-login        (System/getenv "WOOF_SMTP_LOGIN")
   :smtp-password     (System/getenv "WOOF_SMTP_PASSWORD")
   :contributing-url  (System/getenv "WOOF_CONTRIBUTING_URL")
   :contributing-cta  (System/getenv "WOOF_CONTRIBUTING_CTA")
   :log-file          (or (System/getenv "WOOF_LOG_FILE") "logs.txt")
   :port              (or (System/getenv "WOOF_PORT") "3000")
   :base-url          (or (System/getenv "WOOF_BASE_URL") "https://localhost:3000")})

(defn format-mail [s t]
  (str (format (str "%s\n\n"
                    (when-not (= t :none)
                      (format "You can find it here:\n%s%s"
                              (:base-url woof)
                              (condp = t
                                :help    "#help"
                                :change  "#changes"
                                :bug     "#bugs"
                                :patch   "#patches"
                                :release "#releases"))))
               s)
       (when (= t :patch)
         (str "\n\nFor details on how to submit a patch, read this page:\n"
              (:contributing-url woof)))))

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

(def actions-regexps
  {:confirmed #"(?i)^confirm(ed)?|t(rue)?"
   :closed    #"(?i)^(cancell?(ed)?|done|closed?|fix(ed)?|nil|applied)"})
