(ns bzg.feeds
  (:require [clj-rss.core :as rss]
            [bzg.config :as config]
            [bzg.core :as core]
            [clojure.string :as string]
            [hiccup.core :as h]))

(defn feed-description [msg what]
  (let [cdata "<![CDATA[ %s ]]>"]
    (format cdata (h/html (core/format-link-fn msg what)))))

(defn feed-item [{:keys [id summary date from] :as msg} what]
  (let [link (format (:mail-url-format config/woof) id)]
    {:title       summary
     :link        link
     :description (feed-description msg what)
     :author      from
     :guid        link
     :pubDate     date}))

(defn feed [path items]
  {:status  200
   :headers {"Content-Type" "application/xml"}
   :body
   (rss/channel-xml
    {:title       (str (:feed-title config/woof) " - " path)
     :link        (string/replace
                   (:base-url config/woof)
                   #"([^/])/*$" (str "$1" path))
     :description (str (:feed-description config/woof) " - " path)}
    items)})

(defn feed-updates [_]
  (feed "/feed/updates"
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % :bug)
               (core/intern-id (core/get-unfixed-bugs @core/db)))
          (map #(feed-item % :bug)
               (core/intern-id (core/get-pending-help @core/db)))
          (map #(feed-item % :change)
               (core/intern-id (core/get-unreleased-changes @core/db)))
          (map #(feed-item % :release)
               (core/intern-id (core/get-releases @core/db)))))))

(defn- make-feed [{:keys [path what]}]
  (let [get-type (condp = what
                   :bug     core/get-unfixed-bugs
                   :help    core/get-pending-help
                   :change  core/get-unreleased-changes
                   :release core/get-releases)]
    (feed path
          (sort-by
           :pubDate
           (concat
            (map #(feed-item % what)
                 (core/intern-id (get-type @core/db))))))))

(defn feed-bugs [_] (make-feed {:path "/feed/bugs" :what :bug}))

(defn feed-help [_] (make-feed {:path "/feed/help" :what :help}))

(defn feed-changes [_] (make-feed {:path "/feed/changes" :what :change}))

(defn feed-releases [_] (make-feed {:path "/feed/releases" :what :release}))
