(ns bzg.fetch
  (:require [bzg.db :as db]
            [clojure.string :as string]
            [datalevin.core :as d]))

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

(def email-re #"[^<@\s;,]+@[^>@\s;,]+\.[^>@\s;,]+")

(defn- parse-search-string [s]
  (when s
    (let [re-find-in-search
          (fn [s search]
            (peek (re-find
                   (re-pattern
                    (format "(^|\\s)%s(?:%s)?:(%s)" (first s) (subs s 1) email-re))
                   search)))
          from   (re-find-in-search "from" s)
          acked  (re-find-in-search "acked" s)
          owned  (re-find-in-search "owned" s)
          closed (re-find-in-search "closed" s)
          msg    (peek (re-find #"(^|\s)m(?:sg)?:([^\s-]+)" s))
          raw    (-> s
                     (string/replace (re-pattern (format "(^|\\s)[faoc](rom|cked|wned|losed)?:(%s)" email-re)) "")
                     (string/replace #"(^|\s)m(sg)?:([^\s]+)" "")
                     string/trim)]
      {:from      from
       :acked-by  acked
       :owned-by  owned
       :closed-by closed
       :msg-id    msg
       :raw       raw})))

;; FIXME: Use fulltext search for reports?
(defn reports [{:keys [source-id report-type search closed? as-mail]}]
  (let [s-el (parse-search-string search)
        reports
        (->> (d/q
              (if source-id
                `[:find ?e :where [?e ~report-type ?m] [?m :source-id ~source-id]]
                `[:find ?e :where [?e ~report-type ?m]])
              db/db)
             (map #(d/entity db/db (first %)))
             (remove (if-not (= closed? "on") :closed false?))
             (filter #(re-find (re-pattern (or (:raw s-el) ""))
                               (:subject (report-type %))))
             (filter (if-let [f (:from s-el)] #(= (:from (report-type %)) f) seq))
             (filter (if-let [f (:msg-id s-el)] #(= (:message-id (report-type %)) f) seq))
             (filter (if-let [f (:acked-by s-el)] #(= (:from (:acked %)) f) seq))
             (filter (if-let [f (:owned-by s-el)] #(= (:from (:owned %)) f) seq))
             (filter (if-let [f (:closed-by s-el)] #(= (:from (:closed %)) f) seq))
             (map #(assoc % :status (compute-status %)))
             (map #(assoc % :priority (compute-priority %)))
             (map #(assoc % :vote (compute-vote %)))
             (take (or (-> db/config :watch report-type :display-max) 100)))]
    (if as-mail
      (->> reports
           (map #(assoc (get % report-type)
                        :vote (get % :vote)
                        :status (get % :status)
                        :priority (get % :priority)))
           (map add-role))
      reports)))

;; (:vote (first (reports {:source-id   "~bzg/wooff@lists.sr.ht"
;;                         :search      ""
;;                         :closed?     false
;;                         :report-type :request
;;                         :as-mail     false})))

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
