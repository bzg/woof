(ns bzg.web
  (:require [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.config :as config]
            [bzg.feeds :as feeds]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.params :as params]
            [muuntaja.core :as m]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [mount.core :as mount]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [tea-time.core :as tt]
            [selmer.parser :as html]
            [clojure.java.io :as io])
  (:gen-class))

(defn- get-data [what]
  {:status 200
   :body   (core/intern-id
            (apply (condp = what
                     :updates  identity
                     :bugs     core/get-bugs
                     :patches  core/get-patches
                     :helps    core/get-help
                     :releases core/get-releases
                     :changes  core/get-changes)
                   [@core/db]))})

(defn- get-db-data [kvl s srt]
  (->> kvl
       (map (fn [[k v]] {:id k :data v}))
       (sort-by (if (= srt "date") #(:date (:data %)) #(count (:refs (:data %)))))
       reverse
       (filter #(re-find (re-pattern (str "(?i)" (or (not-empty s) "")))
                         (:summary (:data %))))
       (map #(assoc-in
              % [:data :link]
              (format (:mail-url-format config/woof) (:id %))))
       (map #(assoc-in
              % [:data :refs-cnt]
              (count (:refs (:data %)))))))

(defn get-homepage [{:keys [query-params]}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (html/render-file
             (io/resource (str "html/" (:theme config/woof) "/index.html"))
             {:title            (:title config/woof)
              :updates-feed     (string/replace
                                 (:base-url config/woof)
                                 #"([^/])/*$" "$1/feed/updates")
              ;; FIXME: still needed?
              ;; :project-url      (:project-url config/woof)
              ;; :project-name     (:project-name config/woof)
              :contributing-url (:contributing-url config/woof)
              :contributing-cta (:contributing-cta config/woof)
              :helps            (get-db-data (apply core/get-pending-help [@core/db])
                                             (get query-params "s")
                                             (get query-params "sort-help-by"))
              :bugs             (get-db-data (apply core/get-unfixed-bugs [@core/db])
                                             (get query-params "s")
                                             (get query-params "sort-bugs-by"))
              :patches          (get-db-data (apply core/get-unapplied-patches [@core/db])
                                             (get query-params "s")
                                             (get query-params "sort-patches-by"))
              :releases         (get-db-data (apply core/get-releases [@core/db])
                                             (get query-params "s") [])
              :changes          (get-db-data (apply core/get-changes [@core/db])
                                             (get query-params "s") [])})})

(defn get-data-updates [_] (get-data :updates))
(defn get-data-bugs [_] (get-data :bugs))
(defn get-data-patches [_] (get-data :patches))
(defn get-data-helps [_] (get-data :helps))
(defn get-data-releases [_] (get-data :releases))
(defn get-data-changes [_] (get-data :changes))

(def handler
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [params] (get-homepage params))}]
     ["/data"
      ["/updates" {:get get-data-updates}]
      ["/bugs" {:get get-data-bugs}]
      ["/patches" {:get get-data-patches}]
      ["/help" {:get get-data-helps}]
      ["/changes" {:get get-data-changes}]
      ["/releases" {:get get-data-releases}]]
     ["/feed"
      ["/updates" {:get feeds/feed-updates}]
      ["/bugs" {:get feeds/feed-bugs}]
      ["/patches" {:get feeds/feed-patches}]
      ["/help" {:get feeds/feed-help}]
      ["/changes" {:get feeds/feed-changes}]
      ["/releases" {:get feeds/feed-releases}]]]
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
(let [port (edn/read-string (:port config/woof))]
  (mount/defstate woof-server
    :start (server/run-server handler {:port port})
    :stop (when woof-server (woof-server :timeout 100))))

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
