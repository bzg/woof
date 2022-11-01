(ns bzg.fetch
  (:require [bzg.db :as db]
            [datalevin.core :as d]))

;; (defn- add-role [e]
;;   (let [roles (count (select-keys
;;                       (d/entity db/db [:email (:from e)])
;;                       [:admin :maintainer]))]
;;     (merge e {:role roles})))

;; FIXME: Use fulltext search for reports?
(defn reports [{:keys [source-id report-type search as-mail]}]
  (let [reports
        (->> (d/q
              (if source-id
                `[:find ?e :where [?e ~report-type ?m] [?m :source-id ~source-id]]
                `[:find ?e :where [?e ~report-type ?m]])
              db/db)
             (map #(d/entity db/db (first %)))
             ;; Always remove canceled and deleted reports
             ;; (remove :private)
             (remove :deleted)
             (remove :canceled)
             (filter #(re-find (re-pattern (or search ""))
                               (:subject (report-type %))))
             (take (or (-> db/config :watch report-type :display-max) 100)))]
    (if as-mail
      (->> reports
           (map report-type)
           ;; (map #(assoc (d/touch (d/entity db (:db/id %)))
           ;;              :priority (:priority %)))
           ;; FIXME: why does not work for patches only?
           ;; (map add-role)
           )
      reports)))

;; FIXME: refactor?
(defn bugs [& [source-id search]]
  (reports {:source-id   source-id
            :search      search
            :report-type :bug
            :as-mail     true}))

(defn patches [& [source-id search]]
  (reports {:source-id   source-id
            :search      search
            :report-type :patch
            :as-mail     true}))

(defn changes [& [source-id search]]
  (reports {:source-id   source-id
            :search      search
            :report-type :change
            :as-mail     true}))

(defn requests [& [source-id search]]
  (reports {:source-id   source-id
            :search      search
            :report-type :request
            :as-mail     true}))

(defn announcements [& [source-id search]]
  (reports {:source-id   source-id
            :search      search
            :report-type :announcement
            :as-mail     true}))

(defn logs []
  (map first (d/q '[:find (d/pull ?e [*]) :where [?e :log _]] db/db)))

;; FIXME: Handle priority:
;; (remove #(if-let [p (:priority %)] (< p 2) true))
(defn confirmed-bugs [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :bug})
       (filter :acked)
       (remove :closed)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unconfirmed-bugs [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :bug})
       (remove :acked)
       (remove :closed)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unclosed-bugs [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :bug})
       (remove :closed)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn effective-bugs [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :bug})
       (filter :effective)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn approved-patches [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :patch})
       (filter :acked)
       (remove :closed)
       (map :patch)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unapproved-patches [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :patch})
       (remove :acked)
       (remove :closed)
       (map :patch)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn handled-requests [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :request})
       (filter :owned)
       (remove :closed)
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unhandled-requests [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :request})
       (remove :owned)
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn undone-requests [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :request})
       (remove :closed)
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unreleased-changes [& [source-id search]]
  (->> (reports {:source-id source-id :search (or search "") :report-type :change})
       (remove :closed)
       (map :change)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn latest-release [source-id]
  (->> (d/q `[:find ?e :where
              [?e :release ?m]
              [?m :source-id ~source-id]] db/db)
       (map first)
       (map #(d/entity db/db %))
       (remove :closed)
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
       (remove :closed)
       (map :version)
       (into #{})))

;; (defn releases [& [source-id search]]
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

(defn releases [& [source-id search]]
  (reports {:source-id   source-id
            :search      search
            :report-type :release
            :as-mail     true}))

(defn latest-released-changes [& [source-id search]]
  (let [latest-version (:version (latest-release source-id))]
    (->> (reports {:source-id source-id :search (or search "") :report-type :change})
         (filter #(and (= latest-version (:version %))
                       (:released %)))
         (map :change)
         (map #(d/touch (d/entity db/db (:db/id %)))))))

(defn news [& [source-id search]]
  (let [search    (or search "")
        news-show (:news (:show (:ui db/config)))]
    (->> (list
          (when (:announcements news-show)
            (announcements source-id search))
          (when (:releases news-show)
            (releases source-id search))
          (when (:unreleased-changes news-show)
            (unreleased-changes source-id search))
          (when (:latest-released-changes news-show)
            (latest-released-changes source-id search)))
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
