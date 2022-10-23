(ns bzg.fetch
  (:require [bzg.db :as db]
            [datalevin.core :as d]))

;; (defn- add-role [e]
;;   (let [roles (count (select-keys
;;                       (d/entity db/db [:email (:from e)])
;;                       [:admin :maintainer]))]
;;     (merge e {:role roles})))

;; FIXME: Use fulltext search for reports?
(defn reports [{:keys [list-id report-type search as-mail]}]
  (let [reports
        (->> (d/q
              (if list-id
                `[:find ?e :where [?e ~report-type ?m] [?m :list-id ~list-id]]
                `[:find ?e :where [?e ~report-type ?m]])
              db/db)
             (map #(d/entity db/db (first %)))
             ;; Always remove canceled and deleted reports
             ;; (remove :private)
             (remove :deleted)
             (remove :canceled)
             (filter #(re-find (re-pattern (or search ""))
                               (:subject (report-type %))))
             (take (or (-> (d/entity db/db [:defaults "init"]) :display-max report-type)
                       100)))]
    (if as-mail
      (->> reports
           (map report-type)
           ;; (map #(assoc (d/touch (d/entity db (:db/id %)))
           ;;              :priority (:priority %)))
           ;; FIXME: why does not work for patches only?
           ;; (map add-role)
           )
      reports)))

(defn mails [& [list-id search]]
  (->> (d/q `[:find (d/pull ?e [*])
              :where
              [?e :message-id _]
              [?e :list-id ~list-id]]
            db/db)
       (map first)
       (remove :private)
       (remove :deleted)
       (filter #(re-find (re-pattern (or search "")) (:subject %)))
       (sort-by :date)
       ;; (map add-role)
       (take (-> (d/entity db/db [:defaults "init"]) :display-max :mail))))

;; FIXME: refactor?
(defn bugs [& [list-id search]]
  (reports {:list-id     list-id
            :search      search
            :report-type :bug
            :as-mail     true}))

(defn patches [& [list-id search]]
  (reports {:list-id     list-id
            :search      search
            :report-type :patch
            :as-mail     true}))

(defn changes [& [list-id search]]
  (reports {:list-id     list-id
            :search      search
            :report-type :change
            :as-mail     true}))

(defn requests [& [list-id search]]
  (reports {:list-id     list-id
            :search      search
            :report-type :request
            :as-mail     true}))

(defn announcements [& [list-id search]]
  (reports {:list-id     list-id
            :search      search
            :report-type :announcement
            :as-mail     true}))

(defn logs []
  (map first (d/q '[:find (d/pull ?e [*]) :where [?e :log _]] db/db)))

;; FIXME: Handle priority:
;; (remove #(if-let [p (:priority %)] (< p 2) true))
(defn confirmed-bugs [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :bug})
       (filter :confirmed)
       (remove :fixed)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unconfirmed-bugs [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :bug})
       (remove :confirmed)
       (remove :fixed)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unfixed-bugs [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :bug})
       (remove :fixed)
       (map :bug)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn approved-patches [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :patch})
       (filter :approved)
       (remove :applied)
       (map :patch)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unapproved-patches [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :patch})
       (remove :approved)
       (remove :applied)
       (map :patch)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn handled-requests [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :request})
       (filter :handled)
       (remove :done)
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unhandled-requests [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :request})
       (remove :handled)
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn undone-requests [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :request})
       (remove :done)
       (map :request)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn unreleased-changes [& [list-id search]]
  (->> (reports {:list-id list-id :search (or search "") :report-type :change})
       (remove :released)
       (map :change)
       (map #(d/touch (d/entity db/db (:db/id %))))))

(defn latest-release [list-id]
  (->> (d/q `[:find ?e :where
              [?e :release ?m]
              [?m :list-id ~list-id]] db/db)
       (map first)
       (map #(d/entity db/db %))
       (remove :canceled)
       (map (juxt :release #(hash-map :version (:version %))))
       (map (juxt #(select-keys (d/entity db/db (:db/id (first %)))
                                [:date])
                  second))
       (map #(conj (first %) (second %)))
       (sort-by :date)
       last))

(defn released-versions [list-id]
  (->> (d/q `[:find ?e :where
              [?e :release ?m]
              [?m :list-id ~list-id]] db/db)
       (map first)
       (map #(d/entity db/db %))
       (remove :canceled)
       (map :version)
       (into #{})))

;; (defn releases [& [list-id search]]
;;   (->> (d/q (if list-id
;;               `[:find ?e :where
;;                 [?e :release ?m]
;;                 [?m :list-id ~list-id]]
;;               `[:find ?e :where
;;                 [?e :release ?m]]) db/db)
;;        (map first)
;;        (map #(d/entity db/db %))
;;        (remove :canceled)
;;        (map :release)
;;        (filter #(re-find (re-pattern (or search "")) (:subject %)))
;;        (map #(d/touch (d/entity db/db (:db/id %))))))

(defn releases [& [list-id search]]
  (reports {:list-id     list-id
            :search      search
            :report-type :release
            :as-mail     true}))

(defn latest-released-changes [& [list-id search]]
  (let [latest-version (:version (latest-release list-id))]
    (->> (reports {:list-id list-id :search (or search "") :report-type :change})
         (filter #(and (= latest-version (:version %))
                       (:released %)))
         (map :change)
         (map #(d/touch (d/entity db/db (:db/id %)))))))

(defn news [& [list-id search]]
  (let [search    (or search "")
        news-show (:news (:show (:ui db/config)))]
    (->> (list
          (when (:announcements news-show)
            (announcements list-id search))
          (when (:releases news-show)
            (releases list-id search))
          (when (:unreleased-changes news-show)
            (unreleased-changes list-id search))
          (when (:latest-released-changes news-show)
            (latest-released-changes list-id search)))
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

;; Overview functions

(defn grouped-from-reports [reports]
  (->> reports
       flatten
       (map #(d/entity db/db %))
       (map :from)
       (group-by identity)
       (map (fn [[key val]]
              (let [e (d/entity db/db [:email key])]
                {:email    key
                 :username (:username e)
                 :role     (count (select-keys e [:admin :maintainer]))
                 :home     (:home e)
                 :support  (:support e)
                 :cnt      (count val)})))
       (sort-by :cnt)
       reverse))

(defn overview-bug-contributors [list-id] ;; FIXME: Add search here too?
  (let [bugs-confirmed
        (d/q `[:find ?br ?r :where
               [?b :bug ?br]
               [?b :confirmed ?r]
               [?r :list-id ~list-id]] db/db)
        bugs-fixed
        (d/q `[:find ?br ?r :where
               [?b :bug ?br]
               [?b :fixed ?r]
               [?r :list-id ~list-id]] db/db)]
    ;; FIXME: Factor out
    (grouped-from-reports (concat bugs-confirmed bugs-fixed))))

(defn overview-patch-contributors [list-id]
  (let [patches-approved
        (d/q `[:find ?br ?r :where
               [?b :patch ?br]
               [?b :approved ?r]
               [?r :list-id ~list-id]] db/db)
        patches-applied
        (d/q `[:find ?br ?r :where
               [?b :patch ?br]
               [?b :applied ?r]
               [?r :list-id ~list-id]] db/db)]
    (grouped-from-reports (concat patches-approved patches-applied))))

(defn overview-request-contributors [list-id]
  (let [requests-handled
        (d/q `[:find ?r :where
               [?b :request _]
               [?b :handled ?r]
               [?r :list-id ~list-id]] db/db)
        requests-done
        (d/q `[:find ?r :where
               [?b :request _]
               [?b :done ?r]
               [?r :list-id ~list-id]] db/db)]
    (grouped-from-reports (concat requests-handled requests-done))))

(defn overview-announcement-contributors [list-id]
  (grouped-from-reports
   (concat
    (d/q `[:find ?r :where
           [?b :announcement ?r]
           [?r :list-id ~list-id]
           ;; (not [?b :canceled _])
           ] db/db))))
