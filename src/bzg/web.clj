;; Copyright (c) 2022-2023 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns bzg.web
  (:require [reitit.ring :as ring]
            [bzg.core :as core]
            [bzg.i18n :as i18n]
            [bzg.data :as data]
            [bzg.fetch :as fetch]
            [bzg.db :as db]
            [clojure.string :as string]
            [ring.middleware.params :as params]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [selmer.parser :as html]
            [selmer.filters :as filters]
            [markdown.core :as md]
            [clojure.java.io :as io]
            [datalevin.core :as d]))

(filters/add-filter! :concat #(string/join "," %))

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
                "refs"     :refs-count
                ;; See comment in `compute-vote` on why `read-string`
                "vote"     #(read-string (:vote %))
                "related"  #(count (:related-refs %))
                :priority))
     reverse
     (remove nil?)
     (map linkify-maybe))))

(defn- html-defaults [& [source-id]]
  (let [sources    (filter #(not (:hidden (val %))) (:sources db/config))
        source-cfg (or (get sources source-id source-id)
                       (and (= 1 (count sources))
                            (val (first sources))))]
    (-> (merge (:ui db/config)
               (:ui source-cfg)
               {:display (or (:pages (:ui source-cfg))
                             (:pages (:ui db/config)))})
        (dissoc :pages))))

(defn- with-html-defaults [config-defaults {:keys [source] :as m}]
  (merge (html-defaults (:source-id source))
         {:config (merge config-defaults
                         {:admin-address (:admin-address db/config)})}
         {:sources (->> (:sources db/config)
                        (map (fn [[k v]]
                               (when-not (:hidden v)
                                 {:source-id k
                                  :slug      (:slug v)
                                  :doc       (:doc v)})))
                        (remove nil?))}
         m))

(def emails-re (re-pattern (str "(?:" core/email-re "[;,]?)+")))

(defn- parse-search-string [s]
  (when s
    (let [
          ;; TODO: Could we safely use email-re as msgid-re?
          msgid-re   #"[^\s]+"
          version-re #"([<>=]*)([^\s]+)"
          re-find-in-search
          (fn [s search & [re pre]]
            (-> (re-find
                 (re-pattern
                  (format "(?:^|\\s)%s(?:%s)?:(%s)"
                          (first s) (subs s 1) (or re emails-re)))
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
                                   emails-re)) "")
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
                      :slug      (:slug (get (:sources db/config) source-id))})
        ui-config  (if source-id
                     (:ui (get (:sources db/config) source-id))
                     (:ui db/config))]
    (with-html-defaults config-defaults
      {:source   source
       :search   search
       :closed?  closed?
       :page     (name page)
       :columns  (or (:columns (page (:pages ui-config)))
                     #{:priority :vote :from :date :related-refs :refs-count :status})
       :slug-end (or (not-empty slug-end) "index")
       :entries
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

(defn- page-404 [_ source-id _ _ config-defaults]
  (with-html-defaults config-defaults
    {:source (when source-id
               {:source-id source-id
                :slug      (:slug (get (:sources db/config) source-id))})
     :page   "404"}))

(defn- page-howto [_ source-id _ _ config-defaults]
  (with-html-defaults config-defaults
    {:page   "howto"
     :source (when source-id
               (let [src-cfg (get (:sources db/config) source-id)
                     strj    (fn [m] (string/join ", " m))]
                 {:source-id source-id
                  :slug      (:slug src-cfg)
                  :watch
                  (->> (or (:watch src-cfg) (:watch db/config))
                       (map (fn [[k v]]
                              {:report   (name k)
                               :prefix   (strj (:subject-prefix v))
                               :match    (strj (:subject-match v))
                               :doc      (:doc v)
                               :triggers (strj (flatten (vals (:triggers v))))})))}))
     :howto  (md/md-to-html-string
              (slurp (io/resource "md/howto.md")))}))

(defn- get-patch-body [{:keys [msgid]}]
  (let [msg        (ffirst (d/q `[:find ?e :where
                                  [?e :message-id ~msgid]
                                  [?e :patch-body]]
                                db/db))
        patch-body (not-empty (:patch-body (d/entity db/db msg)))]
    {:status  200
     :headers {"Content-Type" "text/plain"}
     :body    (or patch-body "Can't find a patch here")}))

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
                          :404      {:html "/404.html" :fn page-404}
                          {:html "/index.html" :fn page-index})
        slug-end        (or  (peek (re-find #"/([^/]+)$" (or uri ""))) "")
        source-id       (core/slug-to-source-id (:source-slug path-params))
        theme           (if (re-find #"Emacs" (or (get headers "user-agent") ""))
                          "plain"
                          (:theme db/config))]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body
     (html/render-file
      (io/resource (str "themes/" theme (:html html-page)))
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
      ;; Patch body
      ["patch/:msgid"
       ["" {:get #(get-patch-body %)}]]
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
    {:not-found (fn [_] (get-page :404 nil))})
   {:middleware
    [parameters/parameters-middleware
     #(wrap-cors
       %
       :access-control-allow-origin [#"^*$"]
       :access-control-allow-methods [:get])]}))
