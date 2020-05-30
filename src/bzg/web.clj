(ns bzg.web
  (:require [org.httpkit.server :as server]
            [jsonista.core :as j]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.config :as config]
            [clojure.walk :as walk]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ring.middleware.params :as params]
            [muuntaja.core :as m]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [mount.core :as mount]
            [hiccup.page :as h]
            [clj-rss.core :as rss]
            [java-time :as t])
  (:gen-class))

(defn feed [_]
  (letfn [(format-item [{:keys [id subject date from]}]
            {:title       subject
             :link        (format config/mail-archive-formatting-string id)
             :description subject
             :author      from
             :pubDate     date})]
    {:status 200
     :body
     (rss/channel-xml
      {:title       (:feed-title config/config)
       :link        (str (:woof-base-url config/config) "/feed.xml")
       :description (:feed-description config/config)}
      (sort-by
       :pubDate
       (concat
        (map format-item
             (core/intern-id (core/get-unfixed-bugs @core/db)))
        (map format-item
             (core/intern-id (core/get-unreleased-changes @core/db)))
        (map format-item
             (core/intern-id (core/get-releases @core/db))))))}))

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
      [:h1.title.has-text-centered (:woof-page-title config/config)]
      [:h2.subtitle.column.is-8.is-offset-2.has-text-centered
       [:a {:href (str (:woof-base-url config/config) "/feed.xml")} "Subscribe"]
       " / "
       [:a {:href (:main-website-url config/config)}
        (:main-website-name config/config)]]]]
    [:div.container.is-8
     [:div.columns
      [:div.column
       (when-let [bugs (core/intern-id (core/get-unfixed-bugs @core/db))]
         [:section.section
          [:div.container
           [:h1.title "Confirmed bugs"]
           [:div.content
            (for [bug bugs]
              (core/format-default-fn bug))]]])]
      [:div.column
       (when-let [changes (core/intern-id (core/get-unreleased-changes @core/db))]
         [:section.section
          [:div.container
           [:h1.title "Future changes"]
           [:div.content
            (for [change changes]
              (core/format-default-fn change))]]])
       (when-let [releases (core/intern-id (core/get-releases @core/db))]
         [:section.section
          [:div.container
           [:h1.title "Latest releases"]
           [:div.content
            (for [release (take 3 releases)]
              (core/format-default-fn release))]]])]]]
    [:footer.footer
     [:div.columns
      [:div.column.is-offset-4.is-4.has-text-centered
       [:p "Made with " ;; FIXME
        [:a {:href ""} "WOOF"]]]]]]))

(defn get-homepage [_]
  {:status 200
   :body   (homepage)})

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
     ["/bugs" {:get get-bugs}]
     ["/releases" {:get get-releases}]
     ["/changes" {:get get-changes}]
     ["/feed" {:get feed}]]
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

(mount/defstate woof-server
  :start (do (println "Woof started on localhost:3000")
             (server/run-server handler {:port 3000}))
  :stop (do (when woof-server
              (println "Woof stopped")
              (woof-server :timeout 100))))

(defn -main [& [json]]
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
