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
            [clojure.edn :as edn]
            [tea-time.core :as tt])
  (:gen-class))

(defn- format-date [d]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:MM") d))

(defn- filter-summary [query-params db]
  (if (seq (:filter query-params))
    (filter #(re-find (re-pattern (str "(?i)" (:filter query-params)))
                      (:summary %))
            db)
    db))

(defn default [query-params link content]
  (h/html5
   {:lang "en"}
   [:head
    [:title "Woof! Watch Over Our Folders"]
    [:meta {:charset "utf-8"}]
    [:meta {:name "keywords" :content "Woof! Watch Over Our Folders"}]
    [:meta {:name "description" :content "Woof! Watch Over Our Folders"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1, shrink-to-fit=yes"}]
    (h/include-css "https://cdn.jsdelivr.net/npm/bulma@0.9.0/css/bulma.min.css")]
   [:body
    [:section.hero
     [:div.hero-body {:style "padding: 2rem 1.5rem"}
      [:h1.title.has-text-centered
       {:style "margin-bottom: 1em;"}
       (:title config/woof)]
      [:h2.subtitle.column.is-8.is-offset-2.has-text-centered
       {:style "margin-bottom: 0;"}
       [:a {:href  (string/replace
                    (:base-url config/woof)
                    #"([^/])/*$" "$1/feed/updates")
            :title "Subscribe to a RSS feed with all updates"}
        "Subscribe (RSS)"]
       " — "
       [:a {:href (:project-url config/woof)}
        (:project-name config/woof)]
       " — "
       link]
      [:h3.column.is-8.is-offset-2.has-text-centered
       [:a {:href "/#changes"} "Changes"] " — "
       [:a {:href "/#help"} "Help"] " — "
       [:a {:href "/#bugs"} "Bugs"] " — "
       [:a {:href "/#releases"} "Releases"]]
      [:div.column.is-4.is-offset-4
       [:div.level
        [:form.level-item {:action "/"}
         [:input.input {:type        "text"
                        :name        "s"
                        :value       (:filter query-params)
                        :placeHolder "Leave empty to remove filters" }]
         [:button.button.is-info {:type "submit"} "Filter"]]
        [:form
         {:action "/"}
         [:input {:type "hidden" :name "s" :value ""}]
         [:button.button.is-danger.is-light {:type "submit"} "❌"]]]]]]
    content
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
        [:a {:href "https://github.com/bzg/woof"} "Woof!"]]]]]]))

(defn homepage [query-params]
  (default
   query-params
   [:a {:href  "https://github.com/bzg/woof#usage"
        :title "How to use Woof! to update this page?"} "Howto"]
   [:div.container
    [:section.section {:style "padding: 1.5rem 1.0rem"}
     [:div.container
      [:h1#changes.title
       [:span "Upcoming changes "
        [:a.tag.is-info.is-light {:href "/feed/changes"} "RSS"] " "
        [:a.tag.is-success.is-light {:href "/data/changes"} "JSON"]]]
      (if-let [changes (->> (core/get-unreleased-changes @core/db)
                            core/intern-id
                            (sort-by :date)
                            (filter-summary query-params)
                            not-empty)]
        [:div.content
         (for [change changes]
           (core/format-link-fn change :change))]
        [:p "No upcoming change."])]]
    [:section.section {:style "padding: 1.5rem 1.0rem"}
     [:div.container
      [:h1#help.title
       [:span "Help requests "
        [:a.tag.is-info.is-light {:href "/feed/help"} "RSS"] " "
        [:a.tag.is-success.is-light {:href "/data/help"} "JSON"]]]
      (if-let [helps (->> (core/get-pending-help @core/db)
                          core/intern-id
                          (sort-by
                           (if (= (:sort-help-by query-params) "date")
                             :date
                             #(count (:refs %))))
                          (filter-summary query-params)
                          reverse
                          not-empty)]
        [:div.table-container
         [:table.table.is-hoverable.is-fullwidth.is-striped
          [:thead
           [:tr
            [:th "Summary"]
            [:th {:width "15%"}
             [:a {:href "/?sort-help-by=date" :title "Sort help requests by date"}
              "Date"]]
            [:th {:width "5%"}
             [:a {:href "/?sort-help-by=refs" :title "Sort help requests by number of references"}
              "Refs"]]]]
          [:tbody
           (for [help helps]
             [:tr
              [:td (core/format-link-fn help :bug)]
              [:td [:p (format-date (:date help))]]
              [:td [:p (str (count (:refs help)))]]])]]]
        [:p "No help has been requested so far."])]]
    [:section.section {:style "padding: 1.5rem 1.0rem"}
     [:div.container
      [:h1#bugs.title
       [:span "Confirmed bugs "
        [:a.tag.is-info.is-light {:href "/feed/bugs"} "RSS"] " "
        [:a.tag.is-success.is-light {:href "/data/bugs"} "JSON"]]]
      (if-let [bugs (->> (core/get-unfixed-bugs @core/db)
                         core/intern-id
                         (sort-by
                          (if (= (:sort-bugs-by query-params) "date")
                            :date
                            #(count (:refs %))))
                         reverse
                         (filter-summary query-params)
                         not-empty)]
        [:div.table-container
         [:table.table.is-hoverable.is-fullwidth.is-striped
          [:thead
           [:tr
            [:th "Summary"]
            [:th {:width "15%"}
             [:a {:href "/?sort-bugs-by=date" :title "Sort bugs by date"}
              "Date"]]
            [:th {:width "5%"}
             [:a {:href "/?sort-bugs-by=refs" :title "Sort bugs by number of references"}
              "Refs"]]]]
          [:tbody
           (for [bug bugs]
             [:tr
              [:td (core/format-link-fn bug :bug)]
              [:td [:p (format-date (:date bug))]]
              [:td [:p (str (count (:refs bug)))]]])]]]
        [:p "No confirmed bug."])]]
    [:section.section {:style "padding: 1.5rem 1.0rem"}
     [:div.container
      [:h1#releases.title
       [:span "Latest releases "
        [:a.tag.is-info.is-light {:href "/feed/releases"} "RSS"] " "
        [:a.tag.is-success.is-light {:href "/data/releases"} "JSON"]]]
      (if-let [releases (->> (core/get-releases @core/db)
                             core/intern-id
                             (sort-by :date)
                             reverse
                             not-empty)]
        [:div.content
         (for [release (take 3 releases)]
           (core/format-link-fn release :release))]
        [:p "No release."])]]]))

(defn get-homepage [{:keys [query-params]}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (homepage {:sort-bugs-by (get query-params "sort-bugs-by")
                       :sort-help-by (get query-params "sort-help-by")
                       :filter       (get query-params "s")})})

(defn- get-data [what]
  {:status 200
   :body   (core/intern-id
            (apply (condp = what
                     :updates  identity
                     :bugs     core/get-unfixed-bugs
                     :helps    core/get-pending-help
                     :releases core/get-releases
                     :changes  core/get-unreleased-changes)
                   [@core/db]))})

(defn get-updates [_] (get-data :updates))
(defn get-bugs [_] (get-data :bugs))
(defn get-helps [_] (get-data :helps))
(defn get-releases [_] (get-data :releases))
(defn get-changes [_] (get-data :changes))

(def handler
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [params] (get-homepage params))}]
     ["/data"
      ["/updates" {:get get-updates}]
      ["/bugs" {:get get-bugs}]
      ["/help" {:get get-helps}]
      ["/changes" {:get get-changes}]
      ["/releases" {:get get-releases}]]
     ["/feed"
      ["/updates" {:get feeds/feed-updates}]
      ["/bugs" {:get feeds/feed-bugs}]
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

(let [port (edn/read-string (:port config/woof))]
  (mount/defstate woof-server
    :start (do (println (format "Woof! monitoring on localhost:%s started" port))
               (server/run-server handler {:port port}))
    :stop (when woof-server
            (println (format "Woof! monitoring on localhost:%s stopped" port))
            (woof-server :timeout 100))))

(defn -main []
  (tt/start!)
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
