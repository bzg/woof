(ns bzg.feeds
  (:require [clj-rss.core :as rss]
            [bzg.config :as config]
            [bzg.core :as core]
            [clojure.string :as string]
            [selmer.parser :as html]
            [clojure.java.io :as io]))

(defn feed-description [msg what]
  (format "<![CDATA[ %s ]]>"
          (html/render-file
           (io/resource "html/link.html")
           (assoc msg
                  :link (format (:mail-url-format config/woof) (:id msg))
                  :what what))))

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
          (map #(feed-item % :bug) (core/get-unfixed-bugs))
          (map #(feed-item % :patch) (core/get-unapplied-patches))
          (map #(feed-item % :bug) (core/get-pending-help-requests))
          (map #(feed-item % :change) (core/get-unreleased-changes))
          (map #(feed-item % :release) (core/get-releases))))))

(defn- make-feed [{:keys [path what]}]
  (feed path
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % what)
               (condp = what
                 :bug     (core/get-unfixed-bugs)
                 :help    (core/get-pending-help-requests)
                 :patch   (core/get-unapplied-patches)
                 :change  (core/get-unreleased-changes)
                 :release (core/get-releases)))))))

(defn feed-bugs [_] (make-feed {:path "/feed/bugs" :what :bug}))
(defn feed-patches [_] (make-feed {:path "/feed/patches" :what :patch}))
(defn feed-help [_] (make-feed {:path "/feed/help" :what :help}))
(defn feed-changes [_] (make-feed {:path "/feed/changes" :what :change}))
(defn feed-releases [_] (make-feed {:path "/feed/releases" :what :release}))
