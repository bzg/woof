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
            [clojure.string :as string])
  (:gen-class))

(defn intern-id [m]
  (map (fn [[k v]] (assoc v :id k)) m))

(defn format-link-fn
  [{:keys [from subject date id commit]} type]
  (let [shortcommit  (if (< (count commit) 8) commit (subs commit 0 8))
        mail-title   (format "Visit email sent by %s on %s" from date)
        commit-title (format "Visit commit %s made by %s" shortcommit from)]
    (condp = type
      :bug
      [:p [:a {:href   (format (:mail-url-format config/config) id)
               :title  mail-title
               :target "_blank"}
           subject]]
      :change
      [:p
       [:a {:href   (format (:mail-url-format config/config) id)
            :title  mail-title
            :target "_blank"}
        subject]
       " ("
       [:a {:href   (format (:commit-url-format config/config) commit)
            :title  commit-title
            :target "_blank"}
        shortcommit] ")"]
      :release
      [:p [:a {:href   (format (:mail-url-format config/config) id)
               :title  mail-title
               :target "_blank"}
           subject]])))

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
      [:h1.title.has-text-centered (:title config/config)]
      [:h2.subtitle.column.is-8.is-offset-2.has-text-centered
       [:a {:href (string/replace
                   (:base-url config/config)
                   #"([^/])/*$" "$1/feed/updates")}
        "Subscribe"]
       " / "
       [:a {:href (:project-url config/config)}
        (:project-name config/config)]]]]
    [:div.container.is-8
     [:div.columns
      [:div.column
       (when-let [bugs (->> (core/get-unfixed-bugs @core/db)
                            intern-id
                            (sort-by #(count (:refs %)))
                            reverse)]
         [:section.section
          [:div.container
           [:h1.title "Confirmed bugs"]
           [:div.content
            (for [bug bugs]
              (format-link-fn bug :bug))]]])]
      [:div.column
       (when-let [changes (->> (core/get-unreleased-changes @core/db)
                               intern-id
                               (sort-by :date))]
         [:section.section
          [:div.container
           [:h1.title "Future changes"]
           [:div.content
            (for [change changes]
              (format-link-fn change :change))]]])
       (when-let [releases (->> (core/get-releases @core/db)
                                intern-id
                                (sort-by :date)
                                reverse)]
         [:section.section
          [:div.container
           [:h1.title "Latest releases"]
           [:div.content
            (for [release (take 3 releases)]
              (format-link-fn release :release))]]])]]]
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
       [:p "Made with " ;; FIXME
        [:a {:href ""} "WOOF"]]]]]]))

(defn get-homepage [_]
  {:status 200
   :body   (homepage)})

(defn get-updates [_]
  {:status 200
   :body   (intern-id @core/db)})

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

(mount/defstate woof-server
  :start (do (println "Woof monitoring started on localhost:3000")
             (server/run-server handler {:port 3000}))
  :stop (when woof-server
          (println "Woof monitoring stopped")
          (woof-server :timeout 100)))

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
