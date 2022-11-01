(ns bzg.fetch
  (:require [bzg.db :as db]
            [datalevin.core :as d]))

;; (defn- add-role [e]
;;   (let [roles (count (select-keys
;;                       (d/entity db/db [:email (:from e)])
;;                       [:admin :maintainer]))]
;;     (merge e {:role roles})))

(defn- compute-status [r]
  (let [acked? (if (:acked r) 1 0)
        owned? (if (:owned r) 2 0)]
    (if (:closed r) -1
        (+ acked? owned?))))

(defn- compute-priority [r]
  (let [important? (if (:important r) 1 0)
        urgent?    (if (:urgent r) 2 0)]
    (+ important? urgent?)))

;; FIXME: Use fulltext search for reports?
(defn reports [{:keys [source-id report-type search closed? as-mail]}]
  (let [reports
        (->> (d/q
              (if source-id
                `[:find ?e :where [?e ~report-type ?m] [?m :source-id ~source-id]]
                `[:find ?e :where [?e ~report-type ?m]])
              db/db)
             (map #(d/entity db/db (first %)))
             ;; FIXME: Handle private?
             ;; (remove :private)
             (remove (if-not (= closed? "on") :closed false?))
             (filter #(re-find (re-pattern (or search ""))
                               (:subject (report-type %))))
             (map #(assoc % :status (compute-status %)))
             (map #(assoc % :priority (compute-priority %)))
             (take (or (-> db/config :watch report-type :display-max) 100)))]
    (if as-mail
      (->> reports
           (map #(assoc (get % report-type)
                        :status (get % :status)
                        :priority (get % :priority)))
           ;; FIXME: why does not work for patches only?
           ;; (map add-role)
           )
      reports)))

;; FIXME: refactor?
(defn bugs [& [source-id search closed?]]
  (reports {:source-id   source-id
            :search      search
            :closed?     closed?
            :report-type :bug
            :as-mail     true}))

(defn patches [& [source-id search closed?]]
  (reports {:source-id   source-id
            :search      search
            :closed?     closed?
            :report-type :patch
            :as-mail     true}))

(defn changes [& [source-id search closed?]]
  (reports {:source-id   source-id
            :search      search
            :closed?     closed?
            :report-type :change
            :as-mail     true}))

(defn requests [& [source-id search closed?]]
  (reports {:source-id   source-id
            :search      search
            :closed?     closed?
            :report-type :request
            :as-mail     true}))

(defn announcements [& [source-id search closed?]]
  (reports {:source-id   source-id
            :search      search
            :closed?     closed?
            :report-type :announcement
            :as-mail     true}))

(defn logs []
  (map first (d/q '[:find (d/pull ?e [*]) :where [?e :log _]] db/db)))

(defn confirmed-bugs [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :bug})
       (filter :acked)
       (remove (if (not-empty closed?) :closed false?))
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unconfirmed-bugs [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :bug})
       (remove :acked)
       (remove (if (not-empty closed?) :closed false?))
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unclosed-bugs [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :bug})
       (remove (if (not-empty closed?) :closed false?))
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

(defn urgent-bugs [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "")
                 :closed?     closed?
                 :report-type :bug})
       (filter :urgent)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn approved-patches [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :patch})
       (filter :acked)
       (remove (if (not-empty closed?) :closed false?))
       (map :patch)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unapproved-patches [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :patch})
       (remove :acked)
       (remove (if (not-empty closed?) :closed false?))
       (map :patch)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn handled-requests [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :request})
       (filter :owned)
       (remove (if (not-empty closed?) :closed false?))
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unhandled-requests [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :request})
       (remove :owned)
       (remove (if (not-empty closed?) :closed false?))
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn undone-requests [& [source-id search closed?]]
  (->> (reports {:source-id   source-id :search (or search "") :closed? closed?
                 :report-type :request})
       (remove (if (not-empty closed?) :closed false?))
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

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

(defn news [& [source-id search closed?]]
  (let [search    (or search "")
        news-show (:news (:show (:ui db/config)))]
    (->> (list
          (when (:announcements news-show)
            (announcements source-id search closed?))
          (when (:releases news-show)
            (releases source-id search closed?))
          (when (:unreleased-changes news-show)
            (unreleased-changes source-id search closed?))
          (when (:latest-released-changes news-show)
            (latest-released-changes source-id search closed?)))
         (remove nil?)
         flatten)))

;; Main admin functions

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
       (into #{})))
