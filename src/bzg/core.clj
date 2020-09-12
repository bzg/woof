(ns bzg.core
  (:require [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [clojure-mail.events :as events]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.walk :as walk]
            [mount.core :as mount]
            [bzg.config :as config]
            [tea-time.core :as tt])
  (:import [javax.mail.internet MimeUtility]))

;; Use a dynamic var here to use another value when testing

(def ^:dynamic db-file-name "db.edn")

;;; Core atoms and related functions

(def db
  (atom (or (try (edn/read-string (slurp db-file-name))
                 (catch Exception _ nil))
            {})))

(def db-refs (atom #{}))

(defn- all-refs [db]
  (into #{} (apply clojure.set/union (map :refs (vals db)))))

(add-watch
 db :serialize-refs
 (fn [_ _ _ newdb]
   (reset! db-refs (all-refs newdb))
   (spit db-file-name (pr-str newdb))))

;;; Utility functions

(defn intern-id [m]
  (map (fn [[k v]] (assoc v :id k)) m))

(defn- mime-decode [^String s]
  (when (string? s) (MimeUtility/decodeText s)))

(defn get-from [from]
  (:address (first from)))

(defn get-id [^String id]
  (last (re-find #"^<?(.+[^>])>?$" id)))

(defn get-subject [^String s]
  (-> s
      (string/replace #"^(R[Ee] ?: ?)+" "")
      (string/replace #" *\([^)]+\)" "")
      (string/trim)))

(defn format-link-fn
  [{:keys [from subject date id commit]} what]
  (let [shortcommit  (if (< (count commit) 8) commit (subs commit 0 8))
        mail-title   (format "Visit email sent by %s on %s" from date)
        commit-title (format "Visit commit %s made by %s" shortcommit from)]
    (condp = what
      :change
      [:p
       [:a {:href   (format (:mail-url-format config/woof) id)
            :title  mail-title
            :target "_blank"}
        subject]
       " ("
       [:a {:href   (format (:commit-url-format config/woof) commit)
            :title  commit-title
            :target "_blank"}
        shortcommit] ")"]
      ;; Otherwise use the default for :bug :help :release:
      [:p [:a {:href   (format (:mail-url-format config/woof) id)
               :title  mail-title
               :target "_blank"}
           subject]])))

;;; Core functions to return db entries

(defn get-unfixed-bugs [db]
  (filter #(and (= (:type (val %)) "bug")
                (not (get (val %) :fixed))) db))

(defn get-pending-help [db]
  (filter #(and (= (:type (val %)) "help")
                (not (get (val %) :fixed))) db))

(defn get-unreleased-changes [db]
  (filter #(and (= (:type (val %)) "change")
                (not (get (val %) :released))
                (not (get (val %) :canceled))) db))

(defn get-releases [db]
  (->>
   (filter #(= (:type (val %)) "release") db)
   (into {})))

(defn get-released-versions [db]
  (into #{} (map :version (vals (get-releases db)))))

;;; Core functions to update the db

(defn- update-refs [id new-refs]
  (loop [refs new-refs
         ref  (some @db-refs refs)]
    (when ref
      (doseq [e @db]
        (when-let [rfs (:refs (val e))]
          (when (rfs ref)
            (swap! db assoc-in [(key e) :refs] (conj rfs (get-id id)))))))
    (when-let [rest-refs (last (next (partition-by #{ref} refs)))]
      (recur rest-refs
             (some @db-refs rest-refs)))))

(defn- some-db-refs? [refs]
  (some @db-refs refs))

(defn- add-change [{:keys [id from subject date-sent]} refs X-Woof-Change]
  (let [c-specs   (string/split X-Woof-Change #"\s")
        commit    (first c-specs)
        versions  (into #{} (next c-specs))
        released  (get-released-versions @db)
        true-from (get-from from)
        true-id   (get-id id)]
    (cond
      (and released (some released versions))
      (format "%s tried to add a change against a past release, ignoring %s"
              true-from true-id)
      (empty? versions)
      (format "%s tried to add a change with a wrong header format, ignoring %s"
              true-from true-id)
      :else
      (do (swap! db conj {true-id {:type     "change"
                                   :from     true-from
                                   :commit   commit
                                   :refs     (into #{} (conj refs true-id))
                                   :versions versions
                                   :subject  (get-subject subject)
                                   :date     date-sent}})
          (format "%s added a change for version %s via %s"
                  true-from (first versions) true-id)))))

(defn- cancel-change [{:keys [id from date-sent]} refs]
  (let [true-from (get-from from)
        true-id   (get-id id)]
    ;; Prevent release when not from the release manager
    (doseq [e (get-unreleased-changes @db)]
      (when (some (:refs (val e)) refs)
        (swap! db assoc-in [(key e) :canceled] true-id)
        (swap! db assoc-in [(key e) :canceled-by] true-from)
        (swap! db assoc-in [(key e) :canceled-at] date-sent)))
    (format "%s canceled a change announcement via %s" true-from true-id)))

(defn- add-entry [{:keys [id from subject date-sent] :as msg} refs what]
  (let [{:keys [X-Woof-Help]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        X-Woof-Help  (mime-decode X-Woof-Help)
        what-type    (name what)
        what-msg     (condp = what :bug "%s added a bug via %s"
                            "%s added a call for help via %s")
        what-subject (condp = what :bug (get-subject subject)
                            X-Woof-Help)
        true-from    (get-from from)
        true-id      (get-id id)]
    (swap! db conj {true-id {:type    what-type
                             :from    true-from
                             :refs    (into #{} (conj refs true-id))
                             :subject what-subject
                             :date    date-sent}})
    (format what-msg true-from true-id)))

(defn- add-bug [msg refs]
  (add-entry msg refs :bug))

(defn- add-help [msg refs]
  (add-entry msg refs :help))

(defn- fix-entry [{:keys [id from date-sent]} refs what]
  (let [msg       (condp = what :bug "%s marked bug fixed via %s"
                         "%s marked help fixed via %s")
        get-what  (condp = what :bug get-unfixed-bugs 
                         get-pending-help)
        true-from (get-from from)
        true-id   (get-id id)]
    (doseq [e (get-what @db)]
      (when (some (:refs (val e)) refs)
        (swap! db assoc-in [(key e) :fixed] true-id)
        (swap! db assoc-in [(key e) :fixed-by] true-from)
        (swap! db assoc-in [(key e) :fixed-at] date-sent)))
    (format msg true-from true-id)))

(defn- fix-bug [msg refs]
  (fix-entry msg refs :bug))

(defn- cancel-help [msg refs]
  (fix-entry msg refs :help))

(defn- add-release [{:keys [id from subject date-sent]} X-Woof-Release]
  (let [released  (get-released-versions @db)
        true-from (get-from from)
        true-id   (get-id id)]
    (cond
      ;; Prevent release when not from the release manager
      (not (= true-from (:release-manager config/woof)))
      (format "%s tried to release via %s while not being release manager"
              true-from true-id)
      ;; Prevent duplicate release
      (and released (some released #{X-Woof-Release}))
      (format "%s tried to release with a known version number via %s"
              true-from true-id)
      ;; Add the release to the db
      :else
      (do (swap! db conj {true-id {:type    "release"
                                   :from    true-from
                                   :version X-Woof-Release
                                   :subject (get-subject subject)
                                   :date    date-sent}})
          ;; Mark related changes as released
          (doseq [[k v] (get-unreleased-changes @db)]
            (when ((:versions v) X-Woof-Release)
              (swap! db assoc-in [k :released] X-Woof-Release)))
          (format "%s released %s via %s" true-from X-Woof-Release true-id)))))

(defn process-incoming-message
  [{:keys [id from] :as msg}]
  (let [{:keys [X-Woof-Bug X-Woof-Release X-Woof-Change X-Woof-Help
                X-Original-To X-BeenThere To References]}
        (walk/keywordize-keys (apply conj (:headers msg)))
        X-Woof-Help (mime-decode X-Woof-Help)
        refs
        (when (not-empty References)
          (->> (string/split References #"\s")
               (keep not-empty)
               (map get-id)
               (into #{})))]
    ;; Only process emails if they are sent directly from the release
    ;; manager or from the mailing list.
    (when (or (= (get-from from) (:release-manager config/woof))
              (some (into #{} (list X-Original-To X-BeenThere
                                    (when (string? To)
                                      (last (re-find #"^.*<(.*[^>])>.*$" To)))))
                    (into #{} (list (:mailing-list config/woof)))))
      ;; If any email with references contains in its references the id
      ;; of a known bug, add the message-id of this mail to the refs of
      ;; this bug.
      (when refs (update-refs (get-id id) refs))
      (cond
        ;; Confirm a bug and add it to the registry.  Anyone can
        ;; confirm a bug.
        (and X-Woof-Bug
             (re-find (:confirmed config/actions-regexps)
                      (string/trim X-Woof-Bug)))
        (add-bug msg refs)
        ;; Mark a bug as fixed.  Anyone can mark a bug as fixed.  If
        ;; an email contains X-Woof-Bug: fixed, we scan all refs from
        ;; this email and see if we can find a matching ref in those
        ;; of a bug, and if yes, then we mark the bug as :fixed by the
        ;; message id.
        (and X-Woof-Bug refs
             (re-find (:closed config/actions-regexps)
                      (string/trim X-Woof-Bug))
             (some-db-refs? refs))
        (fix-bug msg refs)
        ;; Call for help.  Anyone can call for help.
        (and X-Woof-Help
             (not (re-find (:closed config/actions-regexps)
                           (string/trim X-Woof-Help))))
        (add-help msg refs)
        ;; Cancel a call for help.  Anyone can call for help.
        (and X-Woof-Help
             (re-find (:closed config/actions-regexps)
                      (string/trim X-Woof-Help))
             (some-db-refs? refs))
        (cancel-help msg refs)
        ;; Mark a change as canceled.  Anyone can mark a change as
        ;; canceled.
        (and X-Woof-Change refs
             (re-find (:closed config/actions-regexps)
                      (string/trim X-Woof-Change))
             (some-db-refs? refs))
        (cancel-change msg refs)
        ;; Announce a breaking change in the current development
        ;; branches and associate it with future version(s).  Anyone
        ;; can announce a breaking change.
        X-Woof-Change
        (add-change msg refs X-Woof-Change)
        ;; Make a release.  Only the release manager can make a
        ;; release.
        X-Woof-Release
        (add-release msg X-Woof-Release)))))

;;; Monitoring
(def woof-monitor (atom nil))

(defn- start-inbox-monitor! []
  (reset!
   woof-monitor
   (let [session      (mail/get-session "imaps")
         mystore      (mail/store "imaps" session
                                  (:server config/woof)
                                  (:user config/woof)
                                  (:password config/woof))
         folder       (mail/open-folder mystore (:folder config/woof) :readonly)
         idle-manager (events/new-idle-manager session)]
     (events/add-message-count-listener
      ;; Process incoming mails
      (fn [e]
        (doall
         (map #(do (println %)
                   (spit "logs.txt" (str % "\n") :append true))
              (remove nil?
                      (->> e :messages
                           (map message/read-message)
                           (map process-incoming-message))))))
      ;; Don't process deleted mails
      nil
      folder
      idle-manager)
     idle-manager)))

(defn- start-tasks! []
  (tt/every! 1200 ;; 20 minutes
             (fn []
               (try 
                 (events/stop @woof-monitor)
                 (catch Exception _ nil))
               (start-inbox-monitor!))))

(mount/defstate woof-manager
  :start (do (start-tasks!)
             (println "Woof started"))
  :stop (when woof-manager
          (events/stop woof-monitor)
          (println "Woof stopped")))
