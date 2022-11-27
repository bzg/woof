(ns bzg.fetch
  (:require [clojure.string :as string]
            [bzg.db :as db]
            [datalevin.core :as d]
            [java-time.api :as jt]
            [version-clj.core :as v]))

(defn- add-role [e]
  (let [roles (count (select-keys
                      (d/entity db/db [:email (:from e)])
                      [:admin :maintainer]))]
    (assoc e :role roles)))

(defn- compute-status [r]
  (let [acked? (if (:acked r) 1 0)
        owned? (if (:owned r) 2 0)]
    (if (:closed r) -1
        (+ acked? owned?))))

(defn- compute-priority [r]
  (let [important? (if (:important r) 1 0)
        urgent?    (if (:urgent r) 2 0)]
    (+ important? urgent?)))

(defn- compute-vote [r]
  (let [up   (count (:up r))
        down (count (:down r))]
    (if-not (zero? (+ up down))
      (str (/ up (+ up down)))
      "N/A")))

(defn version-search-true? [cp-name v-searched version]
  (if-not version
    false
    (let [cp-fn (condp = cp-name
                  "="  (fn [v1 v2] (= (v/version-compare v1 v2) 0))
                  "<"  v/older?
                  "<=" v/older-or-equal?
                  ">"  v/newer?
                  ">=" v/newer-or-equal?)]
      (cp-fn version v-searched))))

;; FIXME: Use fulltext search for reports?
(defn reports [{:keys [source-id report-type search closed? as-mail]}]
  (let [report-type-cfg (-> db/config :watch report-type)
        m-any           (fn [to-split cmp]
                          (if to-split
                            (when-let [f (string/split to-split #"[;,]")]
                              (some (into #{} f) (list cmp)))
                            true))
        reports
        (->> (d/q
              (if source-id
                `[:find ?e :where [?e ~report-type ?m] [?m :source-id ~source-id]]
                `[:find ?e :where [?e ~report-type ?m]])
              db/db)
             (map #(d/entity db/db (first %)))
             (remove (if-not (= closed? "on") :closed false?))
             (filter #(re-find (re-pattern (or (:raw search) ""))
                               (:subject (report-type %))))
             (filter (if-let [[cp v] (:version search)]
                       #(version-search-true? cp v (:version %))
                       seq))
             (filter #(m-any (:from search) (:from (report-type %))))
             (filter #(m-any (:msg-id search) (:message-id (report-type %))))
             (filter #(m-any (:acked-by search) (:from (:acked %))))
             (filter #(m-any (:owned-by search) (:from (:owned %))))
             (filter #(m-any (:closed-by search) (:from (:closed %))))
             (map #(assoc % :status (compute-status %)))
             (map #(assoc % :priority (compute-priority %)))
             (map #(assoc % :vote (compute-vote %)))
             (filter (if-let [newer-than (:display-newer-than report-type-cfg)]
                       #(let [d (jt/instant (:date (report-type %)))]
                          (jt/before? (jt/instant)
                                      (jt/plus d (jt/days newer-than))))
                       seq))
             (take (or (:display-max report-type-cfg) 100)))]
    (if as-mail
      (->> reports
           (map #(assoc (get % report-type)
                        :vote (get % :vote)
                        :status (get % :status)
                        :priority (get % :priority)))
           (map add-role))
      reports)))

(defn- reports-as-mail [report-type & [source-id search closed?]]
  (reports {:source-id   source-id
            :search      search
            :closed?     closed?
            :report-type report-type
            :as-mail     true}))

;; Fetch bugs/patches/changes/announcements/requests as mails

(defn bugs [& [source-id search closed?]]
  (reports-as-mail :bug source-id search closed?))

(defn patches [& [source-id search closed?]]
  (reports-as-mail :patch source-id search closed?))

(defn changes [& [source-id search closed?]]
  (reports-as-mail :change source-id search closed?))

(defn announcements [& [source-id search closed?]]
  (reports-as-mail :announcement source-id search closed?))

(defn blogs [& [source-id search closed?]]
  (reports-as-mail :blog source-id search closed?))

(defn requests [& [source-id search closed?]]
  (reports-as-mail :request source-id search closed?))

(defn logs []
  (map first (d/q '[:find (d/pull ?e [*]) :where [?e :log _]] db/db)))

;; Functions to fetch (un)acked/(un)owned/(un)closed bugs/patches/requests

(defn positive-reports [report-type status & [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type report-type})
       (filter status)
       (remove (if (not-empty closed?) :closed false?))
       (map report-type)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn negative-reports [report-type status & [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type report-type})
       (remove status)
       (remove (if (not-empty closed?) :closed false?))
       (map report-type)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn acked-bugs [& [source-id search closed?]]
  (positive-reports :bug :acked source-id search closed?))

(defn unacked-bugs [& [source-id search closed?]]
  (negative-reports :bug :acked source-id search closed?))

(defn owned-bugs [& [source-id search closed?]]
  (positive-reports :bug :owned source-id search closed?))

(defn unowned-bugs [& [source-id search closed?]]
  (negative-reports :bug :owned source-id search closed?))

(defn closed-bugs [& [source-id search closed?]]
  (positive-reports :bug :closed source-id search closed?))

(defn unclosed-bugs [& [source-id search closed?]]
  (negative-reports :bug :closed source-id search closed?))

(defn acked-patches [& [source-id search closed?]]
  (positive-reports :patch :acked source-id search closed?))

(defn unacked-patches [& [source-id search closed?]]
  (negative-reports :patch :acked source-id search closed?))

(defn owned-patches [& [source-id search closed?]]
  (positive-reports :patch :owned source-id search closed?))

(defn unowned-patches [& [source-id search closed?]]
  (negative-reports :patch :owned source-id search closed?))

(defn closed-patches [& [source-id search closed?]]
  (positive-reports :patch :closed source-id search closed?))

(defn unclosed-patches [& [source-id search closed?]]
  (negative-reports :patch :closed source-id search closed?))

(defn acked-requests [& [source-id search closed?]]
  (positive-reports :request :acked source-id search closed?))

(defn unacked-requests [& [source-id search closed?]]
  (negative-reports :request :acked source-id search closed?))

(defn owned-requests [& [source-id search closed?]]
  (positive-reports :request :owned source-id search closed?))

(defn unowned-requests [& [source-id search closed?]]
  (negative-reports :request :owned source-id search closed?))

(defn closed-requests [& [source-id search closed?]]
  (positive-reports :request :closed source-id search closed?))

(defn unclosed-requests [& [source-id search closed?]]
  (negative-reports :request :closed source-id search closed?))

;; Special functions

(defn unreleased-changes [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :change})
       (remove (if (not-empty closed?) :closed false?))
       (map :change)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn latest-release [source-id closed?]
  (->> (d/q `[:find ?e :where
              [?e :release ?m]
              [?m :source-id ~source-id]] db/db)
       (map first)
       (map #(d/entity db/db %))
       (remove (if (not-empty closed?) :closed false?))
       (map (juxt :release #(hash-map :version (:version %))))
       (map (juxt #(select-keys (d/entity db/db (:db/id (first %)))
                                [:date])
                  second))
       (map #(conj (first %) (second %)))
       (sort-by :date)
       last))

(defn released-versions [source-id]
  (->> (d/q `[:find ?e :where
              [?e :release ?m]
              [?m :source-id ~source-id]] db/db)
       (map first)
       (map #(d/entity db/db %))
       ;; FIXME: conditionnally remove :closed?
       (remove :closed)
       (map :version)
       (into #{})))

;; (defn releases [& [source-id search closed]]
;;   (->> (d/q (if source-id
;;               `[:find ?e :where
;;                 [?e :release ?m]
;;                 [?m :source-id ~source-id]]
;;               `[:find ?e :where
;;                 [?e :release ?m]]) db/db)
;;        (map first)
;;        (map #(d/entity db/db %))
;;        (remove :canceled)
;;        (map :release)
;;        (filter #(re-find (re-pattern (or search "")) (:subject %)))
;;        (map #(d/touch (d/entity db/db (:db/id %))))))

(defn releases [& [source-id search closed?]]
  (reports {:source-id   source-id
            :search      search
            :closed?     closed?
            :report-type :release
            :as-mail     true}))

(defn latest-released-changes [& [source-id search closed?]]
  (let [latest-version (:version (latest-release source-id closed?))]
    (->> (reports {:source-id source-id :search      (or search "")
                   :closed?   closed?   :report-type :change})
         (filter #(and (= latest-version (:version %))
                       (:released %)))
         (map :change)
         (map #(d/touch (d/entity db/db (:db/id %)))))))

;; Main news function

(defn index [& [source-id search closed?]]
  (let [search (or search "")]
    (->> (list
          (releases source-id search closed?)
          (unreleased-changes source-id search closed?)
          (latest-released-changes source-id search closed?)
          (announcements source-id search closed?)
          (blogs source-id search closed?)
          (bugs source-id search closed?)
          (patches source-id search closed?)
          (requests source-id search closed?))
         (remove nil?)
         flatten)))

(defn news [& [source-id search closed?]]
  (let [search    (or search "")
        news-show (:news (:show (:ui db/config)))]
    (->> (list
          (when (:announcements news-show)
            (announcements source-id search closed?))
          (when (:blogs news-show)
            (blogs source-id search closed?))
          (when (:releases news-show)
            (releases source-id search closed?))
          (when (:unreleased-changes news-show)
            (unreleased-changes source-id search closed?))
          (when (:latest-released-changes news-show)
            (latest-released-changes source-id search closed?)))
         (remove nil?)
         flatten)))

;; Aliases

(def confirmed-bugs acked-bugs)
(def unconfirmed-bugs unacked-bugs)
(def handled-bugs owned-bugs)
(def unhandled-bugs unowned-bugs)
(def fixed-bugs closed-bugs)
(def canceled-bugs closed-bugs)
(def unfixed-bugs unclosed-bugs)
(def uncanceled-bugs unclosed-bugs)

(def approved-patches acked-patches)
(def unapproved-patches unacked-patches)
(def handled-patches owned-patches)
(def unhandled-patches unowned-patches)
(def applied-patches closed-patches)
(def canceled-patches closed-patches)
(def unapplied-patches unclosed-patches)
(def uncanceled-patches unclosed-patches)

(def approved-requests acked-requests)
(def unapproved-requests unacked-requests)
(def handled-requests owned-requests)
(def unhandled-requests unowned-requests)
(def done-requests closed-requests)
(def canceled-requests closed-requests)
(def uncanceled-requests unclosed-requests)
(def undone-requests unclosed-requests)

;; For tests

(defn urgent-bugs [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "")
                 :closed?     closed?
                 :report-type :bug})
       (filter :urgent)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn effective-bugs [& [source-id search]]
  (->> (reports {:source-id   source-id :search (or search "")
                 ;; Always search through all bugs:
                 :closed?     "on"
                 :report-type :bug})
       (filter :effective)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

;; Admin functions

(comment
  (defn persons []
    (->> (d/q '[:find ?p :where [?p :email ?_]] db/db)
         (map first)
         (map #(d/entity db/db %))))

  (defn admins []
    (->> (filter :admin (persons))
         (map :email)
         (into #{})))

  (defn maintainers []
    (->> (filter :admin (persons))
         (map :email)
         (into #{}))))
