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
  (merge html-defaults {:config config-defaults} m))

(defn- page-home [_ _ config-defaults]
  (with-html-defaults config-defaults
    {:lists (map #(:slug (val %)) (:mailing-lists core/config))}))

(defn- page-index [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    {:announcements
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-announcements list-id (or (:search format-params) ""))}
             format-params))}))

(defn- page-changes [list-id format-params config-defaults]
  (let [search (or (:search format-params) "")]
    (with-html-defaults config-defaults
      (merge
       (when (-> config-defaults :features :change)
         {:changes
          (entries-format
           (merge {:list-id list-id
                   :entries (core/get-unreleased-changes list-id search)}
                  format-params))
          :released-changes
          (entries-format
           (merge {:list-id list-id
                   :entries (core/get-latest-released-changes list-id search)}
                  format-params))})
       (when (-> config-defaults :features :release)
         {:releases
          (entries-format
           (merge {:list-id list-id
                   :entries (core/get-releases list-id search)}
                  format-params))})))))

(defn- page-mails [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    {:mails
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-mails
                        list-id (:search format-params))} format-params))}))

(defn- page-bugs [list-id format-params config-defaults]
  (let [search (or (:search format-params) "")]
    (with-html-defaults config-defaults
      {:unconfirmed-bugs
       (entries-format
        (merge {:list-id list-id
                :entries (core/get-unconfirmed-bugs list-id search)} format-params))
       :confirmed-bugs
       (entries-format
        (merge {:list-id list-id
                :entries (core/get-confirmed-bugs list-id search)} format-params))})))

(defn- page-requests [list-id format-params config-defaults]
  (let [search (or (:search format-params) "")]
    (with-html-defaults config-defaults
      {:unhandled-requests
       (entries-format
        (merge {:list-id list-id
                :entries (core/get-unhandled-requests list-id search)}
               format-params))
       :handled-requests
       (entries-format
        (merge {:list-id list-id
                :entries (core/get-handled-requests list-id search)}
               format-params))})))

(defn- page-patches [list-id format-params config-defaults]
  (let [search (or (:search format-params) "")]
    (with-html-defaults config-defaults
      {:unapproved-patches
       (entries-format
        (merge {:list-id list-id
                :entries (core/get-unapproved-patches list-id search)}
               format-params))
       :approved-patches
       (entries-format
        (merge {:list-id list-id
                :entries (core/get-approved-patches list-id search)}
               format-params))})))

(defn- page-tops [list-id _ config-defaults]
  (with-html-defaults config-defaults
    {:top-bug-contributors          (core/get-top-bug-contributors list-id)
     :top-patch-contributors        (core/get-top-patch-contributors list-id)
     :top-request-contributors      (core/get-top-request-contributors list-id)
     :top-announcement-contributors (core/get-top-announcement-contributors list-id)}))

(def html-page-fn
  {:home     {:html "/home.html" :fn page-home}
   :index    {:html "/index.html" :fn page-index}
   :changes  {:html "/changes.html" :fn page-changes}
   :patches  {:html "/patches.html" :fn page-patches}
   :bugs     {:html "/bugs.html" :fn page-bugs}
   :requests {:html "/requests.html" :fn page-requests}
   :mails    {:html "/mails.html" :fn page-mails}
   :tops     {:html "/tops.html" :fn page-tops}})

(defn- get-page [page {:keys [query-params path-params]}]
  (let [format-params   {:search     (or (get query-params "search") "")
                         :sorting-by (get query-params "sorting-by")}
        config-defaults (merge (into {} (d/entity core/db [:defaults "init"]))
                               format-params)
        html-page       (get html-page-fn page)
        list-id         (core/slug-to-list-id (:list-slug path-params))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme core/config) (:html html-page)))
      ((:fn html-page) list-id format-params config-defaults))}))

(def handler
  (ring/ring-handler
   (ring/router
    [["/"
      ["" {:get #(get-page :home %)}]
      ["howto"
       {:get (fn [_]
               {:status  200
                :headers {"Content-Type" "text/html"}
                :body    (html/render-file
                          (str "html/" (:theme core/config) "/index.html")
                          (merge html-defaults
                                 {:howto (md/md-to-html-string
                                          (slurp (io/resource "md/howto.md")))}))})}]
      [":list-slug/"
       ["" {:get #(get-page :index %)}]
       ["updates:format" {:get #(data/get-updates-data %)}]
       ["announcements:format" {:get #(data/get-announcements-data %)}]
       ["bugs"
        ["" {:get #(get-page :bugs %)}]
        [":format" {:get #(data/get-bugs-data %)}]
        ["-unconfirmed:format" {:get #(data/get-unconfirmed-bugs-data %)}]
        ["-confirmed:format" {:get #(data/get-confirmed-bugs-data %)}]]
       ["requests"
        ["" {:get #(get-page :requests %)}]
        [":format" {:get #(data/get-requests-data %)}]
        ["-unhandled:format" {:get #(data/get-unhandled-requests-data %)}]
        ["-handled:format" {:get #(data/get-handled-requests-data %)}]]
       ["changes"
        ["" {:get #(get-page :changes %)}]
        [":format" {:get #(data/get-changes-data %)}]
        ["-released:format" {:get #(data/get-released-changes-data %)}]]
       ["patches"
        ["" {:get #(get-page :patches %)}]
        [":format" {:get #(data/get-patches-data %)}]
        ["-unapproved:format" {:get #(data/get-unapproved-patches-data %)}]
        ["-approved:format" {:get #(data/get-approved-patches-data %)}]]
       ["mails"
        ["" {:get #(get-page :mails %)}]
        [":format" {:get #(data/get-mails-data %)}]]
       ["tops"
        ["" {:get #(get-page :tops %)}]
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
