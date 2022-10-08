(ns bzg.data
  (:require [bzg.core :as core]
            [clojure.string :as string]
            [clj-rss.core :as rss]
            [selmer.parser :as html]
            [clojure.java.io :as io]))

(defn- format-org [resources list-id]
  (let [fmt (core/list-id-or-slug-to-mail-url-format {:list-id list-id})]
    (->> resources
         (map #(format " - [[%s][%s: %s]] (%s)"
                       (if fmt (format fmt (:message-id %))
                           (:message-id %))
                       (:username %)
                       (string/replace (:subject %) #"^\[[^]]+\] " "")
                       (:date %)))
         (string/join "\n"))))

(defn- format-md [resources list-id]
  (let [fmt (core/list-id-or-slug-to-mail-url-format {:list-id list-id})]
    (->> resources
         (map #(format " - [%s: %s](%s \"%s\")"
                       (:username %)
                       (string/replace (:subject %) #"^\[[^]]+\] " "")
                       (if fmt (format fmt (:message-id %))
                           (:message-id %))
                       (:date %)))
         (string/join "\n"))))

(defn feed-item-description [msg fmt]
  (let [msgid (:message-id msg)]
    (format
     "<![CDATA[ %s ]]>"
     (if fmt
       (html/render-file
        (io/resource (str "html/" (:theme core/config) "/link.html"))
        (assoc msg :link (format fmt msgid)))
       msgid))))

(defn feed-item [{:keys [message-id subject date from] :as msg} list-id]
  (let [fmt  (core/list-id-or-slug-to-mail-url-format {:list-id list-id})
        link (if fmt (format fmt message-id) message-id)]
    {:title       subject
     :link        link
     :description (feed-item-description msg fmt)
     :author      from
     :guid        link
     :pubDate     (.toInstant date)}))

(defn- format-rss [resources list-id]
  (rss/channel-xml
   {:title       (str (:project-name (:ui core/config)) " - " list-id)
    :link        (string/replace
                  (:hostname core/config)
                  #"([^/])/*$" (str "$1/" list-id))
    :description (str (:title core/config) " - " list-id)}
   (sort-by :pubDate (map #(feed-item % list-id) resources))))

(defn get-data [what {:keys [path-params]}]
  (let [list-id   (core/slug-to-list-id (:list-slug path-params))
        format    (subs (:format path-params) 1)
        resources (condp = what
                    :confirmed-bugs   (core/get-confirmed-bugs list-id)
                    :unconfirmed-bugs (core/get-unconfirmed-bugs list-id)
                    :bugs             (core/get-unfixed-bugs list-id)

                    :handled-requests   (core/get-handled-requests list-id)
                    :unhandled-requests (core/get-unhandled-requests list-id)
                    :requests           (core/get-undone-requests list-id)

                    :approved-patches   (core/get-approved-patches list-id)
                    :unapproved-patches (core/get-unapproved-patches list-id)
                    :patches            (core/get-patches list-id)

                    :unreleased-changes (core/get-unreleased-changes list-id)
                    :released-changes   (core/get-latest-released-changes list-id)
                    :changes            (core/get-changes list-id)

                    :announcements (core/get-announcements list-id)
                    :mails         (core/get-mails list-id)
                    :releases      (core/get-releases list-id)
                    :updates       (core/get-updates list-id))
        headers (condp = format
                  "rss"  {"Content-Type" "application/xml"}
                  "md"   {"Content-Type" "text/plain; charset=utf-8"}
                  "org"  {"Content-Type" "text/plain; charset=utf-8"}
                  "json" nil ;; FIXME: Weird?
                  )]
    {:status  200
     :headers headers
     :body
     (condp = format
       "rss"  (format-rss resources list-id)
       "json" resources
       "md"   (format-md resources list-id)
       "org"  (format-org resources list-id))}))

(defn get-bugs-data [params] (get-data :bugs params))
(defn get-unconfirmed-bugs-data [params] (get-data :unconfirmed-bugs params))
(defn get-confirmed-bugs-data [params] (get-data :confirmed-bugs params))

(defn get-requests-data [params] (get-data :requests params))
(defn get-unhandled-requests-data [params] (get-data :unhandled-requests params))
(defn get-handled-requests-data [params] (get-data :handled-requests params))

(defn get-changes-data [params] (get-data :changes params))
(defn get-released-changes-data [params] (get-data :released-changes params))
(defn get-unreleased-changes-data [params] (get-data :unreleased-changes params))

(defn get-patches-data [params] (get-data :patches params))
(defn get-unapproved-patches-data [params] (get-data :unapproved-patches params))
(defn get-approved-patches-data [params] (get-data :approved-patches params))

(defn get-announcements-data [params] (get-data :announcements params))
(defn get-mails-data [params] (get-data :mails params))
(defn get-releases-data [params] (get-data :releases params))
(defn get-updates-data [params] (get-data :updates params))
