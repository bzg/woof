(ns bzg.config)

(def mail-archive-formatting-string
  "https://www.mail-archive.com/search?l=emacs-orgmode@gnu.org&q=%s")

(def config
  {:user              (System/getenv "WOOF_MAIL_USER")
   :server            (System/getenv "WOOF_MAIL_SERVER")
   :password          (System/getenv "WOOF_MAIL_PASSWORD")
   :mailing-list      (System/getenv "WOOF_MAILING_LIST")
   :release-manager   (System/getenv "WOOF_RELEASE_MANAGER")
   :main-website-name "Org-mode"
   :main-website-url  "https://orgmode.org"
   :woof-page-title   "Org-mode updates"
   :woof-base-url     "https://woof.orgmode.org"
   :feed-title        "Org-mode updates"
   :feed-description  "Org-mode updates"
   :folder            "inbox"})
