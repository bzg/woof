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

(defn intern-id [m]
  (map (fn [[k v]] (assoc v :id k)) m))

(defn format-link-fn
  [{:keys [subject date id version versions commit]} type]
  (condp = type
    :bug     [:p [:a {:href   (format (:mail-url-format config/config) id)
                      :title  "Find and read the message"
                      :target "_blank"}
                  subject]]
    :change  [:p
              [:a {:href   (format (:mail-url-format config/config) id)
                   :title  "Find and read the message"
                   :target "_blank"}
               subject]
              " / ("
              [:a {:href   (format (:commit-url-format config/config) commit)
                   :title  "Find and read the commit"
                   :target "_blank"}
               id] ")"]
    :release [:p [:a {:href   (format (:mail-url-format config/config) id)
                      :title  "Find and read the release message"
                      :target "_blank"}
                  subject]]))

(defn feed [_]
  (letfn [(format-item [{:keys [id subject date from]}]
            {:title       subject
             :link        (format (:mail-url-format config/config) id)
             :description subject
             :author      from
             :pubDate     date})]
    {:status 200
     :body
     (rss/channel-xml
      {:title       (:feed-title config/config)
       :link        (str (:base-url config/config) "/feed.xml")
       :description (:feed-description config/config)}
      (sort-by
       :pubDate
       (concat
        (map format-item
             (intern-id (core/get-unfixed-bugs @core/db)))
        (map format-item
             (intern-id (core/get-unreleased-changes @core/db)))
        (map format-item
             (intern-id (core/get-releases @core/db))))))}))

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
      [:h1.title.has-text-centered (:project-title config/config)]
      [:h2.subtitle.column.is-8.is-offset-2.has-text-centered
       [:a {:href (str (:base-url config/config) "/feed.xml")} "Subscribe"]
       " / "
       [:a {:href (:project-url config/config)}
        (:project-name config/config)]]]]
    [:div.container.is-8
     [:div.columns
      [:div.column
       (when-let [bugs (intern-id (core/get-unfixed-bugs @core/db))]
         [:section.section
          [:div.container
           [:h1.title "Confirmed bugs"]
           [:div.content
            (for [bug bugs]
              (format-link-fn bug :bug))]]])]
      [:div.column
       (when-let [changes (intern-id (core/get-unreleased-changes @core/db))]
         [:section.section
          [:div.container
           [:h1.title "Future changes"]
           [:div.content
            (for [change changes]
              (format-link-fn change :change))]]])
       (when-let [releases (intern-id (core/get-releases @core/db))]
         [:section.section
          [:div.container
           [:h1.title "Latest releases"]
           [:div.content
            (for [release (take 3 releases)]
              (format-link-fn release :release))]]])]]]
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
   :body   (intern-id (core/get-unfixed-bugs @core/db))})

(defn get-releases [_]
  {:status 200
   :body   (intern-id (core/get-releases @core/db))})

(defn get-changes [_]
  {:status 200
   :body   (intern-id (core/get-unreleased-changes @core/db))})

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
  :start (do (println "Woof monitoring started on localhost:3000")
             (server/run-server handler {:port 3000}))
  :stop (do (when woof-server
              (println "Woof monitoring stopped")
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
