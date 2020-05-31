(ns bzg.feeds
  (:require [clj-rss.core :as rss]
            [bzg.config :as config]
            [bzg.web :as web]
            [bzg.core :as core]
            [clojure.string :as string]
            [hiccup.page :as h]))

(defn feed-description [msg type]
  (let [cdata "<![CDATA[ %s ]]>"]
    (format cdata (h/html5 [:body (web/format-link-fn msg type)]))))

(defn feed-item [{:keys [id subject date from] :as msg} type]
  {:title       subject
   :link        (format (:mail-url-format config/config) id)
   :description (feed-description msg type)
   :author      from
   :pubDate     date})

(defn feed [path items]
  {:status 200
   :body
   (rss/channel-xml
    {:title       (str (:feed-title config/config) " - " path)
     :link        (string/replace
                   (:base-url config/config)
                   #"([^/])/*$" (str "$1" path))
     :description (str (:feed-description config/config) " - " path)}
    items)})

(defn feed-updates [_]
  (feed "/feed/updates"
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % :bug)
               (web/intern-id (core/get-unfixed-bugs @core/db)))
          (map #(feed-item % :change)
               (web/intern-id (core/get-unreleased-changes @core/db)))
          (map #(feed-item % :release)
               (web/intern-id (core/get-releases @core/db)))))))

(defn feed-bugs [_]
  (feed "/feed/bugs"
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % :bug)
               (web/intern-id (core/get-unfixed-bugs @core/db)))))))

(defn feed-changes [_]
  (feed "/feed/changes"
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % :change)
               (web/intern-id (core/get-unreleased-changes @core/db)))))))

(defn feed-releases [_]
  (feed "/feed/releases"
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % :release)
               (web/intern-id (core/get-releases @core/db)))))))

