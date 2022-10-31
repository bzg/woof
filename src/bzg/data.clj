(ns bzg.data
  (:require [bzg.core :as core]
            [bzg.fetch :as fetch]
            [bzg.db :as db]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [clj-rss.core :as rss]
            [selmer.parser :as html]
            [clojure.java.io :as io]))

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

(defn feed-item-description [msg archived-at]
  (format
   "<![CDATA[ %s ]]>"
   (if (re-matches #"http.*" archived-at)
     (html/render-file
      (io/resource (str "html/" (:theme db/config) "/link.html"))
      (assoc msg :link archived-at))
     archived-at)))

(defn feed-item [{:keys [message-id archived-at subject date from] :as msg} source-id]
  (let [archived-at (core/archived-message
                     {:source-id   source-id
                      :archived-at archived-at
                      :message-id  message-id})
        link        (if (not-empty archived-at) archived-at message-id)]
    {:title       subject
     :link        link
     :description (feed-item-description msg archived-at)
     :author      from
     :guid        link
     :pubDate     (.toInstant date)}))

(defn- format-rss [resources source-id]
  (rss/channel-xml
   {:title       (str (:project-name (:ui db/config)) " - " source-id)
    :link        (string/replace
                  (:hostname db/config)
                  #"([^/])/*$" (str "$1/" source-id))
    :description (str (:title db/config) " - " source-id)}
   (sort-by :pubDate (map #(feed-item % source-id) resources))))

(defn get-data [what {:keys [path-params query-params]}]
  (let [source-id (core/slug-to-source-id (:source-slug path-params))
        format    (subs (:format path-params) 1)
        search    (or (:search query-params) "")
        resources (condp = what
                    :bugs     (fetch/bugs source-id search)
                    :requests (fetch/requests source-id search)
                    :patches  (fetch/patches source-id search)
                    :news     (fetch/news source-id search)
                    ;; :mails         (fetch/mails source-id search)
                    )
        headers   (condp = format
                    "rss"  {"Content-Type" "application/xml"}
                    "md"   {"Content-Type" "text/plain; charset=utf-8"}
                    "org"  {"Content-Type" "text/plain; charset=utf-8"}
                    "json" {"Content-Type" "application/json; charset=utf-8"})]
    {:status  200
     :headers headers
     :body
     (condp = format
       "rss"  (format-rss resources source-id)
       "json" (json/write-str (map #(into {} %) resources))
       "md"   (format-md resources source-id)
       "org"  (format-org resources source-id))}))

(defn get-bugs-data [params] (get-data :bugs params))
(defn get-requests-data [params] (get-data :requests params))
(defn get-patches-data [params] (get-data :patches params))
(defn get-news-data [params] (get-data :news params))
(defn get-mails-data [params] (get-data :mails params))

