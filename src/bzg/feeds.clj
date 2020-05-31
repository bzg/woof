(ns bzg.feeds
  (:require [clj-rss.core :as rss]
            [bzg.config :as config]
            [bzg.web :as web]
            [bzg.core :as core]
            [clojure.string :as string]))

(defn feed-updates [_]
  (letfn [(format-item [{:keys [id subject date from]}]
            {:title       subject
             :link        (format (:mail-url-format config/config) id)
             :description subject
             :author      from
             :pubDate     date})]
    {:status 200
     :body
     (rss/channel-xml
      {:title       (:feed-title config/config)
       :link        (string/replace
                     (:base-url config/config)
                     #"([^/])/*$" "$1/feed/updates")
       :description (:feed-description config/config)}
      (sort-by
       :pubDate
       (concat
        (map format-item
             (web/intern-id (core/get-unfixed-bugs @core/db)))
        (map format-item
             (web/intern-id (core/get-unreleased-changes @core/db)))
        (map format-item
             (web/intern-id (core/get-releases @core/db))))))}))

(defn feed-bugs [_]
  (letfn [(format-item [{:keys [id subject date from]}]
            {:title       subject
             :link        (format (:mail-url-format config/config) id)
             :description subject
             :author      from
             :pubDate     date})]
    {:status 200
     :body
     (rss/channel-xml
      {:title       (:feed-title config/config)
       :link        (string/replace
                     (:base-url config/config)
                     #"([^/])/*$" "$1/feed/bugs")
       :description (:feed-description config/config)}
      (sort-by
       :pubDate
       (concat
        (map format-item
             (web/intern-id (core/get-unfixed-bugs @core/db))))))}))

(defn feed-releases [_]
  (letfn [(format-item [{:keys [id subject date from]}]
            {:title       subject
             :link        (format (:mail-url-format config/config) id)
             :description subject
             :author      from
             :pubDate     date})]
    {:status 200
     :body
     (rss/channel-xml
      {:title       (:feed-title config/config)
       :link        (string/replace
                     (:base-url config/config)
                     #"([^/])/*$" "$1/feed/releases")
       :description (:feed-description config/config)}
      (sort-by
       :pubDate
       (concat
        (map format-item
             (web/intern-id (core/get-releases @core/db))))))}))

(defn feed-changes [_]
  (letfn [(format-item [{:keys [id subject date from]}]
            {:title       subject
             :link        (format (:mail-url-format config/config) id)
             :description subject
             :author      from
             :pubDate     date})]
    {:status 200
     :body
     (rss/channel-xml
      {:title       (:feed-title config/config)
       :link        (string/replace
                     (:base-url config/config)
                     #"([^/])/*$" "$1/feed/changes")
       :description (:feed-description config/config)}
      (sort-by
       :pubDate
       (concat
        (map format-item
             (web/intern-id (core/get-unreleased-changes @core/db))))))}))
