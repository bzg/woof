(ns bzg.data
  (:require [bzg.core :as core]
            [bzg.config :as config]
            [clojure.string :as string]))

(defn- get-resources [what]
  (condp = what
    :confirmed-bugs     (core/get-confirmed-bugs)
    :unconfirmed-bugs   (core/get-unconfirmed-bugs)
    :bugs               (core/get-unfixed-bugs)
    :unhandled-requests (core/get-unhandled-requests)
    :handled-requests   (core/get-handled-requests)
    :requests           (core/get-undone-requests)
    :unapproved-patches (core/get-unapproved-patches)
    :approved-patches   (core/get-approved-patches)
    :patches            (core/get-unapplied-patches)
    :mails              (core/get-mails)
    :changes            (core/get-upcoming-changes)
    :released-changes   (core/get-latest-released-changes)
    :announcements      (core/get-announcements)
    :releases           (core/get-releases)
    :updates            (core/get-updates)))

(defn- get-data [what]
  {:status 200
   :body   (get-resources what)})

(defn- get-org [what]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body
   (string/join
    "\n"
    (map #(format " - [[%s][%s: %s]] (%s)"
                  (if-let [fmt (:mail-url-format config/env)]
                    (format fmt (:message-id %))
                    (:message-id %))
                  (:username %)
                  (string/replace (:subject %) #"^\[[^]]+\] " "")
                  (:date %)) 
         (get-resources what)))})

(defn- get-md [what]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body
   (string/join
    "\n"
    (map #(format " - [%s: %s](%s \"%s\")"
                  (:username %)
                  (string/replace (:subject %) #"^\[[^]]+\] " "")
                  (if-let [fmt (:mail-url-format config/env)]
                    (format fmt (:message-id %))
                    (:message-id %))
                  (:date %))
         (get-resources what)))})

(defn get-data-bugs [_] (get-data :bugs))
(defn get-data-confirmed-bugs [_] (get-data :confirmed-bugs))
(defn get-data-unconfirmed-bugs [_] (get-data :unconfirmed-bugs))
(defn get-data-patches [_] (get-data :patches))
(defn get-data-approved-patches [_] (get-data :approved-patches))
(defn get-data-unapproved-patches [_] (get-data :unapproved-patches))
(defn get-data-requests [_] (get-data :requests))
(defn get-data-handled-requests [_] (get-data :handled-requests))
(defn get-data-unhandled-requests [_] (get-data :unhandled-requests))
(defn get-data-updates [_] (get-data :updates))
(defn get-data-mails [_] (get-data :mails))
(defn get-data-releases [_] (get-data :releases))
(defn get-data-changes [_] (get-data :changes))
(defn get-data-released-changes [_] (get-data :released-changes))
(defn get-data-announcements [_] (get-data :announcements))

(defn get-org-bugs [_] (get-org :bugs))
(defn get-org-confirmed-bugs [_] (get-org :confirmed-bugs))
(defn get-org-unconfirmed-bugs [_] (get-org :unconfirmed-bugs))
(defn get-org-patches [_] (get-org :patches))
(defn get-org-approved-patches [_] (get-org :approved-patches))
(defn get-org-unapproved-patches [_] (get-org :unapproved-patches))
(defn get-org-requests [_] (get-org :requests))
(defn get-org-handled-requests [_] (get-org :handled-requests))
(defn get-org-unhandled-requests [_] (get-org :unhandled-requests))
(defn get-org-updates [_] (get-org :updates))
(defn get-org-mails [_] (get-org :mails))
(defn get-org-releases [_] (get-org :releases))
(defn get-org-changes [_] (get-org :changes))
(defn get-org-released-changes [_] (get-org :released-changes))
(defn get-org-announcements [_] (get-org :announcements))

(defn get-md-bugs [_] (get-md :bugs))
(defn get-md-confirmed-bugs [_] (get-md :confirmed-bugs))
(defn get-md-unconfirmed-bugs [_] (get-md :unconfirmed-bugs))
(defn get-md-patches [_] (get-md :patches))
(defn get-md-approved-patches [_] (get-md :approved-patches))
(defn get-md-unapproved-patches [_] (get-md :unapproved-patches))
(defn get-md-requests [_] (get-md :requests))
(defn get-md-handled-requests [_] (get-md :handled-requests))
(defn get-md-unhandled-requests [_] (get-md :unhandled-requests))
(defn get-md-updates [_] (get-md :updates))
(defn get-md-mails [_] (get-md :mails))
(defn get-md-releases [_] (get-md :releases))
(defn get-md-changes [_] (get-md :changes))
(defn get-md-released-changes [_] (get-md :released-changes))
(defn get-md-announcements [_] (get-md :announcements))
