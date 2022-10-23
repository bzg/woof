(ns bzg.web
  (:require [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.data :as data]
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

(def html-defaults
  {:title          (:title (:ui core/config))
   :project-name   (:project-name (:ui core/config))
   :project-url    (:project-url (:ui core/config))
   :contribute-url (:contribute-url (:ui core/config))
   :contribute-cta (:contribute-cta (:ui core/config))
   :support-url    (:support-url (:ui core/config))
   :support-cta    (:support-cta (:ui core/config))})

(defn- with-html-defaults [config-defaults m]
  (merge html-defaults
         {:config config-defaults}
         {:lists (map #(:slug (val %)) (:sources core/config))}
         m))

(defn- page-sources [_ _ _ _ config-defaults]
  (with-html-defaults config-defaults _))

(defn- page-index [feature list-id slug-end format-params config-defaults]
  (let [search (:search format-params)]
    (with-html-defaults config-defaults
      {:list-id  list-id
       :feature  (name feature)
       :slug-end slug-end
       :entries
       ;; FIXME: Confusing use of entries twice?
       (entries-format
        (merge {:list-id list-id
                :entries
                (condp = feature
                  ;; FIXME: Replace get-* functions with available features
                  :announcement
                  (core/get-announcements list-id search)
                  :bug
                  (core/get-unconfirmed-bugs list-id search)
                  :patch
                  (core/get-unapproved-patches list-id search)
                  :request
                  (core/get-unhandled-requests list-id search)
                  :mail
                  (core/get-mails list-id search)
                  ;; TODO: implement get-tops?
                  ;; :tops
                  ;; (core/get-tops list-id search)
                  )}
               format-params))})))

;; (defn- page-tops [list-id _ config-defaults]
;;   (with-html-defaults config-defaults
;;     {:top-bug-contributors          (core/get-top-bug-contributors list-id)
;;      :top-patch-contributors        (core/get-top-patch-contributors list-id)
;;      :top-request-contributors      (core/get-top-request-contributors list-id)
;;      :top-announcement-contributors (core/get-top-announcement-contributors list-id)}))

(defn- get-page [page {:keys [query-params path-params uri]}]
  (let [format-params   {:search     (or (get query-params "search") "")
                         :sorting-by (get query-params "sorting-by")}
        config-defaults (merge (into {} (d/entity core/db [:defaults "init"]))
                               format-params)
        html-page       (if (= page :sources)
                          {:html "/sources.html" :fn page-sources}
                          {:html "/index.html" :fn page-index})
        slug-end        (peek (re-find #"/([^/]+)$" (or uri "")))
        list-id         (core/slug-to-list-id (:list-slug path-params))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme core/config) (:html html-page)))
      ((:fn html-page) page list-id slug-end format-params config-defaults))}))

(def handler
  (ring/ring-handler
   (ring/router
    [["/"
      ["" {:get #(get-page :announcement %)}]
      ["announcements:format" {:get #(data/get-announcements-data %)}]
      ["sources" {:get #(get-page :sources %)}]
      ["howto"
       {:get (fn [_]
               {:status  200
                :headers {"Content-Type" "text/html"}
                :body    (html/render-file
                          (str "html/" (:theme core/config) "/index.html")
                          (merge html-defaults
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
      ;; ["tops"
      ;;  ["" {:get #(get-page :tops %)}]]
      ;; List per source
      ["source/:list-slug/"
       ["" {:get #(get-page :announcement %)}]
       ["announcements:format" {:get #(data/get-announcements-data %)}]
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
        [":format" {:get #(data/get-mails-data %)}]]]]]
    {:data {:middleware [params/wrap-params]}})
   (ring/create-default-handler
    {:not-found
     (fn [{:keys [query-params path-params]}]
       {:status  200
        :headers {"Content-Type" "text/html"}
        :body    (html/render-file
                  (io/resource (str "html/" (:theme core/config) "/404.html"))
                  (merge (into {} (d/entity core/db [:defaults "init"]))
                         html-defaults query-params path-params))})})
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
              {:port (:port core/config)})
             (timbre/info (format "Woof web server started on %s (port %s)"
                                  (:hostname core/config)
                                  (:port core/config))))
  ;; FIXME: Use in production
  ;; :start (server/run-server
  ;;         handler {:port (edn/read-string (:port config/env))})
  :stop (when woof-server
          (woof-server :timeout 100)
          (timbre/info "Woof web server stopped")))

(defn -main []
  (let [admin-address (:admin-address core/config)]
    (tt/start!)
    (core/set-defaults)
    (core/update-person! {:email    admin-address
                          :username (or (:admin-username core/config)
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
