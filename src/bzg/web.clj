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
        (if (not-empty (:mail-url-format config/woof))
          #(assoc-in % [:link] (format (:mail-url-format config/woof)
                                       (:message-id %)))
          identity)]
    (->>
     db
     (sort-by (if (= sorting-by "date") #(:date %) #(:backrefs %)))
     reverse
     (filter #(re-find (re-pattern (str "(?i)" (or (not-empty s) ""))) (:subject %)))
     (map linkify-maybe))))

(def html-defaults
  {:title          (:title config/woof)
   :project-name   (:project-name config/woof)
   :project-url    (:project-url config/woof)
   :contribute-url (:contribute-url config/woof)
   :contribute-cta (:contribute-cta config/woof)})

(defn get-homepage [{:keys [query-params]}]
  (let [s (get query-params "s")]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config/woof) "/index.html"))
      (merge html-defaults
             {:releases
              (db-format
               {:db (->> (core/get-releases) (sort-by :data) reverse (take 3))
                :s  s})
              :changes
              (db-format {:db (core/get-unreleased-changes) :s s})
              ;; FIXME: Add announcements
              }))}))

(defn get-page-bugs [{:keys [query-params]}]
  (let [format-params {:s          (get query-params "s")
                       :sorting-by (get query-params "sorting-by")}]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config/woof) "/bugs.html"))
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
      (io/resource (str "html/" (:theme config/woof) "/mails.html"))
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
      (io/resource (str "html/" (:theme config/woof) "/requests.html"))
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
      (io/resource (str "html/" (:theme config/woof) "/patches.html"))
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
                         (str "html/" (:theme config/woof) "/index.html")
                         (merge html-defaults
                                {:howto (md/md-to-html-string
                                         (slurp (io/resource "md/howto.md")))}))})}]
     ;; json data
     ["/updates.json" {:get data/get-data-updates}]
     ["/mails.json" {:get data/get-data-mails}]
     ["/bugs.json" {:get data/get-data-bugs}]
     ["/patches.json" {:get data/get-data-patches}]
     ["/requests.json" {:get data/get-data-requests}]
     ["/changes.json" {:get data/get-data-changes}]
     ["/releases.json" {:get data/get-data-releases}]
     ;; rss feeds
     ["/updates.rss" {:get feeds/feed-updates}]
     ["/mails.rss" {:get feeds/feed-mails}]
     ["/bugs.rss" {:get feeds/feed-bugs}]
     ["/patches.rss" {:get feeds/feed-patches}]
     ["/requests.rss" {:get feeds/feed-requests}]
     ["/changes.rss" {:get feeds/feed-changes}]
     ["/releases.rss" {:get feeds/feed-releases}]]
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
  ;;         {:port (edn/read-string (:port config/woof))})
  :start (server/run-server
          handler {:port (edn/read-string (:port config/woof))})
  :stop (when woof-server (woof-server :timeout 100)))

(defn -main []
  (tt/start!)
  (core/start-mail-loop!)
  (mount/start #'core/woof-manager #'woof-server))

;; (-main)

(comment
  (mount/start #'core/woof-manager)
  (mount/stop #'core/woof-manager)
  (mount/start #'woof-server)
  (mount/stop #'woof-server)
  (mount/start)
  (mount/stop)
  )
