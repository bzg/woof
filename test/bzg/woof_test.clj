(ns bzg.woof-test
  (:require [bzg.core :as core]
            [bzg.config :as config]
            [clojure.spec.alpha :as spec]
            [clojure.test :refer :all]
            [clojure.string :as s]))

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

;; Confirm a bug
(def msg1 {:id        "id1"
           :subject   "Subject message id1"
           :from      '({:address (:user config/config)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/config)}
                       {"X-Woof-Bug" "confirmed"}]})

(def msg2 {:id        "id2"
           :subject   "Subject message id2"
           :from      '({:address (:user config/config)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/config)}
                       {"References" "id1"}
                       {"X-Woof-Bug" "fixed"}]})

(def msg3 {:id        "id3"
           :subject   "Release 8.2"
           :from      '({:address (:user config/config)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/config)}
                       {"X-Woof-Release" "8.2"}]})

(def msg4 {:id        "id4"
           :subject   "Release 8.3"
           :from      '({:address (:user config/config)})
           :date-sent #inst "2020-05-27T00:13:11.037044Z"
           :headers   [{"X-Original-To" (:mailing-list config/config)}
                       {"X-Woof-Release" "8.3"}]})

;; (process-incoming-message msg1)
;; (process-incoming-message msg2)
;; (process-incoming-message msg3)
;; @db
;; (get-unfixed-bugs @db)
;; (get-releases @db)
;; (get-unfixed-bugs @db)
