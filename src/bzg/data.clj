(ns bzg.data
  (:require [bzg.core :as core]
            [clojure.string :as string]))

(defn- get-resources [what list-id]
  (condp = what
    :confirmed-bugs     (core/get-confirmed-bugs list-id)
    :unconfirmed-bugs   (core/get-unconfirmed-bugs list-id)
    :bugs               (core/get-unfixed-bugs list-id)
    :unhandled-requests (core/get-unhandled-requests list-id)
    :handled-requests   (core/get-handled-requests list-id)
    :requests           (core/get-undone-requests list-id)
    :unapproved-patches (core/get-unapproved-patches list-id)
    :approved-patches   (core/get-approved-patches list-id)
    :patches            (core/get-unapplied-patches list-id)
    :mails              (core/get-mails list-id)
    :changes            (core/get-upcoming-changes list-id)
    :released-changes   (core/get-latest-released-changes list-id)
    :announcements      (core/get-announcements list-id)
    :releases           (core/get-releases list-id)
    :updates            (core/get-updates list-id)))

(defn- get-data [what list-slug]
  (let [list-id (core/slug-to-list-id list-slug)]
    {:status 200
     :body   (get-resources what list-id)}))

(defn- get-org [what list-slug]
  (let [list-id (core/slug-to-list-id list-slug)]
    {:status  200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body
     (string/join
      "\n"
      (map #(format " - [[%s][%s: %s]] (%s)"
                    (if-let [fmt (:mail-url-format core/config)]
                      (format fmt (:message-id %))
                      (:message-id %))
                    (:username %)
                    (string/replace (:subject %) #"^\[[^]]+\] " "")
                    (:date %)) 
           (get-resources what list-id)))}))

(defn- get-md [what list-slug]
  (let [list-id (core/slug-to-list-id list-slug)]
    {:status  200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body
     (string/join
      "\n"
      (map #(format " - [%s: %s](%s \"%s\")"
                    (:username %)
                    (string/replace (:subject %) #"^\[[^]]+\] " "")
                    (if-let [fmt (:mail-url-format core/config)]
                      (format fmt (:message-id %))
                      (:message-id %))
                    (:date %))
           (get-resources what list-id)))}))

(defn get-data-bugs [list-slug] (get-data :bugs list-slug))
(defn get-data-confirmed-bugs [list-slug] (get-data :confirmed-bugs list-slug))
(defn get-data-unconfirmed-bugs [list-slug] (get-data :unconfirmed-bugs list-slug))
(defn get-data-patches [list-slug] (get-data :patches list-slug))
(defn get-data-approved-patches [list-slug] (get-data :approved-patches list-slug))
(defn get-data-unapproved-patches [list-slug] (get-data :unapproved-patches list-slug))
(defn get-data-requests [list-slug] (get-data :requests list-slug))
(defn get-data-handled-requests [list-slug] (get-data :handled-requests list-slug))
(defn get-data-unhandled-requests [list-slug] (get-data :unhandled-requests list-slug))
(defn get-data-updates [list-slug] (get-data :updates list-slug))
(defn get-data-mails [list-slug] (get-data :mails list-slug))
(defn get-data-releases [list-slug] (get-data :releases list-slug))
(defn get-data-changes [list-slug] (get-data :changes list-slug))
(defn get-data-released-changes [list-slug] (get-data :released-changes list-slug))
(defn get-data-announcements [list-slug] (get-data :announcements list-slug))

(defn get-org-bugs [list-slug] (get-org :bugs list-slug))
(defn get-org-confirmed-bugs [list-slug] (get-org :confirmed-bugs list-slug))
(defn get-org-unconfirmed-bugs [list-slug] (get-org :unconfirmed-bugs list-slug))
(defn get-org-patches [list-slug] (get-org :patches list-slug))
(defn get-org-approved-patches [list-slug] (get-org :approved-patches list-slug))
(defn get-org-unapproved-patches [list-slug] (get-org :unapproved-patches list-slug))
(defn get-org-requests [list-slug] (get-org :requests list-slug))
(defn get-org-handled-requests [list-slug] (get-org :handled-requests list-slug))
(defn get-org-unhandled-requests [list-slug] (get-org :unhandled-requests list-slug))
(defn get-org-updates [list-slug] (get-org :updates list-slug))
(defn get-org-mails [list-slug] (get-org :mails list-slug))
(defn get-org-releases [list-slug] (get-org :releases list-slug))
(defn get-org-changes [list-slug] (get-org :changes list-slug))
(defn get-org-released-changes [list-slug] (get-org :released-changes list-slug))
(defn get-org-announcements [list-slug] (get-org :announcements list-slug))

(defn get-md-bugs [list-slug] (get-md :bugs list-slug))
(defn get-md-confirmed-bugs [list-slug] (get-md :confirmed-bugs list-slug))
(defn get-md-unconfirmed-bugs [list-slug] (get-md :unconfirmed-bugs list-slug))
(defn get-md-patches [list-slug] (get-md :patches list-slug))
(defn get-md-approved-patches [list-slug] (get-md :approved-patches list-slug))
(defn get-md-unapproved-patches [list-slug] (get-md :unapproved-patches list-slug))
(defn get-md-requests [list-slug] (get-md :requests list-slug))
(defn get-md-handled-requests [list-slug] (get-md :handled-requests list-slug))
(defn get-md-unhandled-requests [list-slug] (get-md :unhandled-requests list-slug))
(defn get-md-updates [list-slug] (get-md :updates list-slug))
(defn get-md-mails [list-slug] (get-md :mails list-slug))
(defn get-md-releases [list-slug] (get-md :releases list-slug))
(defn get-md-changes [list-slug] (get-md :changes list-slug))
(defn get-md-released-changes [list-slug] (get-md :released-changes list-slug))
(defn get-md-announcements [list-slug] (get-md :announcements list-slug))
