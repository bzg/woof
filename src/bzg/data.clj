;; Copyright (c) 2022-2023 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns bzg.data
  (:require [bzg.core :as core]
            [bzg.fetch :as fetch]
            [bzg.db :as db]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [clj-rss.core :as rss]))

(defn- format-org [resources source-id]
  (->> resources
       (map #(format " - [[%s][%s: %s]] (%s)"
                     (core/archived-message
                      {:source-id   source-id
                       :archived-at (:archived-at %)
                       :message-id  (:message-id %)})
                     (:username %)
                     (string/replace (:subject %) #"^\[[^]]+\] " "")
                     (:date %)))
       (string/join "\n")))

(defn- format-md [resources source-id]
  (->> resources
       (map #(format " - [%s: %s](%s \"%s\")"
                     (:username %)
                     (string/replace (:subject %) #"^\[[^]]+\] " "")
                     (core/archived-message
                      {:source-id   source-id
                       :archived-at (:archived-at %)
                       :message-id  (:message-id %)})
                     (:date %)))
       (string/join "\n")))

(defn- feed-item [{:keys [message-id archived-at subject date username from body]} source-id]
  (let [archived-at (core/archived-message
                     {:source-id   source-id
                      :archived-at archived-at
                      :message-id  message-id})
        link        (if (not-empty archived-at) archived-at message-id)
        item-data
        {:title       subject
         :link        link
         :description subject
         :author      (core/make-to username from)
         :guid        link
         :pubDate     (.toInstant date)}]
    (if (not-empty body)
      (conj item-data {"content:encoded"
                       (format "<![CDATA[%s]]>"
                               (string/replace body "\n" "<br>"))})
      item-data)))

(defn- format-rss [resources source-id]
  (let [source-id-suffix (str " - " source-id)]
    (rss/channel-xml
     {:title       (str (:project-name (:ui db/config))
                        (when source-id source-id-suffix))
      :link        (string/replace (:project-url (:ui db/config))
                                   #"([^/])/*$" (str "$1/" source-id))
      :description (str (:title (:ui db/config))
                        (when source-id  source-id-suffix))}
     (sort-by :pubDate (map #(feed-item % source-id) resources)))))

(defn- get-data [what {:keys [path-params query-params]}]
  (let [source-id (core/slug-to-source-id (:source-slug path-params))
        format    (subs (:format path-params) 1)
        search    (or (:search query-params) "")
        resources (condp = what
                    :index    (fetch/index source-id search)
                    :bugs     (fetch/bugs source-id search)
                    :requests (fetch/requests source-id search)
                    :patches  (fetch/patches source-id search)
                    :news     (fetch/news source-id search))
        ;; Display the latest first.
        resources (reverse (sort-by :date resources))]
    (if-not (some #{"rss" "md" "org" "json"} (list format))
      {:status  200
       :headers {"Content-Type" "text/plain"}
       :body    "Resource not found"}
      (let [headers-and-body
            (condp = format
              "rss"  [{"Content-Type" "application/xml"}
                      (format-rss resources source-id)]
              "md"   [{"Content-Type" "text/plain; charset=utf-8"}
                      (format-md resources source-id)]
              "org"  [{"Content-Type" "text/plain; charset=utf-8"}
                      (format-org resources source-id)]
              "json" [{"Content-Type" "application/json; charset=utf-8"}
                      (json/write-str (map #(into {} %) resources))])]
        {:status  200
         :headers (first headers-and-body)
         :body    (last headers-and-body)}))))

(defn get-all-data [params] (get-data :index params))
(defn get-bugs-data [params] (get-data :bugs params))
(defn get-requests-data [params] (get-data :requests params))
(defn get-patches-data [params] (get-data :patches params))
(defn get-news-data [params] (get-data :news params))
