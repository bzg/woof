(ns bzg.web
  (:require [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.data :as data]
            [bzg.feeds :as feeds]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            ;; FIXME: Remove in production
            [ring.middleware.reload :as reload]
            [ring.middleware.params :as params]
            [muuntaja.core :as m]
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

(defn- entries-format [{:keys [list-id entries search sorting-by]}]
  (let [linkify-maybe
        (if-let [mail-url-format
                 (not-empty (:mail-url-format
                             (first (filter #(= (:address %) list-id)
                                            (:mailing-lists core/config)))))]
          #(assoc-in % [:link] (format mail-url-format (:message-id %)))
          identity)]
    (->>
     entries
     (sort-by (condp = sorting-by "date" :date "user" :role :backrefs))
     reverse
     (map (fn [e]
            (if-let [s (not-empty search)]
              (when (re-find (re-pattern (str "(?i)" s))
                             (:subject e)) e)
              e)))
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
    {:lists (map :slug (:mailing-lists core/config))}))

(defn- page-index [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    {:announcements
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-announcements list-id)}
             format-params))}))

(defn- page-changes [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    (merge
     (when (-> config-defaults :features :bug)
       {:releases
        (entries-format
         (merge {:list-id list-id
                 :entries
                 (->> (core/get-releases list-id)
                      (take (-> config-defaults :display-max :releases)))}
                format-params))})
     (when (-> config-defaults :features :change)
       {:changes
        (entries-format
         (merge {:list-id list-id
                 :entries (core/get-upcoming-changes list-id)}
                format-params))})
     (when (-> config-defaults :features :release)
       {:released-changes
        (entries-format
         (merge {:list-id list-id
                 :entries (core/get-latest-released-changes list-id)}
                format-params))}))))

(defn- page-mails [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    {:mails
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-mails list-id)} format-params))}))

(defn- page-bugs [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    {:unconfirmed-bugs
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-unconfirmed-bugs list-id)} format-params))
     :confirmed-bugs
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-confirmed-bugs list-id)} format-params))}))

(defn- page-requests [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    {:unhandled-requests
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-unhandled-requests list-id)}
             format-params))
     :handled-requests
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-handled-requests list-id)}
             format-params))}))

(defn- page-patches [list-id format-params config-defaults]
  (with-html-defaults config-defaults
    {:unapproved-patches
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-unapproved-patches list-id)}
             format-params))
     :unapplied-patches
     (entries-format
      (merge {:list-id list-id
              :entries (core/get-unapplied-patches list-id)}
             format-params))}))

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
  (let [format-params   {:search     (get query-params "search")
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
    [["/" {:get (fn [params] (get-page :home params))}]
     ["/:list-slug/" {:get (fn [params] (get-page :index params))}]
     ["/:list-slug/changes" {:get (fn [params] (get-page :changes params))}]
     ["/:list-slug/requests" {:get (fn [params] (get-page :requests params))}]
     ["/:list-slug/bugs" {:get (fn [params] (get-page :bugs params))}]
     ["/:list-slug/patches" {:get (fn [params] (get-page :patches params))}]
     ["/:list-slug/mails" {:get (fn [params] (get-page :mails params))}]
     ["/:list-slug/tops" {:get (fn [params] (get-page :tops params))}]
     ["/howto"
      {:get (fn [_]
              {:status  200
               :headers {"Content-Type" "text/html"}
               :body    (html/render-file
                         (str "html/" (:theme core/config) "/index.html")
                         (merge html-defaults
                                {:howto (md/md-to-html-string
                                         (slurp (io/resource "md/howto.md")))}))})}]

     ;; FIXME: only expose enabled export formats?
     ;; Json data
     ["/:list-slug/updates.json" {:get (fn [{:keys [path-params]}] (data/get-data-updates (:list-slug path-params)))}]
     ["/:list-slug/mails.json" {:get (fn [{:keys [path-params]}] (data/get-data-mails (:list-slug path-params)))}]
     ["/:list-slug/changes.json" {:get (fn [{:keys [path-params]}] (data/get-data-changes (:list-slug path-params)))}]
     ["/:list-slug/released-changes.json" {:get (fn [{:keys [path-params]}] (data/get-data-released-changes (:list-slug path-params)))}]
     ["/:list-slug/announcements.json" {:get (fn [{:keys [path-params]}] (data/get-data-announcements (:list-slug path-params)))}]
     ["/:list-slug/releases.json" {:get (fn [{:keys [path-params]}] (data/get-data-releases (:list-slug path-params)))}]

     ["/:list-slug/bugs.json" {:get (fn [{:keys [path-params]}] (data/get-data-bugs (:list-slug path-params)))}]
     ["/:list-slug/confirmed-bugs.json" {:get (fn [{:keys [path-params]}] (data/get-data-confirmed-bugs (:list-slug path-params)))}]
     ["/:list-slug/unconfirmed-bugs.json" {:get (fn [{:keys [path-params]}] (data/get-data-unconfirmed-bugs (:list-slug path-params)))}]

     ["/:list-slug/patches.json" {:get (fn [{:keys [path-params]}] (data/get-data-patches (:list-slug path-params)))}]
     ["/:list-slug/approved-patches.json" {:get (fn [{:keys [path-params]}] (data/get-data-approved-patches (:list-slug path-params)))}]
     ["/:list-slug/unapproved-patches.json" {:get (fn [{:keys [path-params]}] (data/get-data-unapproved-patches (:list-slug path-params)))}]

     ["/:list-slug/requests.json" {:get (fn [{:keys [path-params]}] (data/get-data-requests (:list-slug path-params)))}]
     ["/:list-slug/handled-requests.json" {:get (fn [{:keys [path-params]}] (data/get-data-handled-requests (:list-slug path-params)))}]
     ["/:list-slug/unhandled-requests.json" {:get (fn [{:keys [path-params]}] (data/get-data-unhandled-requests (:list-slug path-params)))}]

     ;; Org data
     ["/:list-slug/updates.org" {:get (fn [{:keys [path-params]}] (data/get-org-updates (:list-slug path-params)))}]
     ["/:list-slug/mails.org" {:get (fn [{:keys [path-params]}] (data/get-org-mails (:list-slug path-params)))}]
     ["/:list-slug/changes.org" {:get (fn [{:keys [path-params]}] (data/get-org-changes (:list-slug path-params)))}]
     ["/:list-slug/released-changes.org" {:get (fn [{:keys [path-params]}] (data/get-org-released-changes (:list-slug path-params)))}]
     ["/:list-slug/announcements.org" {:get (fn [{:keys [path-params]}] (data/get-org-announcements (:list-slug path-params)))}]
     ["/:list-slug/releases.org" {:get (fn [{:keys [path-params]}] (data/get-org-releases (:list-slug path-params)))}]

     ["/:list-slug/bugs.org" {:get (fn [{:keys [path-params]}] (data/get-org-bugs (:list-slug path-params)))}]
     ["/:list-slug/confirmed-bugs.org" {:get (fn [{:keys [path-params]}] (data/get-org-confirmed-bugs (:list-slug path-params)))}]
     ["/:list-slug/unconfirmed-bugs.org" {:get (fn [{:keys [path-params]}] (data/get-org-unconfirmed-bugs (:list-slug path-params)))}]

     ["/:list-slug/patches.org" {:get (fn [{:keys [path-params]}] (data/get-org-patches (:list-slug path-params)))}]
     ["/:list-slug/approved-patches.org" {:get (fn [{:keys [path-params]}] (data/get-org-approved-patches (:list-slug path-params)))}]
     ["/:list-slug/unapproved-patches.org" {:get (fn [{:keys [path-params]}] (data/get-org-unapproved-patches (:list-slug path-params)))}]

     ["/:list-slug/requests.org" {:get (fn [{:keys [path-params]}] (data/get-org-requests (:list-slug path-params)))}]
     ["/:list-slug/handled-requests.org" {:get (fn [{:keys [path-params]}] (data/get-org-handled-requests (:list-slug path-params)))}]
     ["/:list-slug/unhandled-requests.org" {:get (fn [{:keys [path-params]}] (data/get-org-unhandled-requests (:list-slug path-params)))}]

     ;; Markdown data
     ["/:list-slug/updates.md" {:get (fn [{:keys [path-params]}] (data/get-md-updates (:list-slug path-params)))}]
     ["/:list-slug/mails.md" {:get (fn [{:keys [path-params]}] (data/get-md-mails (:list-slug path-params)))}]
     ["/:list-slug/changes.md" {:get (fn [{:keys [path-params]}] (data/get-md-changes (:list-slug path-params)))}]
     ["/:list-slug/released-changes.md" {:get (fn [{:keys [path-params]}] (data/get-md-released-changes (:list-slug path-params)))}]
     ["/:list-slug/announcements.md" {:get (fn [{:keys [path-params]}] (data/get-md-announcements (:list-slug path-params)))}]
     ["/:list-slug/releases.md" {:get (fn [{:keys [path-params]}] (data/get-md-releases (:list-slug path-params)))}]

     ["/:list-slug/bugs.md" {:get (fn [{:keys [path-params]}] (data/get-md-bugs (:list-slug path-params)))}]
     ["/:list-slug/confirmed-bugs.md" {:get (fn [{:keys [path-params]}] (data/get-md-confirmed-bugs (:list-slug path-params)))}]
     ["/:list-slug/unconfirmed-bugs.md" {:get (fn [{:keys [path-params]}] (data/get-md-unconfirmed-bugs (:list-slug path-params)))}]

     ["/:list-slug/patches.md" {:get (fn [{:keys [path-params]}] (data/get-md-patches (:list-slug path-params)))}]
     ["/:list-slug/approved-patches.md" {:get (fn [{:keys [path-params]}] (data/get-md-approved-patches (:list-slug path-params)))}]
     ["/:list-slug/unapproved-patches.md" {:get (fn [{:keys [path-params]}] (data/get-md-unapproved-patches (:list-slug path-params)))}]

     ["/:list-slug/requests.md" {:get (fn [{:keys [path-params]}] (data/get-md-requests (:list-slug path-params)))}]
     ["/:list-slug/handled-requests.md" {:get (fn [{:keys [path-params]}] (data/get-md-handled-requests (:list-slug path-params)))}]
     ["/:list-slug/unhandled-requests.md" {:get (fn [{:keys [path-params]}] (data/get-md-unhandled-requests (:list-slug path-params)))}]

     ;; RSS feeds
     ["/:list-slug/updates.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-updates (:list-slug path-params)))}]
     ["/:list-slug/mails.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-mails (:list-slug path-params)))}]
     ["/:list-slug/changes.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-changes (:list-slug path-params)))}]
     ["/:list-slug/released-changes.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-released-changes (:list-slug path-params)))}]
     ["/:list-slug/announcements.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-announcements (:list-slug path-params)))}]
     ["/:list-slug/releases.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-releases (:list-slug path-params)))}]

     ["/:list-slug/bugs.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-bugs (:list-slug path-params)))}]
     ["/:list-slug/confirmed-bugs.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-confirmed-bugs (:list-slug path-params)))}]
     ["/:list-slug/unconfirmed-bugs.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-unconfirmed-bugs (:list-slug path-params)))}]

     ["/:list-slug/patches.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-patches (:list-slug path-params)))}]
     ["/:list-slug/approved-patches.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-approved-patches (:list-slug path-params)))}]
     ["/:list-slug/unapproved-patches.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-unapproved-patches (:list-slug path-params)))}]

     ["/:list-slug/requests.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-requests (:list-slug path-params)))}]
     ["/:list-slug/handled-requests.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-handled-requests (:list-slug path-params)))}]
     ["/:list-slug/unhandled-requests.rss" {:get (fn [{:keys [path-params]}] (feeds/feed-unhandled-requests (:list-slug path-params)))}]
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
