(ns bzg.core
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.walk :as walk]
            [mount.core :as mount]
            [bzg.config :as config]))

(def ^:dynamic db-file-name "db.edn")

(def db
  (atom (or (try (edn/read-string (slurp db-file-name))
                 (catch Exception _ nil))
            {})))

(def db-bug-refs (atom #{}))

(defn- all-bug-refs [db]
  (into #{} (apply clojure.set/union (map :refs (vals db)))))

(add-watch
 db :serialize-bug-refs
 (fn [_ _ _ newdb]
   (reset! db-bug-refs (all-bug-refs newdb))
   (spit db-file-name (pr-str newdb))))

(defn get-unfixed-bugs [db]
  (filter #(and (= (:type (val %)) "bug")
                (not (get (val %) :fixed))) db))

(defn get-unreleased-changes [db]
  (filter #(and (= (:type (val %)) "change")
                (not (get (val %) :released))) db))

(defn get-releases [db]
  (->>
   (filter #(= (:type (val %)) "release") db)
   (take 10)
   (into {})))

(defn- update-bug-refs [id new-refs]
  (loop [refs new-refs
         ref  (some @db-bug-refs refs)]
    (when ref
      (doseq [e @db]
        (when-let [rfs (:refs (val e))]
          (when (rfs ref)
            (swap! db assoc-in [(key e) :refs] (conj rfs id))))))
    (when-let [rest-refs (last (next (partition-by #{ref} refs)))]
      (recur rest-refs
             (some @db-bug-refs rest-refs)))))

(defn- add-change [{:keys [id from subject date-sent]} X-Woof-Change]
  (let [c-specs (string/split X-Woof-Change #"\s")]
    (swap! db conj {id {:type     "change"
                        :from     (:address (first from))
                        :commit   (first c-specs)
                        :versions (into #{} (next c-specs))
                        :subject  subject
                        :date     date-sent}})
    (println from "added a change via" id)))

(defn- add-confirmed-bug [{:keys [id from subject date-sent]} refs]
  (swap! db conj {id {:type    "bug"
                      :from    (:address (first from))
                      :refs    (into #{} (conj refs id))
                      :subject subject
                      :date    date-sent}})
  (println from "added a bug via" id))

(defn- add-fixed-bug [{:keys [id from date-sent]} refs]
  (doseq [e (get-unfixed-bugs @db)]
    (when (some (:refs (val e)) refs)
      (swap! db assoc-in [(key e) :fixed] id)
      (swap! db assoc-in [(key e) :fixed-by] from)
      (swap! db assoc-in [(key e) :fixed-at] date-sent)))
  (println from "marked bug fixed via" id))

(defn- add-release [{:keys [id from subject date-sent]} X-Woof-Release]
  ;; Add the release to the db
  (swap! db conj {id {:type    "release"
                      :from    (:address (first from))
                      :version X-Woof-Release
                      :subject subject
                      :date    date-sent}})
  ;; Mark related changes as released
  (doseq [[k v] (get-unreleased-changes @db)]
    (when ((:versions v) X-Woof-Release)
      (swap! db assoc-in [k :released] X-Woof-Release)))
  (println from "released" X-Woof-Release "via" id))

(defn process-incoming-message
  [{:keys [id from] :as msg}]
  (let [{:keys [X-Woof-Bug X-Woof-Release X-Woof-Change
                X-Original-To References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        refs
        (when (not-empty References)
          (->> (string/split References #"\s")
               (keep not-empty)
               (into #{})))]
    ;; Only process emails if they are sent from the mailing list.
    (when (= X-Original-To (:mailing-list config/config))
      ;; If any email with references contains in its references the id
      ;; of a known bug, add the message-id of this mail to the refs of
      ;; this bug.
      (when refs (update-bug-refs id refs))
      (cond
        ;; Announce a breaking change in the current development
        ;; branches and associate it with future version(s).  Anyone
        ;; can announce a breaking change.
        X-Woof-Change
        (add-change msg X-Woof-Change)
        ;; Or confirm a bug and add it to the registry.  Anyone can
        ;; confirm a bug.
        (and X-Woof-Bug (re-find #"(?i)confirmed" X-Woof-Bug))
        (add-confirmed-bug msg refs)
        ;; Or mark a bug as fixed.  Anyone can mark a bug as fixed.  If an
        ;; email contains X-Woof-Bug: fixed, we scan all refs from this
        ;; email and see if we can find a matching ref in those of a bug,
        ;; and if yes, then we mark the bug as :fixed by the message id.
        (and X-Woof-Bug refs
             (re-find #"(?i)fixed" X-Woof-Bug)
             (some @db-bug-refs refs))
        (add-fixed-bug msg refs)
        ;; Or make a release.
        (and X-Woof-Release
             ;; Only the release manager can announce a release.
             (= (:address (first from))
                (:release-manager config/config)))
        (add-release msg X-Woof-Release)))))

(defn- start-inbox-monitor []
  (let [session      (mail/get-session "imaps")
        mystore      (mail/store "imaps" session
                                 (:server config/config)
                                 (:user config/config)
                                 (:password config/config))
        folder       (mail/open-folder mystore (:folder config/config) :readonly)
        idle-manager (events/new-idle-manager session)]
    (events/add-message-count-listener
     ;; Process incoming mails
     (fn [e] (prn (->> e :messages
                       (map message/read-message)
                       (map process-incoming-message))))
     ;; Don't process deleted mails
     nil
     folder
     idle-manager)
    idle-manager))

(mount/defstate woof-manager
  :start (do (println "Woof manager started")
             (start-inbox-monitor))
  :stop (when woof-manager
          (println "Woof manager stopped")
          (events/stop woof-manager)))
