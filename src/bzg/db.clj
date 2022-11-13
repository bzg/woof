(ns bzg.db
  (:require [datalevin.core :as d]
            [bzg.config :as config]
            [aero.core :refer (read-config)]))

;; Set up configuration
(def config (merge config/defaults (read-config "config.edn")))

;; Set up the database
(def schema
  {:defaults {:db/valueType :db.type/string
              :db/unique    :db.unique/identity}
   :log      {:db/valueType :db.type/instant
              :db/unique    :db.unique/identity}

   :confirmed {:db/valueType :db.type/ref}
   :canceled  {:db/valueType :db.type/ref}
   :applied   {:db/valueType :db.type/ref}
   :approved  {:db/valueType :db.type/ref}
   :done      {:db/valueType :db.type/ref}
   :fixed     {:db/valueType :db.type/ref}
   :handled   {:db/valueType :db.type/ref}

   :message-id {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
   :subject    {:db/valueType :db.type/string
                :db/fulltext  true}
   :email      {:db/valueType :db.type/string
                :db/unique    :db.unique/identity}
   :references {:db/valueType   :db.type/string
                :db/cardinality :db.cardinality/many}

   :bug          {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :patch        {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :request      {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :change       {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :announcement {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :blog         {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}
   :release      {:db/valueType :db.type/ref
                  :db/unique    :db.unique/identity}})

(def conn (d/get-conn (:db-dir config) schema))

(def db (d/db conn))
