(ns bzg.config-test
  (:require [bzg.config :as config]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]))

(spec/def ::port string?)
(spec/def ::db-dir string?)
(spec/def ::log-file string?)
(spec/def ::base-url string?)
(spec/def ::admin-address string?)
(spec/def ::admin-username string?)
(spec/def ::inbox-user string?)
(spec/def ::inbox-server string?)
(spec/def ::inbox-password string?)
(spec/def ::inbox-folder string?)

(spec/def ::theme string?)

(spec/def ::mailing-list-address (spec/nilable string?))
(spec/def ::mail-url-format (spec/nilable string?))
(spec/def ::smtp-host (spec/nilable string?))
(spec/def ::smtp-port integer?)
(spec/def ::smtp-use-tls boolean?)
(spec/def ::smtp-login (spec/nilable string?))
(spec/def ::smtp-password (spec/nilable string?))
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

(spec/def ::config
  (spec/keys
   :req-un [::admin-address
            ::inbox-user
            ::inbox-server
            ::inbox-password
            ::smtp-host
            ::smtp-login
            ::smtp-port
            ::smtp-use-tls
            ::smtp-password
            ::title
            ::project-name
            ::project-url]
   :opt-un [::port
            ::db-dir
            ::log-file
            ::base-url
            ::admin-username
            ::inbox-folder
            ::mailing-list-address
            ::mail-url-format
            ::theme
            ::contribute-url
            ::contribute-cta
            ::contribute-cta-email
            ::support-url
            ::support-cta
            ::support-cta-email]))

(deftest configuration
  (testing "Testing configuration"
    (is (spec/valid? ::admin-address (:admin-address config/env)))
    (is (spec/valid? ::inbox-user (:inbox-user config/env)))
    (is (spec/valid? ::inbox-server (:inbox-server config/env)))
    (is (spec/valid? ::inbox-user (:inbox-user config/env)))
    (is (spec/valid? ::inbox-password (:inbox-password config/env)))
    (is (spec/valid? ::inbox-folder (:inbox-folder config/env)))
    (is (spec/valid? ::smtp-host (:smtp-host config/env)))
    (is (spec/valid? ::smtp-login (:smtp-login config/env)))
    (is (spec/valid? ::smtp-port (:smtp-port config/env)))
    (is (spec/valid? ::smtp-use-tls (:smtp-use-tls config/env)))
    (is (spec/valid? ::smtp-password (:smtp-password config/env)))
    (is (spec/valid? ::title (:title config/env)))
    (is (spec/valid? ::project-name (:project-name config/env)))
    (is (spec/valid? ::project-url (:project-url config/env)))
    (is (spec/valid? ::config config/env))))
