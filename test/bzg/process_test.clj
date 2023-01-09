;; Copyright (c) 2022-2023 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns bzg.process-test
  (:require [bzg.core :as core]
            [bzg.db :as db]
            [bzg.fetch :as fetch]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [clojure-mail.core :as mail]
            [clojure.java.shell :as sh]))

;; ;; Alter db vars
(alter-var-root #'db/config
                #(assoc %
                        :inbox-user "woof@woof.io"
                        :log-file "test-log.txt"
                        :sources {"list@woof.io" {:slug "list@woof.io"}}))

(alter-var-root #'db/conn
                (constantly (d/get-conn "db-test" db/schema)))

(alter-var-root #'db/db
                (constantly (d/db db/conn)))

;; Initialize defaults
(core/set-defaults)

;; Initialize root admin
(d/transact! db/conn [(merge {:defaults "init"} (:defaults db/config))])
(core/update-person! {:email "admin@woof.io" :role :admin} {:root true})

;; Define test emails
(defn- read-mail [f] (mail/file->message (str "test-mails/" f)))
(def bug1 (read-mail "bug1"))
(def bug1-confirmed (read-mail "bug1-confirmed"))
(def bug1-urgent (read-mail "bug1-urgent"))
(def bug1-noturgent (read-mail "bug1-noturgent"))
(def bug1-fixed (read-mail "bug1-fixed"))
(def bug2 (read-mail "bug2"))
(def bug2-canceled (read-mail "bug2-canceled"))
(def bug3 (read-mail "bug3"))
(def bug3-canceled-via-inbox (read-mail "bug3-canceled-via-inbox"))
(def patch1 (read-mail "patch1"))
(def patch1-approved (read-mail "patch1-approved"))
(def request1 (read-mail "request1"))
(def change1 (read-mail "change1"))
(def change1-canceled (read-mail "change1-canceled"))
(def change1-uncanceled (read-mail "change1-uncanceled"))
(def release1 (read-mail "release1"))
(def patch2-bug1 (read-mail "patch2-bug1"))

(defn- count-related [email]
  (count (map first (d/q `[:find ?r :where
                           [?e :message-id ~email]
                           [?e :related-refs ?r]] db/db))))

;; Run tests
(deftest processes
  (testing "Adding bug1"
    (do (core/read-and-process-mail (list bug1))
        (is (= 1 (count-related "bug1@woof.io")))
        (is (= 1 (count (fetch/unconfirmed-bugs "list@woof.io"))))
        (is (= 1 (count (fetch/unclosed-bugs "list@woof.io"))))))
  (testing "Adding bug2"
    (do (core/read-and-process-mail (list bug2))
        (is (= 2 (count (fetch/unconfirmed-bugs "list@woof.io"))))
        (is (= 2 (count (fetch/unclosed-bugs "list@woof.io"))))))
  (testing "Confirming bug1"
    (do (core/read-and-process-mail (list bug1-confirmed))
        ;; Confirming does not update related references
        (is (= 1 (count-related "bug1@woof.io")))
        (is (= 1 (count (fetch/unconfirmed-bugs "list@woof.io"))))
        (is (= 2 (count (fetch/unclosed-bugs "list@woof.io"))))))
  (testing "Declaring bug1 as urgent"
    (do (core/read-and-process-mail (list bug1-urgent))
        (is (= 1 (count (fetch/urgent-bugs "list@woof.io"))))))
  (testing "Declaring bug1 as not urgent"
    (do (core/read-and-process-mail (list bug1-noturgent))
        (is (= 0 (count (fetch/urgent-bugs "list@woof.io"))))))
  (testing "Canceling bug2"
    (do (core/read-and-process-mail (list bug2-canceled))
        (is (= 0 (count (fetch/unconfirmed-bugs "list@woof.io"))))
        (is (= 1 (count (fetch/unclosed-bugs "list@woof.io"))))))
  (testing "Effective bug reports"
    (is (= 0 (count (fetch/effective-bugs "list@woof.io")))))
  (testing "Adding a patch against bug1"
    (do (core/read-and-process-mail (list patch2-bug1))
        ;; Creating a patch while replying to a bug updates related refs
        (is (= 2 (count-related "bug1@woof.io")))
        (is (= 1 (count (fetch/unapproved-patches "list@woof.io"))))))
  (testing "Fixing bug1"
    (do (core/read-and-process-mail (list bug1-fixed))
        (is (= 0 (count (fetch/unclosed-bugs "list@woof.io"))))
        (is (= 1 (count (fetch/effective-bugs "list@woof.io"))))))
  (testing "Adding a patch"
    (do (core/read-and-process-mail (list patch1))
        (is (= 2 (count (fetch/unapproved-patches "list@woof.io"))))))
  (testing "Approving this patch"
    (do (core/read-and-process-mail (list patch1-approved))
        (is (= 1 (count (fetch/approved-patches "list@woof.io"))))
        (is (= 1 (count (fetch/unapproved-patches "list@woof.io"))))))
  (testing "Adding a request"
    (do (core/read-and-process-mail (list request1))
        (is (= 1 (count (fetch/requests "list@woof.io"))))))
  (testing "Adding a change"
    (do (core/read-and-process-mail (list change1))
        (is (= 1 (count (fetch/unreleased-changes "list@woof.io"))))))
  (testing "Canceling a change"
    (do (core/read-and-process-mail (list change1-canceled))
        (is (= 0 (count (fetch/unreleased-changes "list@woof.io"))))))
  (testing "Uncanceling a change"
    (do (core/read-and-process-mail (list change1-uncanceled))
        (is (= 1 (count (fetch/unreleased-changes "list@woof.io"))))))
  (testing "Adding a release"
    (do (core/read-and-process-mail (list release1))
        (is (= 1 (count (fetch/released-versions "list@woof.io"))))
        (is (= 1 (count (fetch/latest-released-changes "list@woof.io"))))))
  (testing "Adding bug3"
    (do (core/read-and-process-mail (list bug3))
        (is (= 1 (count (fetch/unconfirmed-bugs "list@woof.io"))))
        (is (= 1 (count (fetch/unclosed-bugs "list@woof.io"))))))
  (testing "Canceling bug3 via inbox"
    (do (core/read-and-process-mail (list bug3-canceled-via-inbox))
        (is (= 0 (count (fetch/unclosed-bugs "list@woof.io")))))))

;; Clean up behind tests
(sh/sh "rm" "-fr" "db-test")
