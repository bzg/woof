(ns bzg.config)

(def defaults
  {:db-dir   ".db"
   :log-file "log.txt"
   :theme    "default"
   :lang     "en" ;; Accepted: en, fr
   ;; FIXME: Check why mail here?
   :log      [:db :file :mail]

   ;; FIXME: Document for easier configuration
   :watch {:change       {:subject-prefix     ["CHANGE"]
                          :subject-match      []
                          :doc                ""
                          :display-newer-than 100
                          :triggers           {:closed ["Canceled"]}}
           :release      {:subject-prefix ["RELEASE"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:closed ["Canceled"]}}
           :announcement {:subject-prefix ["ANN"]
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
                          :triggers       {:acked  ["Confirmed"]
                                           :owned  ["Handled"]
                                           :closed ["Fixed" "Canceled"]}}
           :patch        {:subject-prefix ["PATCH"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Approved" "Reviewed"]
                                           :owned  ["Handled"]
                                           :closed ["Applied" "Canceled"]}}
           :request      {:subject-prefix ["FP" "FR" "RFC" "RFE" "TODO" "POLL"]
                          :subject-match  []
                          :doc            ""
                          :triggers       {:acked  ["Approved"]
                                           :owned  ["Handled"]
                                           :closed ["Done" "Canceled"]}}}

   ;; FIXME: Document what is accepted here (Un/Not/Non)
   :priority-words-all #{"Important" "Not important" "Unimportant"
                         "Urgent" "Not urgent" "Non urgent" "Non-urgent"}

   :permissions
   {:admin       #{:add-admin :remove-admin
                   :add-feature :remove-feature
                   :remove-maintainer :undelete :unignore
                   :global-notifications}
    :maintainer  #{:maintenance :add-maintainer :delete :ignore
                   :change :release :announcement}
    :contributor #{:notifications :home :support
                   :bug :patch :request :blog}}

   ;; FIXME: Document each admin word
   :admin-report-words
   {:notifications        "Notifications"
    :home                 "Home"
    :support              "Support"
    :add-maintainer       "Add maintainer"
    :delete               "Delete"
    :ignore               "Ignore"
    :add-admin            "Add admin"
    :remove-admin         "Remove admin"
    :remove-maintainer    "Remove maintainer"
    :maintenance          "Maintenance"
    :undelete             "Undelete"
    :unignore             "Unignore"
    :global-notifications "Global notifications"
    }
   })
