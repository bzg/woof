(ns bzg.data
  (:require [bzg.core :as core]))

(defn- get-data [what]
  {:status 200
   :body
   (map #(update % :date str)
        (->>
         (condp = what
           :updates  (core/get-updates)
           :mails    (core/get-mails)
           :bugs     (core/get-bugs)
           :patches  (core/get-patches)
           :requests (core/get-requests)
           :releases (core/get-releases)
           :changes  (core/get-changes))))})

(defn get-data-updates [_] (get-data :updates))
(defn get-data-mails [_] (get-data :mails))
(defn get-data-bugs [_] (get-data :bugs))
(defn get-data-patches [_] (get-data :patches))
(defn get-data-requests [_] (get-data :requests))
(defn get-data-releases [_] (get-data :releases))
(defn get-data-changes [_] (get-data :changes))
