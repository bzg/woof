;; Copyright (c) 2022-2023 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns bzg.config)

(def defaults
  { ;; Default relative directory where to store the database
   :db-dir   ".db"
   ;; Default theme for the web interface
   :theme    "bulma"
   ;; UI accepted languages so far: en, fr
   :lang     "en"
   ;; Default log file name
   :log-file "log.txt"
   ;; Where to store logs?
   ;; :file means to store in :log-file
   ;; :db means to store in the database
   ;; :mail means to enable sending emails on log errors
   :log      [:file :mail]

   ;; What reports should Woof watch?
   ;;
   ;; Available: :change :release :announcement :blog :bug :patch :request
   ;;
   ;; Each report entry is a map with
   ;; :subject-prefix : accepted subject prefixes
   ;; :subject-match : a string in the subject to trigger a report
   ;; :doc : the documentation for this report type
   ;; :display-newer-than : Don't display reports if older than (in days)
   ;; :display-max : Don't display more than X reports
   ;; :triggers : a map of possible "triggers", i.e. terms in the body
   ;; of an email that trigger a report update.
   ;;
   ;; :triggers is a map of:
   ;;  :acked : array of terms to "ack" a report
   ;;  :owned : array of terms to "own" (i.e. assign to yourself) a report
   ;; :closed : array of terms to "close" a report (fixed, done, canceled, etc.)
   ;;
   ;; TODO: You can help enhancing tests
   :watch {:change       {:subject-prefix     ["CHANGE"]
                          :subject-match      []
                          :doc                ""
                          :display-newer-than 100
                          :triggers           {:closed ["Canceled"]}}
           :release      {:subject-prefix ["RELEASE" "REL"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:closed ["Canceled"]}}
           :announcement {:subject-prefix ["ANNOUCEMENT" "ANN"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:closed ["Canceled"]}
                          :display-max    20}
           :blog         {:subject-prefix ["BLOG" "TIP"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:closed ["Canceled"]}
                          :display-max    20}
           :bug          {:subject-prefix ["BUG"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Approved" "Confirmed"]
                                           :owned  ["Handled"]
                                           :closed ["Canceled" "Fixed"]}}
           :patch        {:subject-prefix ["PATCH"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Approved" "Reviewed"]
                                           :owned  ["Handled"]
                                           :closed ["Canceled" "Applied"]}}
           :request      {:subject-prefix ["FP" "FR" "RFC" "RFE" "TASK" "POLL"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Approved"]
                                           :owned  ["Handled"]
                                           :closed ["Canceled" "Done" "Closed"]}}}

   ;; A set of priority words that trigger a report update
   ;;
   ;; Note that "Un" "Not " "Non " "Non-" "Not-" are prefixes for
   ;; indicating opposite: Unimportant is the opposite of important.
   ;;
   ;; If you configure this, you need to
   ;; use "Un" "Not " "Non " "Non-" "Not-" as indicating opposite.
   :priority-words-all #{"Important" "Not important" "Unimportant"
                         "Urgent" "Not urgent" "Non urgent" "Non-urgent"}

   ;; Default permissions for admins, maintainers and contributor.
   :permissions
   {:admin       #{:add-admin :remove-admin
                   :add-feature :remove-feature
                   :remove-maintainer :undelete :unignore
                   :global-notifications}
    :maintainer  #{:maintenance :add-maintainer :delete :ignore
                   :change :release :announcement}
    :contributor #{:notifications :home :support
                   :bug :patch :request :blog}}

   ;; Configuration triggers
   :admin-report-triggers
   {:notifications        "Notifications"         ; Receive email notifications?
    :home                 "Home"                  ; Set your homepage
    :support              "Support"               ; Set your support page
    :add-maintainer       "Add maintainer"        ; Add maintainer bzg@woof.io
    :delete               "Delete"                ; Delete past reports from a user
    :undelete             "Undelete"              ; Undelete past reports from a user
    :ignore               "Ignore"                ; Ignore future reports from a user
    :unignore             "Unignore"              ; Don't ignore reports from a user
    :add-admin            "Add admin"             ; Add admin bzg@woof.io
    :remove-admin         "Remove admin"          ; Remove admin bzg@woof.io
    :remove-maintainer    "Remove maintainer"     ; Remove maintainer bzg@woof.io
    :maintenance          "Maintenance"           ; Put the application under maintenance
    :global-notifications "Global notifications"  ; Turn notifications globally on/off
    }
   })
