(ns bzg.config)

(def woof
  {:user              (System/getenv "WOOF_MAIL_USER")
   :server            (System/getenv "WOOF_MAIL_SERVER")
   :password          (System/getenv "WOOF_MAIL_PASSWORD")
   :mailing-list      (System/getenv "WOOF_MAILING_LIST")
   :release-manager   (System/getenv "WOOF_RELEASE_MANAGER")
   :mail-url-format   (System/getenv "WOOF_MAIL_URL_FORMAT")
   :folder            (System/getenv "WOOF_MAIL_FOLDER")
   :project-name      (System/getenv "WOOF_PROJECT_NAME")
   :project-url       (System/getenv "WOOF_PROJECT_URL")
   :title             (System/getenv "WOOF_TITLE")
   :feed-title        (System/getenv "WOOF_FEED_TITLE")
   :feed-description  (System/getenv "WOOF_FEED_DESCRIPTION")
   :commit-url-format (System/getenv "WOOF_COMMIT_URL_FORMAT")
   :port              (or (System/getenv "WOOF_PORT") "3000")
   :base-url          (or (System/getenv "WOOF_BASE_URL") "https://localhost:3000")})

(def actions-regexps
  {:confirmed #"(?i)^confirm(ed)?|t(rue)?"
   :closed    #"(?i)^(cancel(ed)?|done|closed?|fix(ed)?|nil)"})
