(ns bzg.web
  (:require [org.httpkit.server :as server]
            [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.i18n :as i18n]
            [bzg.data :as data]
            [bzg.fetch :as fetch]
            [bzg.db :as db]
            [clojure.string :as string]
            ;; FIXME: Remove in production
            [ring.middleware.reload :as reload]
            [ring.middleware.params :as params]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [integrant.core :as ig]
            [tea-time.core :as tt]
            [selmer.parser :as html]
            [markdown.core :as md]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [taoensso.timbre :as timbre])
  (:gen-class))

(defn- entries-format [{:keys [source-id entries sorting-by]}]
  (let [message-format
        (not-empty (core/archived-message {:source-id source-id}))
        linkify-maybe
        (cond
          message-format
          #(assoc-in % [:link] (format message-format (:message-id %)))
          (:archived-at (first entries))
          #(assoc-in % [:link] (:archived-at %))
          :else identity)]
    (->>
     entries
     (sort-by (condp = sorting-by
                "date"     :date
                "user"     :role
                "status"   :status
                "priority" :priority
                "refs"     :refs
                :priority))
     reverse
     (remove nil?)
     (map linkify-maybe))))

(defn- html-defaults [& [source-id]]
  (let [sources    (:sources db/config)
        source-cfg (or (get (:sources db/config) source-id)
                       (and (= 1 (count sources))
                            (val (first sources))))]
    (-> (merge (:ui db/config)
               (:ui source-cfg)
               {:display (or (:show (:ui source-cfg))
                             (:show (:ui db/config))
                             ;; FIXME: Remove below?
                             ;; (:watch source-cfg)
                             ;; (:watch db/config)
                             )})
        (dissoc :show))))

(defn- with-html-defaults [config-defaults {:keys [source] :as m}]
  (merge (html-defaults (:source-id source))
         {:config config-defaults}
         {:sources (map (fn [[k v]] {:source-id k
                                     :slug      (:slug v)
                                     :doc       (:doc v)})
                        (:sources db/config))}
         m))

(defn- parse-search-string [s]
  (when s
    (let [
          ;; FIXME: Can we safely use email-re for msgid?
          msgid-re   #"[^\s]+"
          version-re #"([<>=]*)([^\s]+)"
          re-find-in-search
          (fn [s search & [re pre]]
            (-> (re-find
                 (re-pattern
                  (format "(?:^|\\s)%s(?:%s)?:(%s)"
                          (first s) (subs s 1) (or re core/email-re)))
                 search)
                (as-> r (if pre (take-last 2 r) (peek r)))))
          from       (re-find-in-search "from" s)
          acked      (re-find-in-search "acked" s)
          owned      (re-find-in-search "owned" s)
          closed     (re-find-in-search "closed" s)
          version    (re-find-in-search "version" s version-re :prefix)
          msg        (re-find-in-search "msg" s msgid-re)
          raw        (-> s
                         (string/replace
                          (re-pattern
                           (format "(?:^|\\s)[faoc](rom|cked|wned|losed)?:%s"
                                   core/email-re)) "")
                         (string/replace #"(?:^|\s)v(ersion)?:[^\s]+" "")
                         (string/replace #"(?:^|\s)m(sg)?:[^\s]+" "")
                         string/trim)]
      {:from      from
       :acked-by  acked
       :owned-by  owned
       :closed-by closed
       :version   (not-empty (replace {"" "="} version))
       :msg-id    msg
       :raw       raw})))

(defn- page-index [page source-id slug-end format-params config-defaults]
  (let [search     (:search format-params)
        search-els (parse-search-string search)
        closed?    (:closed? format-params)
        source     (when source-id
                     {:source-id source-id
                      :slug      (:slug (get (:sources db/config) source-id))})]
    (with-html-defaults config-defaults
      {:source   source
       :search   search
       :closed?  closed?
       :page     (name page)
       :slug-end (or (not-empty slug-end) "index")
       :entries
       ;; FIXME: Confusing use of entries twice?
       (entries-format
        (merge {:entries
                (map #(assoc % :source-slug (core/source-id-to-slug (:source-id %)))
                     (condp = page
                       :index   (fetch/index source-id search-els closed?)
                       :news    (fetch/news source-id search-els closed?)
                       :bug     (fetch/bugs source-id search-els closed?)
                       :patch   (fetch/patches source-id search-els closed?)
                       :request (fetch/requests source-id search-els closed?)))}
               format-params))})))

(defn- page-sources [_ source-id _ _ config-defaults]
  (with-html-defaults config-defaults
    {:page   "sources"
     :source {:source-id source-id
              :slug      (:slug (get (:sources db/config) source-id))}}))

(defn- page-overview [_ source-id _ _ config-defaults]
  (with-html-defaults config-defaults
    {:source (when source-id
               {:source-id source-id
                :slug      (:slug (get (:sources db/config) source-id))})
     :page   "overview"
     ;; TODO: Implement overview features here
     }))

(defn- page-howto [_ source-id _ _ config-defaults]
  (with-html-defaults config-defaults
    {:source (when source-id
               {:source-id source-id
                :slug      (:slug (get (:sources db/config) source-id))})
     :page   "howto"
     :howto  (md/md-to-html-string
              (slurp (io/resource "md/howto.md")))}))

(defn- get-page [page {:keys [query-params path-params uri headers]}]
  (let [format-params   {:search     (or (get query-params "search") "")
                         :closed?    (or (get query-params "closed") "")
                         :sorting-by (get query-params "sorting-by")}
        lang            (if-let [lang (get headers "accept-language")]
                          (subs lang 0 2) "en")
        config-defaults (conj (into {} (d/entity db/db [:defaults "init"]))
                              {:i18n (get i18n/langs (keyword lang))})
        html-page       (condp = page
                          :sources  {:html "/sources.html" :fn page-sources}
                          :howto    {:html "/howto.html" :fn page-howto}
                          :overview {:html "/overview.html" :fn page-overview}
                          {:html "/index.html" :fn page-index})
        slug-end        (peek (re-find #"/([^/]+)$" (or uri "")))
        source-id       (core/slug-to-source-id (:source-slug path-params))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "html/" (:theme db/config) (:html html-page)))
      ((:fn html-page) page source-id slug-end format-params config-defaults))}))

(def handler
  (ring/ring-handler
   (ring/router
    [["/"
      ["" {:get #(get-page :index %)}]
      ["index:format" {:get #(data/get-all-data %)}]
      ["sources" {:get #(get-page :sources %)}]
      ["howto" {:get #(get-page :howto %)}]
      ["overview" {:get #(get-page :overview %)}]
      ["news"
       ["" {:get #(get-page :news %)}]
       [":format" {:get #(data/get-news-data %)}]]
      ["bugs"
       ["" {:get #(get-page :bug %)}]
       [":format" {:get #(data/get-bugs-data %)}]]
      ["patches"
       ["" {:get #(get-page :patch %)}]
       [":format" {:get #(data/get-patches-data %)}]]
      ["requests"
       ["" {:get #(get-page :request %)}]
       [":format" {:get #(data/get-requests-data %)}]]
      ;; List per source
      ["source/:source-slug/"
       ["" {:get #(get-page :index %)}]
       ["index:format" {:get #(data/get-all-data %)}]
       ["howto" {:get #(get-page :howto %)}]
       ["overview" {:get #(get-page :overview %)}]
       ["news"
        ["" {:get #(get-page :news %)}]
        [":format" {:get #(data/get-all-data %)}]]
       ["bugs"
        ["" {:get #(get-page :bug %)}]
        [":format" {:get #(data/get-bugs-data %)}]]
       ["patches"
        ["" {:get #(get-page :patch %)}]
        [":format" {:get #(data/get-patches-data %)}]]
       ["requests"
        ["" {:get #(get-page :request %)}]
        [":format" {:get #(data/get-requests-data %)}]]]]]
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

(def components-config
  (let [server       (:inbox-server db/config)
        user         (:inbox-user db/config)
        password     (:inbox-password db/config)
        folder       (:inbox-folder db/config)
        monitor-opts {:server   server
                      :user     user
                      :password password
                      :folder   folder}]
    {:inbox/monitor  monitor-opts
     :reload/monitor monitor-opts
     :http/service   {:port     (:port db/config)
                      :hostname (:hostname db/config)}}))

(defmethod ig/init-key :http/service [_ {:keys [port hostname]}]
  (server/run-server
   (reload/wrap-reload handler {:dirs ["src" "resources"]})
   {:port port}
   (timbre/info
    (format "Web server started on %s (port %s)" hostname port))))

(defmethod ig/init-key :inbox/monitor [_ opts]
  (core/start-inbox-monitor! opts)
  (timbre/info
   (format "Inbox monitoring started on %s" (:user opts))))

(defmethod ig/init-key :reload/monitor [_ opts]
  (core/reload-monitor! opts))

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
    (ig/init components-config)))

;; (-main)
