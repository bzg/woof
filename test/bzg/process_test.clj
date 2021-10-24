(ns bzg.process-test
  (:require [bzg.core :as core]
            [bzg.config :as config]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [clojure-mail.core :as mail]
            [clojure.java.shell :as sh]))

;; Alter db vars
(alter-var-root #'config/env
                #(assoc %
                        :log-file "test-log.txt"
                        :mailing-list-address "test@woof.io"))

(alter-var-root #'core/conn
                (constantly (d/get-conn "db-test" core/schema)))

(alter-var-root #'core/db
                (constantly (d/db core/conn)))

;; Initialize root admin
(d/transact! core/conn [(merge {:defaults "init"} config/defaults)])
(core/update-person! {:email "admin@woof.io" :role :admin} {:root true})

;; Define test emails
(defn- read-mail-resource [f] (mail/file->message (str "test-mails/" f)))
(def mail-bug1 (read-mail-resource "mail-bug1"))
(def mail-patch1 (read-mail-resource "mail-patch1"))

;; Run tests
(deftest processes
  (testing "Adding the root admin"
    (is (not-empty (core/get-admins))))
  (testing "Adding a bug"
    (do (core/read-and-process-mail (list mail-bug1))
        (is (not-empty (core/get-unconfirmed-bugs)))))
  (testing "Adding a patch"
    (do (core/read-and-process-mail (list mail-patch1))
        (is (= 1 (count (core/get-unapproved-patches)))))))

;; Clean up behind tests
(sh/sh "rm" "-fr" "db-test")
