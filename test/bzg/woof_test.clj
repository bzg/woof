(ns bzg.woof-test
  (:require [bzg.core :as core]
            [bzg.config :as config]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer :all]
            [clojure.string :as s]
            [clojure.java.shell :as sh]))

(spec/def ::user             string?)
(spec/def ::server           string?)
(spec/def ::password         string?)
(spec/def ::mailing-list     string?)
(spec/def ::release-manager  string?)
(spec/def ::mail-url-format  string?)
(spec/def ::folder           string?)
(spec/def ::project-name     string?)
(spec/def ::project-url      string?)
(spec/def ::title            string?)
(spec/def ::base-url         string?)
(spec/def ::feed-title       string?)
(spec/def ::feed-description string?)

(spec/def ::config
  (spec/keys :req-un [::user
                      ::server
                      ::password
                      ::mailing-list
                      ::mail-url-format
                      ::release-manager
                      ::folder
                      ::project-url
                      ::project-name
                      ::title
                      ::base-url
                      ::feed-title
                      ::feed-description]))

(deftest configuration
  (testing "Testing configuration"
    (is (spec/valid? ::config config/config))))

(def test-data
  {:msg1 {:id        "id1"
          :subject   "Subject message id1"
          :from      (list {:address (:user config/config)})
          :date-sent #inst "2020-05-27T00:13:11.037044Z"
          :headers   [{"X-Original-To" (:mailing-list config/config)}
                      {"X-Woof-Bug" "confirmed"}]}
   :msg2 {:id        "id2"
          :subject   "Subject message id2"
          :from      (list {:address (:user config/config)})
          :date-sent #inst "2020-05-27T00:13:11.037044Z"
          :headers   [{"X-Original-To" (:mailing-list config/config)}
                      {"References" "id1"}
                      {"X-Woof-Bug" "fixed"}]}
   :msg3 {:id        "id3"
          :subject   "Incompatible change for release 8.3"
          :from      (list {:address (:user config/config)})
          :date-sent #inst "2020-05-27T00:13:11.037044Z"
          :headers   [{"X-Original-To" (:mailing-list config/config)}
                      {"X-Woof-Change" "commithash 8.3"}]}
   :msg4 {:id        "id4"
          :subject   "Release 8.3"
          :from      (list {:address (:release-manager config/config)})
          :date-sent #inst "2020-05-27T00:13:11.037044Z"
          :headers   [{"X-Original-To" (:mailing-list config/config)}
                      {"X-Woof-Release" "8.3"}]}})

(deftest message-processing
  (binding [core/db-file-name "db-test.edn"]
    (testing "Add a bug"
      (core/process-incoming-message (:msg1 test-data))
      (is (= 1 (count @core/db)))
      (is (not-empty (get @core/db "id1")))
      (reset! core/db {}))
    (testing "Add a bug and fix it"
      (core/process-incoming-message (:msg1 test-data))
      (core/process-incoming-message (:msg2 test-data))
      (is (= 1 (count @core/db)))
      (is (= 0 (count (core/get-unfixed-bugs @core/db))))
      (reset! core/db {}))
    (testing "Add a release"
      (core/process-incoming-message (:msg4 test-data))
      (is (= 1 (count (core/get-releases @core/db))))
      (reset! core/db {}))
    (testing "Add a change"
      (core/process-incoming-message (:msg3 test-data))
      (is (= 1 (count (core/get-unreleased-changes @core/db))))
      (reset! core/db {}))
    (testing "Add a release wrt to a change"
      (core/process-incoming-message (:msg3 test-data))
      (is (= 1 (count (core/get-unreleased-changes @core/db))))
      (core/process-incoming-message (:msg4 test-data))
      (is (= 0 (count (core/get-unreleased-changes @core/db))))
      (reset! core/db {}))
    (sh/sh "rm" "db-test.edn")))
