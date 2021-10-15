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
     (if-let [mail-url-format (not-empty (:mail-url-format config/env))]
       (html/render-file
        (io/resource (str "html/" (:theme config/env) "/link.html"))
        (assoc msg :link (format mail-url-format msgid) :what what))
       (str msgid (format " (%s)" what))))))

(defn feed-item [{:keys [message-id subject date from] :as msg} what]
  (let [link (format (:mail-url-format config/env) message-id)]
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
    {:title       (str (:feed-title config/env) " - " path)
     :link        (string/replace
                   (:base-url config/env)
                   #"([^/])/*$" (str "$1" path))
     :description (str (:feed-description config/env) " - " path)}
    items)})

(defn feed-updates [_]
  (feed "/updates.rss"
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % :confirmed-bugs) (core/get-confirmed-bugs))
          (map #(feed-item % :unconfirmed-bugs) (core/get-unconfirmed-bugs))
          (map #(feed-item % :bugs) (core/get-unfixed-bugs))
          (map #(feed-item % :mails) (core/get-mails))
          (map #(feed-item % :unhandled-requests) (core/get-unhandled-requests))
          (map #(feed-item % :handled-requests) (core/get-handled-requests))
          (map #(feed-item % :requests) (core/get-undone-requests))
          (map #(feed-item % :unapproved-patches) (core/get-unapproved-patches))
          (map #(feed-item % :approved-patches) (core/get-approved-patches))
          (map #(feed-item % :patches) (core/get-unapplied-patches))
          (map #(feed-item % :changes) (core/get-unreleased-changes))
          (map #(feed-item % :released-changes) (core/get-latest-released-changes))
          (map #(feed-item % :announcements) (core/get-announcements))
          (map #(feed-item % :releases) (core/get-releases))))))

(defn- make-feed [{:keys [path what]}]
  (feed path
        (sort-by
         :pubDate
         (concat
          (map #(feed-item % what)
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
                 :announcements      (core/get-announcements)
                 :changes            (core/get-unreleased-changes)
                 :released-changes   (core/get-latest-released-changes)
                 :releases           (core/get-releases)
                 :updates            (core/get-updates)
                 ))))))

(defn feed-confirmed-bugs [_] (make-feed {:path "/bugs.rss" :what :confirmed-bugs}))
(defn feed-unconfirmed-bugs [_] (make-feed {:path "/bugs.rss" :what :unconfirmed-bugs}))
(defn feed-bugs [_] (make-feed {:path "/bugs.rss" :what :bugs}))

(defn feed-unapproved-patches [_] (make-feed {:path "/unapproved-patches.rss" :what :unapproved-patches}))
(defn feed-approved-patches [_] (make-feed {:path "/approved-patches.rss" :what :approved-patches}))
(defn feed-patches [_] (make-feed {:path "/patches.rss" :what :patches}))

(defn feed-unhandled-requests [_] (make-feed {:path "/unhandled-requests.rss" :what :unhandled-requests}))
(defn feed-handled-requests [_] (make-feed {:path "/handled-requests.rss" :what :handled-requests}))
(defn feed-requests [_] (make-feed {:path "/requests.rss" :what :requests}))

(defn feed-mails [_] (make-feed {:path "/mails.rss" :what :mails}))
(defn feed-changes [_] (make-feed {:path "/changes.rss" :what :changes}))
(defn feed-released-changes [_] (make-feed {:path "/released-changes.rss" :what :changes}))
(defn feed-announcements [_] (make-feed {:path "/announcements.rss" :what :announcements}))
(defn feed-releases [_] (make-feed {:path "/releases.rss" :what :releases}))
