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
(spec/def ::admin-report-words string?)

(spec/def ::archived-message-format
  (spec/and string? #(re-matches #".*%s.*" %)))

(spec/def ::archived-list-message-format
  (spec/and string? #(re-matches #".*%s.*%s.*" %)))

(spec/def ::maintenance boolean?)
(spec/def ::notifications boolean?)
(spec/def ::watch map?) ;; TODO
(spec/def ::show map?) ;; TODO
(spec/def ::display-max map?) ;; TODO
(spec/def ::data-formats map?) ;; TODO

(spec/def ::defaults
  (spec/keys
   :opt-un [::maintenance
            ::notifications]))

(spec/def ::address string?)
(spec/def ::slug string?)

(spec/def ::list-id string?)
(spec/def ::list-config
  (spec/keys
   :req-un [::slug]
   :opt-un [::archived-message-format
            ::ui
            ::watch]))

(spec/def ::sources
  (spec/map-of ::list-id ::list-config))

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
            ::project-name]
   :opt-un [::project-url
            ::contribute-url
            ::contribute-cta
            ::contribute-cta-email
            ::support-url
            ::support-cta
            ::support-cta-email
            ::show]))

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
            ::watch
            ::sources]
   :opt-un [::archived-list-message-format
            ::defaults
            ::data-formats]))

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
    (is (spec/valid? ::sources (:sources config)))
    (is (spec/valid? ::watch (:watch config)))
    (is (spec/valid? ::show (:show (:ui config))))
    (is (spec/valid? ::config config))))
