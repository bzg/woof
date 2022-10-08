(ns bzg.config-test
  (:require [aero.core :refer (read-config)]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]))

(def config (read-config "config.edn"))

(spec/def ::smtp-host (spec/nilable string?))
(spec/def ::smtp-port integer?)
(spec/def ::smtp-use-tls string?)
(spec/def ::smtp-login (spec/nilable string?))
(spec/def ::smtp-password (spec/nilable string?))

(spec/def ::inbox-user string?)
(spec/def ::inbox-server string?)
(spec/def ::inbox-password string?)
(spec/def ::inbox-folder string?)

(spec/def ::hostname string?)
(spec/def ::port integer?)
(spec/def ::db-dir string?)
(spec/def ::log-file string?)
(spec/def ::theme string?)

(spec/def ::log vector?)

(spec/def ::admin-address string?)
(spec/def ::admin-username string?)

(spec/def ::reports string?)
(spec/def ::report-types string?)

(spec/def ::admin-report-strings string?)
(spec/def ::permissions string?)
(spec/def ::action-words string?)
(spec/def ::report-strings string?)

(spec/def ::mail-url-format (spec/nilable string?))

(spec/def ::maintenance boolean?)
(spec/def ::notifications boolean?)
(spec/def ::features map?) ;; TODO
(spec/def ::display-max map?) ;; TODO
(spec/def ::export-formats map?) ;; TODO

(spec/def ::defaults
  (spec/keys
   :opt-un [::maintenance
            ::notifications
            ::features
            ::display-max
            ::export-formats]))

(spec/def ::address string?)
(spec/def ::slug string?)

;; FIXME: Don't use a vector for mailing-lists
(spec/def ::mailing-lists vector?
  ;; (spec/keys
  ;;  :req-un [::address]
  ;;  :opt-un [::slug])
  )

(spec/def ::title (spec/nilable string?))
(spec/def ::project-name (spec/nilable string?))
(spec/def ::project-url (spec/nilable string?))
(spec/def ::contribute-url (spec/nilable string?))
(spec/def ::contribute-cta (spec/nilable string?))
(spec/def ::contribute-cta-email (spec/nilable string?))
(spec/def ::support-url (spec/nilable string?))
(spec/def ::support-cta (spec/nilable string?))
(spec/def ::support-cta-email (spec/nilable string?))
(spec/def ::feed-title (spec/nilable string?))
(spec/def ::feed-description (spec/nilable string?))

(spec/def ::ui
  (spec/keys
   :req-un [::title
            ::project-name
            ::project-url]
   :opt-un [::contribute-url
            ::contribute-cta
            ::contribute-cta-email
            ::support-url
            ::support-cta
            ::support-cta-email]))

(spec/def ::config
  (spec/keys
   :req-un [::inbox-user
            ::inbox-server
            ::inbox-password
            ::inbox-folder
            ::smtp-host
            ::smtp-login
            ::smtp-port
            ::smtp-use-tls
            ::smtp-password
            ::admin-address
            ::admin-username
            ::port
            ::ui
            ::db-dir
            ::log-file
            ::defaults
            ::mail-url-format
            ::mailing-lists]))

(deftest configuration
  (testing "Testing configuration"
    (is (spec/valid? ::admin-address (:admin-address config)))
    (is (spec/valid? ::inbox-user (:inbox-user config)))
    (is (spec/valid? ::inbox-server (:inbox-server config)))
    (is (spec/valid? ::inbox-user (:inbox-user config)))
    (is (spec/valid? ::inbox-password (:inbox-password config)))
    (is (spec/valid? ::inbox-folder (:inbox-folder config)))
    (is (spec/valid? ::smtp-host (:smtp-host config)))
    (is (spec/valid? ::smtp-login (:smtp-login config)))
    (is (spec/valid? ::smtp-port (:smtp-port config)))
    (is (spec/valid? ::smtp-use-tls (:smtp-use-tls config)))
    (is (spec/valid? ::smtp-password (:smtp-password config)))
    (is (spec/valid? ::title (:ui (:title config))))
    (is (spec/valid? ::project-name (:ui (:project-name config))))
    (is (spec/valid? ::project-url (:ui (:project-url config))))
    (is (spec/valid? ::config config))))
