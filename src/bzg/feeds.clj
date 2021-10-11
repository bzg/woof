(ns bzg.feeds
  (:require [clj-rss.core :as rss]
            [bzg.config :as config]
            [bzg.core :as core]
            [clojure.string :as string]
            [selmer.parser :as html]
            [clojure.java.io :as io]))

(defn feed-description [msg what]
  (let [what  (name what)
        msgid (:message-id msg)]
    (format
     "<![CDATA[ %s ]]>"
     (if-let [mail-url-format (not-empty (:mail-url-format config/woof))]
       (html/render-file
        (io/resource (str "html/" (:theme config/woof) "/link.html"))
        (assoc msg
               :link (format (:mail-url-format config/woof) msgid)
               :what what))
       (str msgid (format " (%s)" what))))))

(defn feed-item [{:keys [message-id subject date from] :as msg} what]
  (let [link (format (:mail-url-format config/woof) message-id)]
    {:title       subject
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
  (feed "/updates.rss"
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % :bug) (core/get-unfixed-bugs))
          (map #(feed-item % :patch) (core/get-unapplied-patches))
          (map #(feed-item % :bug) (core/get-unhandled-requests))
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
                 :mail    (core/get-mails)
                 :request (core/get-unhandled-requests)
                 :patch   (core/get-unapplied-patches)
                 :change  (core/get-unreleased-changes)
                 :release (core/get-releases)))))))

(defn feed-bugs [_] (make-feed {:path "/bugs.rss" :what :bug}))
(defn feed-mails [_] (make-feed {:path "/mails.rss" :what :mail}))
(defn feed-patches [_] (make-feed {:path "/patches.rss" :what :patch}))
(defn feed-requests [_] (make-feed {:path "/requests.rss" :what :request}))
(defn feed-changes [_] (make-feed {:path "/changes.rss" :what :change}))
(defn feed-releases [_] (make-feed {:path "/releases.rss" :what :release}))
