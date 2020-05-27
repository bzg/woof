(ns bzg.woof
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure.string :as string]
            [clojure.edn :as edn])
  (:gen-class))

(def config
  {:user            (System/getenv "WOOF_MAIL_USER")
   :server          (System/getenv "WOOF_MAIL_SERVER")
   :password        (System/getenv "WOOF_MAIL_PASSWORD")
   :mailing-list    (System/getenv "WOOF_MAILING_LIST")
   :release-manager (System/getenv "WOOF_RELEASE_MANAGER")
   :folder          "inbox"})

(def db (atom (or (try (edn/read-string (slurp "db.edn"))
                       (catch Exception _ nil))
                  {})))

(def db-bug-refs (atom #{}))

(defn all-bug-refs [db]
  (into #{} (apply clojure.set/union (map :refs (vals db)))))

(add-watch
 db :serialize-bug-refs
 (fn [_ _ _ newdb]
   (reset! db-bug-refs (all-bug-refs newdb))
   (spit "db.edn" (pr-str newdb))))

(defn update-bug-refs [id new-refs]
  (loop [refs new-refs
         ref  (some @db-bug-refs refs)]
    (when ref
      (doseq [e @db]
        (if-let [rfs (:refs (val e))]
          (when (rfs ref)
            (swap! db assoc-in [(key e) :refs] (conj rfs id))))))
    (when-let [rest-refs (last (next (partition-by #{ref} refs)))]
      (recur rest-refs
             (some @db-bug-refs rest-refs)))))

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
   (into {})
   (map (fn [[k v]] (assoc v :id k)))))

(defn process-incoming-message
  [{:keys [id from subject date-sent] :as msg}]
  (let [from (:address (first from))
        {:keys [X-Woof-Bug X-Woof-Release X-Woof-Change
                X-Original-To References]}
        (clojure.walk/keywordize-keys
         (apply conj (:headers msg)))
        refs
        (when (not-empty References)
          (->> (string/split References #"\s")
               (keep not-empty)
               (into #{})))]

    ;; Only process emails if they are sent from the mailing list.
    (when (= X-Original-To (:mailing-list config))

      ;; If any email with references contains in its references the id
      ;; of a known bug, add the message-id of this mail to the refs of
      ;; this bug.
      (when refs (update-bug-refs id refs))

      (cond
        ;; Announce a breaking change in the current development
        ;; branches and associate it with future version(s).  Anyone
        ;; can announce a breaking change.
        (not-empty X-Woof-Change)
        (let [c-specs (string/split X-Woof-Change #"\s")]
          (swap! db conj {id {:type     "change"
                              :commit   (first c-specs)
                              :versions (into #{} (next c-specs))
                              :subject  subject
                              :date     date-sent}})
          (println from "added a change via" id))

        ;; Or confirm a bug and add it to the registry.  Anyone can
        ;; confirm a bug.
        (and X-Woof-Bug
             (string/includes? X-Woof-Bug "confirmed"))
        (do (swap! db conj {id {:type    "bug"
                                :refs    (into #{} (conj refs id))
                                :subject subject
                                :date    date-sent}})
            (println from "added a bug via" id))

        ;; Or mark a bug as fixed.  Anyone can mark a bug as fixed.  If an
        ;; email contains X-Woof-Bug: fixed, we scan all refs from this
        ;; email and see if we can find a matching ref in those of a bug,
        ;; and if yes, then we mark the bug as :fixed by the message id.
        (and X-Woof-Bug refs
             (string/includes? X-Woof-Bug "fixed")
             (some @db-bug-refs refs))
        (do (doseq [e (get-unfixed-bugs @db)]
              (when (some (:refs (val e)) refs)
                (swap! db assoc-in [(key e) :fixed] id)))
            (println from "marked bug fixed via" id))

        ;; Or make a release.
        (and (not-empty X-Woof-Release)
             ;; Only the release manager can announce a release.
             (= from (:release-manager config)))
        (do ;; Add the release to the db
          (swap! db conj {id {:type    "release"
                              :version X-Woof-Release
                              :subject subject
                              :date    date-sent}})
          ;; Mark related changes as released
          (doseq [[k v] (get-unreleased-changes @db)]
            (when ((:versions v) X-Woof-Release)
              (swap! db assoc-in [k :released] X-Woof-Release)))
          (println from "released" X-Woof-Release "via" id))))))

(defn -main []
  (let [session      (mail/get-session "imaps")
        mystore      (mail/store "imaps" session
                                 (:server config)
                                 (:user config)
                                 (:password config))
        folder       (mail/open-folder mystore (:folder config) :readonly)
        idle-manager (events/new-idle-manager session)]
    (events/add-message-count-listener
     ;; Process incoming mails
     (fn [e] (prn (->> e :messages
                       (map message/read-message)
                       (map process-incoming-message))))
     ;; Process deleted mails
     nil
     folder
     idle-manager)
    idle-manager)
  (println "Monitoring mails sent from"
           (:mailing-list config) "to" (:user config)))

;; (-main)
