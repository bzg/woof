(ns bzg.process-test
  (:require [bzg.core :as core]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [clojure-mail.core :as mail]
            [clojure.java.shell :as sh]))

;; ;; Alter db vars
(alter-var-root #'core/config
                #(assoc %
                        :log-file "test-log.txt"
                        :mailing-lists {"test@list.io" {:slug "list"}}))

(alter-var-root #'core/conn
                (constantly (d/get-conn "db-test" core/schema)))

(alter-var-root #'core/db
                (constantly (d/db core/conn)))

;; Initialize defaults
(core/set-defaults)

;; Initialize root admin
(d/transact! core/conn [(merge {:defaults "init"} (:defaults core/config))])
(core/update-person! {:email "admin@woof.io" :role :admin} {:root true})

;; Define test emails
(defn- read-mail [f] (mail/file->message (str "test-mails/" f)))
(def bug1 (read-mail "bug1"))
(def bug1-confirmed (read-mail "bug1-confirmed"))
(def patch1 (read-mail "patch1"))
(def patch1-approved (read-mail "patch1-approved"))
(def change1 (read-mail "change1"))
(def release1 (read-mail "release1"))

;; Run tests
(deftest processes
  (testing "Adding the root admin"
    (is (not-empty (core/get-admins))))
  (testing "Adding bug1"
    (do (core/read-and-process-mail (list bug1))
        (is (= 1 (count (core/get-unconfirmed-bugs "test@list.io"))))))
  (testing "Confirming bug1"
    (do (core/read-and-process-mail (list bug1-confirmed))
        (is (empty? (core/get-unconfirmed-bugs "test@list.io")))
        (is (= 1 (count (core/get-confirmed-bugs "test@list.io"))))))
  (testing "Adding a patch"
    (do (core/read-and-process-mail (list patch1))
        (is (= 1 (count (core/get-unapproved-patches "test@list.io"))))))
  (testing "Approving a patch"
    (do (core/read-and-process-mail (list patch1-approved))
        (is (= 1 (count (core/get-approved-patches "test@list.io"))))
        (is (= 0 (count (core/get-unapproved-patches "test@list.io"))))))
  (testing "Adding a change"
    (do (core/read-and-process-mail (list change1))
        (is (= 1 (count (core/get-unreleased-changes "test@list.io"))))))
  (testing "Adding a release"
    (do (core/read-and-process-mail (list release1))
        (is (= 1 (count (core/get-released-versions "test@list.io"))))
        (is (= 1 (count (core/get-latest-released-changes "test@list.io")))))))

;; Clean up behind tests
(sh/sh "rm" "-fr" "db-test")

