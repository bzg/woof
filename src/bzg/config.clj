(ns bzg.config)

(def defaults
  {:db-dir   ".db"
   :log-file "log.txt"
   :theme    "default"
   :log      [:db :file :mail]

   :watch {:change       {:subject-prefix ["CHANGE"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:closed ["Canceled"]}}
           :release      {:subject-prefix ["RELEASE"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:closed ["Canceled"]}}
           :announcement {:subject-prefix ["ANN"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:closed ["Canceled"]}
                          :display-max    20}
           :bug          {:subject-prefix ["BUG"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Confirmed"]
                                           :owned  ["Handled"]
                                           :closed ["Fixed" "Canceled"]}}
           :patch        {:subject-prefix ["PATCH"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Approved" "Reviewed"]
                                           :owned  ["Handled"]
                                           :closed ["Applied" "Canceled"]}}
           :request      {:subject-prefix ["FP" "FR" "TODO"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Approved"]
                                           :owned  ["Handled"]
                                           :closed ["Done" "Canceled"]}}}

   :admin-report-words
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
    :remove-admin         "Remove admin"
    :remove-maintainer    "Remove maintainer"
    :maintenance          "Maintenance"
    :undelete             "Undelete"
    :unignore             "Unignore"
    :global-notifications "Global notifications"
    }
   
   })
