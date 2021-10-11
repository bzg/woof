(ns bzg.data
  (:require [bzg.core :as core]))

(defn- get-data [what]
  {:status 200
   :body
   (map #(update % :date str)
        (->>
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
           :changes            (core/get-unreleased-changes)
           :releases           (core/get-releases)
           :updates            (core/get-updates)
           )))})

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
