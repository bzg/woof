(ns bzg.web
  (:require [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.data :as data]
            [bzg.fetch :as fetch]
            [bzg.db :as db]
            ;; FIXME: Remove in production
            [ring.middleware.reload :as reload]
            [ring.middleware.params :as params]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [mount.core :as mount]
            [tea-time.core :as tt]
            [selmer.parser :as html]
            [selmer.filters :as selmer]
            [markdown.core :as md]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [taoensso.timbre :as timbre])
  (:gen-class))

(selmer/add-filter! :e-pluralize #(when (> (count %) 1) "es"))

(defn- entries-format [{:keys [list-id entries sorting-by]}]
  (let [message-format
        (not-empty (core/archived-message {:list-id list-id}))
        linkify-maybe
        (cond
          message-format
          #(assoc-in % [:link] (format message-format (:message-id %)))
          (:archived-at (first entries))
          #(assoc-in % [:link] (:archived-at %))
          :else identity)]
    (->>
     entries
     (sort-by (condp = sorting-by "date" :date "user" :role :backrefs))
     reverse
     (remove nil?)
     (map linkify-maybe))))

(defn- html-defaults [& [list-id]]
  (let [source (get (:sources db/config) list-id)]
    (-> (merge (:ui db/config)
               (:ui source)
               {:display (or (:show (:ui source))
                             (:show (:ui db/config))
                             (:watch db/config))})
        (dissoc :show))))

(defn- with-html-defaults [config-defaults {:keys [list-id] :as m}]
  (merge (html-defaults list-id)
         {:config config-defaults}
         {:lists (map #(:slug (val %)) (:sources db/config))}
         m))

(defn- page-sources [_ _ _ _ config-defaults]
  (with-html-defaults config-defaults _))

(defn- page-index [page list-id slug-end format-params config-defaults]
  (let [search (:search format-params)]
    (with-html-defaults config-defaults
      {:list-id  list-id
       :search   search
       :page     (name page)
       :slug-end (or (not-empty slug-end) "news")
       :entries
       ;; FIXME: Confusing use of entries twice?
       (entries-format
        (merge {:list-id list-id
                :entries
                (condp = page
                  :news    (fetch/news list-id search)
                  :bug     (fetch/bugs list-id search)
                  :patch   (fetch/patches list-id search)
                  :request (fetch/requests list-id search)
                  ;; :mail    (fetch/mails list-id search)
                  )}
               format-params))})))

(defn- page-overview [_ list-id _ _ config-defaults]
  (with-html-defaults config-defaults
    {:list-id list-id
     :page    "overview"
     ;; TODO: Implement overview features here
     }))

(defn- get-page [page {:keys [query-params path-params uri]}]
  (let [format-params   {:search     (or (get query-params "search") "")
                         :sorting-by (get query-params "sorting-by")}
        config-defaults (into {} (d/entity db/db [:defaults "init"]))
        html-page       (condp = page
                          :sources  {:html "/sources.html" :fn page-sources}
                          :overview {:html "/overview.html" :fn page-overview}
                          {:html "/index.html" :fn page-index})
        slug-end        (peek (re-find #"/([^/]+)$" (or uri "")))
        list-id         (core/slug-to-list-id (:list-slug path-params))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme db/config) (:html html-page)))
      ((:fn html-page) page list-id slug-end format-params config-defaults))}))

(def handler
  (ring/ring-handler
   (ring/router
    [["/"
      ["" {:get #(get-page :news %)}]
      ["news:format" {:get #(data/get-news-data %)}]
      ["sources" {:get #(get-page :sources %)}]
      ["howto"
       {:get (fn [_]
               {:status  200
                :headers {"Content-Type" "text/html"}
                :body    (html/render-file
                          (str "html/" (:theme db/config) "/index.html")
                          (merge (html-defaults)
                                 {:howto (md/md-to-html-string
                                          (slurp (io/resource "md/howto.md")))}))})}]
      ["bugs"
       ["" {:get #(get-page :bug %)}]
       [":format" {:get #(data/get-bugs-data %)}]]
      ["patches"
       ["" {:get #(get-page :patch %)}]
       [":format" {:get #(data/get-patches-data %)}]]
      ["requests"
       ["" {:get #(get-page :request %)}]
       [":format" {:get #(data/get-requests-data %)}]]
      ["mails"
       ["" {:get #(get-page :mail %)}]
       [":format" {:get #(data/get-mails-data %)}]]
      ["overview"
       ["" {:get #(get-page :overview %)}]]
      ;; List per source
      ["source/:list-slug/"
       ["" {:get #(get-page :news %)}]
       ["news:format" {:get #(data/get-news-data %)}]
       ["bugs"
        ["" {:get #(get-page :bug %)}]
        [":format" {:get #(data/get-bugs-data %)}]]
       ["patches"
        ["" {:get #(get-page :patch %)}]
        [":format" {:get #(data/get-patches-data %)}]]
       ["requests"
        ["" {:get #(get-page :request %)}]
        [":format" {:get #(data/get-requests-data %)}]]
       ["mails"
        ["" {:get #(get-page :mail %)}]
        [":format" {:get #(data/get-mails-data %)}]]
       ["overview"
        ["" {:get #(get-page :overview %)}]]]]]
    {:data {:middleware [params/wrap-params]}})
   (ring/create-default-handler
    {:not-found
     (fn [{:keys [query-params path-params]}]
       {:status  200
        :headers {"Content-Type" "text/html"}
        :body    (html/render-file
                  (io/resource (str "html/" (:theme db/config) "/404.html"))
                  (merge (into {} (d/entity db/db [:defaults "init"]))
                         (html-defaults) query-params path-params))})})
   {:middleware
    [parameters/parameters-middleware
     #(wrap-cors
       %
       :access-control-allow-origin [#"^*$"]
       :access-control-allow-methods [:get])]}))

(def woof-server)
(mount/defstate ^{:on-reload :noop} woof-server
  :start (do (server/run-server
              (reload/wrap-reload handler {:dirs ["src" "resources"]})
              {:port (:port db/config)})
             (timbre/info (format "Woof web server started on %s (port %s)"
                                  (:hostname db/config)
                                  (:port db/config))))
  ;; FIXME: Use in production
  ;; :start (server/run-server
  ;;         handler {:port (edn/read-string (:port config/env))})
  :stop (when woof-server
          (woof-server :timeout 100)
          (timbre/info "Woof web server stopped")))

(defn -main []
  (let [admin-address (:admin-address db/config)]
    (tt/start!)
    (core/set-defaults)
    (core/update-person! {:email    admin-address
                          :username (or (:admin-username db/config)
                                        admin-address)
                          :role     :admin}
                         ;; The root admin cannot be removed
                         {:root true})
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
