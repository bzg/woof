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
            [selmer.filters :as selmer]
            [markdown.core :as md]
            [clojure.java.io :as io]
            [datalevin.core :as d])
  (:gen-class))

(selmer/add-filter! :e-pluralize #(when (> (count %) 1) "es"))

(defn- entries-format [{:keys [entries search sorting-by]}]
  (let [linkify-maybe
        (if (not-empty (:mail-url-format config/env))
          #(assoc-in % [:link] (format (:mail-url-format config/env)
                                       (:message-id %)))
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
  {:title          (:title config/env)
   :project-name   (:project-name config/env)
   :project-url    (:project-url config/env)
   :contribute-url (:contribute-url config/env)
   :contribute-cta (:contribute-cta config/env)
   :support-url    (:support-url config/env)
   :support-cta    (:support-cta config/env)})

(defn- with-html-defaults [config-defaults m]
  (merge html-defaults {:config config-defaults} m))

(defn- page-index [format-params config-defaults]
  (with-html-defaults config-defaults
    {:announcements
     (entries-format
      (merge {:entries (core/get-announcements)}
             format-params))}))

(defn- page-changes [format-params config-defaults]
  (with-html-defaults config-defaults
    (merge
     (when (-> config-defaults :features :bug)
       {:releases
        (entries-format
         (merge {:entries (->> (core/get-releases)
                               (take (-> config-defaults :max :releases)))}
                format-params))})
     (when (-> config-defaults :features :change)
       {:changes
        (entries-format (merge {:entries (core/get-upcoming-changes)}
                               format-params))})
     (when (-> config-defaults :features :release)
       {:released-changes
        (entries-format
         (merge {:entries (core/get-latest-released-changes)}
                format-params))}))))

(defn- page-mails [format-params config-defaults]
  (with-html-defaults config-defaults
    {:mails
     (entries-format
      (merge {:entries (core/get-mails)} format-params))}))

(defn- page-bugs [format-params config-defaults]
  (with-html-defaults config-defaults
    {:unconfirmed-bugs
     (entries-format
      (merge {:entries (core/get-unconfirmed-bugs)} format-params))
     :confirmed-bugs
     (entries-format
      (merge {:entries (core/get-confirmed-bugs)} format-params))}))

(defn- page-requests [format-params config-defaults]
  (with-html-defaults config-defaults
    {:unhandled-requests
     (entries-format (merge {:entries (core/get-unhandled-requests)}
                            format-params))
     :handled-requests
     (entries-format (merge {:entries (core/get-handled-requests)}
                            format-params))}))

(defn- page-patches [format-params config-defaults]
  (with-html-defaults config-defaults
    {:unapproved-patches
     (entries-format (merge {:entries (core/get-unapproved-patches)}
                            format-params))
     :unapplied-patches
     (entries-format (merge {:entries (core/get-unapplied-patches)}
                            format-params))}))

(defn- page-tops [_ config-defaults]
  (with-html-defaults config-defaults
    {:top-bug-contributors          (core/get-top-bug-contributors)
     :top-patch-contributors        (core/get-top-patch-contributors)
     :top-request-contributors      (core/get-top-request-contributors)
     :top-announcement-contributors (core/get-top-announcement-contributors)}))

(def html-page-fn
  {:index    {:html "/index.html" :fn page-index}
   :changes  {:html "/changes.html" :fn page-changes}
   :patches  {:html "/patches.html" :fn page-patches}
   :bugs     {:html "/bugs.html" :fn page-bugs}
   :requests {:html "/requests.html" :fn page-requests}
   :mails    {:html "/mails.html" :fn page-mails}
   :tops     {:html "/tops.html" :fn page-tops}})

(defn- get-page [query-params page]
  (let [format-params   {:search     (get query-params "search")
                         :sorting-by (get query-params "sorting-by")}
        config-defaults (merge (into {} (d/entity core/db [:defaults "init"]))
                               format-params)
        html-page       (get html-page-fn page)]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme config-defaults) (:html html-page)))
      ((:fn html-page) format-params config-defaults))}))

(defn- get-page-index [{:keys [query-params]}]
  (get-page query-params :index))

(defn- get-page-changes [{:keys [query-params]}]
  (get-page query-params :changes))

(defn- get-page-mails [{:keys [query-params]}]
  (get-page query-params :mails))

(defn- get-page-bugs [{:keys [query-params]}]
  (get-page query-params :bugs))

(defn- get-page-requests [{:keys [query-params]}]
  (get-page query-params :requests))

(defn- get-page-patches [{:keys [query-params]}]
  (get-page query-params :patches))

(defn- get-page-tops [{:keys [query-params]}]
  (get-page query-params :tops))

(def handler
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [params] (get-page-index params))}]
     ["/changes" {:get (fn [params] (get-page-changes params))}]
     ["/requests" {:get (fn [params] (get-page-requests params))}]
     ["/bugs" {:get (fn [params] (get-page-bugs params))}]
     ["/patches" {:get (fn [params] (get-page-patches params))}]
     ["/mails" {:get (fn [params] (get-page-mails params))}]
     ["/tops" {:get (fn [params] (get-page-tops params))}]
     ["/howto"
      {:get (fn [_]
              {:status  200
               :headers {"Content-Type" "text/html"}
               :body    (html/render-file
                         (str "html/" (:theme config/env) "/index.html")
                         (merge html-defaults
                                {:howto (md/md-to-html-string
                                         (slurp (io/resource "md/howto.md")))}))})}]
     ;; FIXME: only expose enabled export formats?
     ;; Json data
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

     ;; Org data
     ["/updates.org" {:get data/get-org-updates}]
     ["/mails.org" {:get data/get-org-mails}]
     ["/changes.org" {:get data/get-org-changes}]
     ["/released-changes.org" {:get data/get-org-released-changes}]
     ["/announcements.org" {:get data/get-org-announcements}]
     ["/releases.org" {:get data/get-org-releases}]

     ["/bugs.org" {:get data/get-org-bugs}]
     ["/confirmed-bugs.org" {:get data/get-org-confirmed-bugs}]
     ["/unconfirmed-bugs.org" {:get data/get-org-unconfirmed-bugs}]

     ["/patches.org" {:get data/get-org-patches}]
     ["/approved-patches.org" {:get data/get-org-approved-patches}]
     ["/unapproved-patches.org" {:get data/get-org-unapproved-patches}]

     ["/requests.org" {:get data/get-org-requests}]
     ["/handled-requests.org" {:get data/get-org-handled-requests}]
     ["/unhandled-requests.org" {:get data/get-org-unhandled-requests}]

     ;; Markdown data
     ["/updates.md" {:get data/get-md-updates}]
     ["/mails.md" {:get data/get-md-mails}]
     ["/changes.md" {:get data/get-md-changes}]
     ["/released-changes.md" {:get data/get-md-released-changes}]
     ["/announcements.md" {:get data/get-md-announcements}]
     ["/releases.md" {:get data/get-md-releases}]

     ["/bugs.md" {:get data/get-md-bugs}]
     ["/confirmed-bugs.md" {:get data/get-md-confirmed-bugs}]
     ["/unconfirmed-bugs.md" {:get data/get-md-unconfirmed-bugs}]

     ["/patches.md" {:get data/get-md-patches}]
     ["/approved-patches.md" {:get data/get-md-approved-patches}]
     ["/unapproved-patches.md" {:get data/get-md-unapproved-patches}]

     ["/requests.md" {:get data/get-md-requests}]
     ["/handled-requests.md" {:get data/get-md-handled-requests}]
     ["/unhandled-requests.md" {:get data/get-md-unhandled-requests}]

     ;; RSS feeds
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
    (core/set-defaults)
    (core/update-person! {:email    admin-address
                          :username (or (:admin-username config/env)
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
