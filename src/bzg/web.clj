(ns bzg.web
  (:require [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.config :as config]
            [bzg.feeds :as feeds]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            ;; [ring.middleware.reload :as reload]
            [ring.middleware.params :as params]
            [muuntaja.core :as m]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [mount.core :as mount]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [tea-time.core :as tt]
            [selmer.parser :as html]
            [markdown.core :as md]
            [clojure.java.io :as io])
  (:gen-class))

(defn- get-data [what]
  {:status 200
   :body   (condp = what
             :updates  identity
             :bugs     (core/get-bugs)
             :patches  (core/get-patches)
             :helps    (core/get-help-requests)
             :releases (core/get-releases)
             :changes  (core/get-changes))})

(defn- db-filter [{:keys [db s sort-bugs-by sort-help-by sort-patches-by]}]
  (->>
   db
   (sort-by (if (some (into #{} (remove nil? [sort-bugs-by
                                              sort-help-by
                                              sort-patches-by]))
                      "date")
              #(:date %) #(count (:refs %))))
   reverse
   (filter #(re-find (re-pattern (str "(?i)" (or (not-empty s) ""))) (:summary %)))
   (map #(assoc-in % [:link] (format (:mail-url-format config/woof) (:id %))))
   (map #(assoc-in % [:refs-cnt] (count (:refs %))))))

(def html-defaults
  {:title            (:title config/woof)
   :project-name     (:project-name config/woof)
   :project-url      (:project-url config/woof)
   :updates-feed     (string/replace
                      (:base-url config/woof)
                      #"([^/])/*$" "$1/feed/updates")
   :contributing-url (:contributing-url config/woof)
   :contributing-cta (:contributing-cta config/woof)})

(defn get-homepage [{:keys [query-params]}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body
   (html/render-file
    (io/resource (str "html/" (:theme config/woof) "/index.html"))
    (merge html-defaults
           {:helps
            (db-filter {:db           (core/get-pending-help-requests)
                        :s            (get query-params "s")
                        :sort-help-by (get query-params "sort-help-by")})
            :bugs
            (db-filter {:db           (core/get-unfixed-bugs)
                        :s            (get query-params "s")
                        :sort-bugs-by (get query-params "sort-bugs-by")                                       })
            :patches
            (db-filter {:db              (core/get-unapplied-patches)
                        :s               (get query-params "s")
                        :sort-patches-by (get query-params "sort-patches-by")})
            :releases
            (db-filter {:db (core/get-releases)
                        :s  (get query-params "s")})
            :changes
            (db-filter {:db (core/get-changes)
                        :s  (get query-params "s")})}))})

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
     ["/howto"
      {:get (fn [_]
              {:status  200
               :headers {"Content-Type" "text/html"}
               :body    (html/render-file
                         (str "html/" (:theme config/woof) "/index.html")
                         (merge html-defaults
                                {:howto (md/md-to-html-string
                                         (slurp (io/resource "md/howto.md")))}))})}]
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
