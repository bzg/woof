;; Copyright (c) 2022 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns bzg.config-test
  (:require [aero.core :refer (read-config)]
            [bzg.config :as config]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]))

(def config (merge config/defaults (read-config "config.edn")))

(spec/def ::smtp-host (spec/nilable string?))
(spec/def ::smtp-port integer?)
(spec/def ::smtp-use-tls string?)
(spec/def ::smtp-login (spec/nilable string?))
(spec/def ::smtp-password (spec/nilable string?))

(spec/def ::inbox-user string?)
(spec/def ::inbox-server string?)
(spec/def ::inbox-password string?)
(spec/def ::inbox-folder string?)

(spec/def ::baseurl string?)
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
(spec/def ::archived-message-raw-format
  (spec/and string? #(re-matches #".*%s.*" %)))
(spec/def ::archived-list-message-raw-format
  (spec/and string? #(re-matches #".*%s.*%s.*" %)))

(spec/def ::hidden boolean?)
(spec/def ::doc string?)

(spec/def ::change map?)
(spec/def ::release map?)
(spec/def ::announcement map?)
(spec/def ::blog map?)
(spec/def ::bug map?)
(spec/def ::patch map?)
(spec/def ::request map?)
(spec/def ::index map?)
(spec/def ::overview boolean?)
(spec/def ::watch
  (spec/keys
   :opt-un [::change
            ::release
            ::announcement
            ::blog
            ::bug
            ::patch
            ::request]))

(spec/def ::pages
  (spec/keys
   :opt-un [::index
            ::overview
            ::change
            ::release
            ::announcement
            ::blog
            ::bug
            ::patch
            ::request]))

(spec/def ::display-max integer?)
(spec/def ::display-newer-than integer?)
(spec/def ::rss boolean?)
(spec/def ::json boolean?)
(spec/def ::org boolean?)
(spec/def ::md boolean?)
(spec/def ::data-formats
  (spec/keys :opt-un [::rss ::json ::org ::md]))

(spec/def ::maintenance boolean?)
(spec/def ::notifications boolean?)
(spec/def ::defaults
  (spec/keys :opt-un [::maintenance ::notifications]))

(spec/def ::address string?)
(spec/def ::slug string?)

(spec/def ::source-id string?)
(spec/def ::list-config
  (spec/keys
   :req-un [::slug]
   :opt-un [::archived-message-format
            ::archived-message-format-raw
            ::ui
            ::doc
            ::hidden
            ::watch]))

(spec/def ::sources
  (spec/map-of ::source-id ::list-config))

(spec/def ::title (spec/nilable string?))
(spec/def ::project-name (spec/nilable string?))
(spec/def ::project-url (spec/nilable string?))
(spec/def ::contribute-url (spec/nilable string?))
(spec/def ::support-url (spec/nilable string?))
(spec/def ::feed-title (spec/nilable string?))
(spec/def ::feed-description (spec/nilable string?))
(spec/def ::enabled boolean?)

(spec/def ::ui
  (spec/keys
   :opt-un [::enabled
            ::title
            ::project-name
            ::project-url
            ::contribute-url
            ::support-url
            ::data-formats
            ::pages]))

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
   :opt-un [::archived-list-message-raw-format
            ::archived-list-message-format
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
    (is (spec/valid? ::pages (:pages (:ui config))))
    (is (spec/valid? ::config config))))
