(ns bzg.web
  (:require [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.data :as data]
            [bzg.config :as config]
            [bzg.feeds :as feeds]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            ;; [ring.middleware.reload :as reload]
            [ring.middleware.params :as params]
            [muuntaja.core :as m]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [mount.core :as mount]
            [clojure.edn :as edn]
            [tea-time.core :as tt]
            [selmer.parser :as html]
            [markdown.core :as md]
            [clojure.java.io :as io])
  (:gen-class))

(defn- db-format [{:keys [db s sorting-by]}]
  (let [linkify-maybe
        (if (not-empty (:mail-url-format config/env))
          #(assoc-in % [:link] (format (:mail-url-format config/env)
                                       (:message-id %)))
          identity)]
    (->>
     db
     (sort-by (if (= sorting-by "date") #(:date %) #(:backrefs %)))
     reverse
     (filter #(re-find (re-pattern (str "(?i)" (or (not-empty s) ""))) (:subject %)))
     (map linkify-maybe))))

(def html-defaults
  {:title          (:title config/env)
   :project-name   (:project-name config/env)
   :project-url    (:project-url config/env)
   :contribute-url (:contribute-url config/env)
   :contribute-cta (:contribute-cta config/env)})

(defn get-homepage [{:keys [query-params]}]
  (let [s (get query-params "s")]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config/env) "/index.html"))
      (merge html-defaults
             {:releases
              (db-format
               {:db (->> (core/get-releases) (sort-by :data) reverse
                         ;; FIXME: Allow to configure
                         (take 3))
                :s  s})
              :changes
              (db-format {:db (core/get-unreleased-changes) :s s})
              :released-changes
              (db-format {:db (core/get-latest-released-changes) :s s})
              :announcements
              (db-format {:db (core/get-announcements) :s s})}))}))

(defn get-page-bugs [{:keys [query-params]}]
  (let [format-params {:s          (get query-params "s")
                       :sorting-by (get query-params "sorting-by")}]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config/env) "/bugs.html"))
      (merge html-defaults
             {:unconfirmed-bugs
              (db-format
               (merge {:db (core/get-unconfirmed-bugs)} format-params))
              :confirmed-bugs
              (db-format
               (merge {:db (core/get-confirmed-bugs)} format-params))}))}))

(defn get-page-mails [{:keys [query-params]}]
  (let [format-params {:s          (get query-params "s")
                       :sorting-by (get query-params "sorting-by")}]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config/env) "/mails.html"))
      (merge html-defaults
             {:mails
              (db-format
               (merge {:db (core/get-mails)} format-params))}))}))

(defn get-page-requests [{:keys [query-params]}]
  (let [format-params {:s          (get query-params "s")
                       :sorting-by (get query-params "sorting-by")}]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config/env) "/requests.html"))
      (merge html-defaults
             {:unhandled-requests
              (db-format (merge {:db (core/get-unhandled-requests)}
                                format-params))
              :handled-requests
              (db-format (merge {:db (core/get-handled-requests)}
                                format-params))}))}))

(defn get-page-patches [{:keys [query-params]}]
  (let [format-params {:s          (get query-params "s")
                       :sorting-by (get query-params "sorting-by")}]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config/env) "/patches.html"))
      (merge html-defaults
             {:approved-patches
              (db-format (merge {:db (core/get-approved-patches)}
                                format-params))
              :unapproved-patches
              (db-format (merge {:db (core/get-unapproved-patches)}
                                format-params))}))}))

(def handler
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [params] (get-homepage params))}]
     ["/bugs" {:get (fn [params] (get-page-bugs params))}]
     ["/mails" {:get (fn [params] (get-page-mails params))}]
     ["/patches" {:get (fn [params] (get-page-patches params))}]
     ["/requests" {:get (fn [params] (get-page-requests params))}]
     ["/howto"
      {:get (fn [_]
              {:status  200
               :headers {"Content-Type" "text/html"}
               :body    (html/render-file
                         (str "html/" (:theme config/env) "/index.html")
                         (merge html-defaults
                                {:howto (md/md-to-html-string
                                         (slurp (io/resource "md/howto.md")))}))})}]
     ;; json data
     ["/updates.json" {:get data/get-data-updates}]
     ["/mails.json" {:get data/get-data-mails}]
     ["/changes.json" {:get data/get-data-changes}]
     ["/released-changes.json" {:get data/get-data-released-changes}]
     ["/announcements.json" {:get data/get-data-announcements}]
     ["/releases.json" {:get data/get-data-releases}]

     ["/bugs.json" {:get data/get-data-bugs}]
     ["/confirmed-bugs.json" {:get data/get-data-confirmed-bugs}]
     ["/unconfirmed-bugs.json" {:get data/get-data-unconfirmed-bugs}]
     
     ["/patches.json" {:get data/get-data-patches}]
     ["/approved-patches.json" {:get data/get-data-approved-patches}]
     ["/unapproved-patches.json" {:get data/get-data-unapproved-patches}]
     
     ["/requests.json" {:get data/get-data-requests}]
     ["/handled-requests.json" {:get data/get-data-handled-requests}]
     ["/unhandled-requests.json" {:get data/get-data-unhandled-requests}]
     
     ;; rss feeds
     ["/updates.rss" {:get feeds/feed-updates}]
     ["/mails.rss" {:get feeds/feed-mails}]
     ["/changes.rss" {:get feeds/feed-changes}]
     ["/released-changes.rss" {:get feeds/feed-released-changes}]
     ["/announcements.rss" {:get feeds/feed-announcements}]
     ["/releases.rss" {:get feeds/feed-releases}]
     
     ["/bugs.rss" {:get feeds/feed-bugs}]
     ["/confirmed-bugs.rss" {:get feeds/feed-confirmed-bugs}]
     ["/unconfirmed-bugs.rss" {:get feeds/feed-unconfirmed-bugs}]
     
     ["/patches.rss" {:get feeds/feed-patches}]
     ["/approved-patches.rss" {:get feeds/feed-approved-patches}]
     ["/unapproved-patches.rss" {:get feeds/feed-unapproved-patches}]
     
     ["/requests.rss" {:get feeds/feed-requests}]
     ["/handled-requests.rss" {:get feeds/feed-handled-requests}]
     ["/unhandled-requests.rss" {:get feeds/feed-unhandled-requests}]
     ]
    {:data {:muuntaja   m/instance
      	    :middleware [params/wrap-params
                         muuntaja/format-middleware]}})
   (ring/create-default-handler)
   {:middleware
    [parameters/parameters-middleware
     #(wrap-cors
       %
       :access-control-allow-origin [#"^*$"]
       :access-control-allow-methods [:get])]}))

(def woof-server)
(mount/defstate ^{:on-reload :noop} woof-server
  ;; :start (server/run-server
  ;;         (reload/wrap-reload handler {:dirs ["src" "resources"]})
  ;;         {:port (edn/read-string (:port config/env))})
  :start (server/run-server
          handler {:port (edn/read-string (:port config/env))})
  :stop (when woof-server (woof-server :timeout 100)))

(defn -main []
  (let [admin-address (:admin-address config/env)]
    (tt/start!)
    (core/update-person {:email    admin-address
                         :username (:admin-username config/env)
                         :role     :admin})
    (core/start-mail-loop!)
    (mount/start #'core/woof-manager #'woof-server)))

;; (-main)

(comment
  (mount/start #'core/woof-manager)
  (mount/stop #'core/woof-manager)
  (mount/start #'woof-server)
  (mount/stop #'woof-server)
  (mount/start)
  (mount/stop)
  )
