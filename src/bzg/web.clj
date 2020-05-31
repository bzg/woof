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
            [hiccup.page :as h]
            [clojure.string :as string]
            [clojure.edn :as edn])
  (:gen-class))

(defn homepage []
  (h/html5
   {:lang "en"}
   [:head
    [:title "Woof - Watch Over Our Folders"]
    [:meta {:charset "utf-8"}]
    [:meta {:name "keywords" :content "Woof - Watch Over Our Folders"}]
    [:meta {:name "description" :content "Woof - Watch Over Our Folders"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, shrink-to-fit=yes"}]
    (h/include-css "https://cdn.jsdelivr.net/npm/bulma@0.8.2/css/bulma.min.css")]
   [:body
    [:section.hero
     [:div.hero-body
      [:h1.title.has-text-centered (:title config/woof)]
      [:h2.subtitle.column.is-8.is-offset-2.has-text-centered
       [:a {:href (string/replace
                   (:base-url config/woof)
                   #"([^/])/*$" "$1/feed/updates")}
        "Subscribe"]
       " / "
       [:a {:href (:project-url config/woof)}
        (:project-name config/woof)]]]]
    [:div.container.is-8
     [:div.columns
      [:div.column
       [:section.section
        [:div.container
         [:h1.title "Confirmed bugs"]
         (if-let [bugs (->> (core/get-unfixed-bugs @core/db)
                            core/intern-id
                            (sort-by #(count (:refs %)))
                            reverse
                            not-empty)]
           [:div.content
            (for [bug bugs]
              (core/format-link-fn bug :bug))]
           [:p "No confirmed bug."])]]]
      [:div.column
       [:section.section
        [:div.container
         [:h1.title "Upcoming changes"]
         (if-let [changes (->> (core/get-unreleased-changes @core/db)
                               core/intern-id
                               (sort-by :date)
                               not-empty)]

           [:div.content
            (for [change changes]
              (core/format-link-fn change :change))]
           [:p "No upcoming change."])]]
       [:section.section
        [:div.container
         [:h1.title "Latest releases"]
         (if-let [releases (->> (core/get-releases @core/db)
                                core/intern-id
                                (sort-by :date)
                                reverse
                                not-empty)]
           [:div.content
            (for [release (take 3 releases)]
              (core/format-link-fn release :release))]
           [:p "No release."])]]]]]
    [:footer.footer
     [:div.columns
      [:div.column.is-offset-4.is-4.has-text-centered
       [:p "Feeds: "
        [:a {:href "/feed/bugs"} "bugs"] ", "
        [:a {:href "/feed/changes"} "changes"] ", "
        [:a {:href "/feed/releases"} "releases"] " or "
        [:a {:href "/feed/updates"} "all updates"]]
       [:p "Data: "
        [:a {:href "/data/bugs"} "bugs"] ", "
        [:a {:href "/data/changes"} "changes"] ", "
        [:a {:href "/data/releases"} "releases"] " or "
        [:a {:href "/data/updates"} "all updates"]]
       [:br]
       [:p "Made with "
        [:a {:href "https://github.com/bzg/woof"} "WOOF"]]]]]]))

(defn get-homepage [_]
  {:status 200
   :body   (homepage)})

(defn get-updates [_]
  {:status 200
   :body   (core/intern-id @core/db)})

(defn get-bugs [_]
  {:status 200
   :body   (core/intern-id (core/get-unfixed-bugs @core/db))})

(defn get-releases [_]
  {:status 200
   :body   (core/intern-id (core/get-releases @core/db))})

(defn get-changes [_]
  {:status 200
   :body   (core/intern-id (core/get-unreleased-changes @core/db))})

(def handler
  (ring/ring-handler
   (ring/router
    [["/" {:get get-homepage}]
     ["/data"
      ["/updates" {:get get-updates}]
      ["/bugs" {:get get-bugs}]
      ["/changes" {:get get-changes}]
      ["/releases" {:get get-releases}]]
     ["/feed"
      ["/updates" {:get feeds/feed-updates}]
      ["/bugs" {:get feeds/feed-bugs}]
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

(let [port (edn/read-string (:port config/woof))]
  (mount/defstate woof-server
    :start (do (println (format "Woof monitoring on localhost:%s started" port))
               (server/run-server handler {:port port}))
    :stop (when woof-server
            (println (format "Woof monitoring on localhost:%s stopped" port))
            (woof-server :timeout 100))))

(defn -main []
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
