;; Copyright (c) 2022-2023 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

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

   :acked     {:db/valueType :db.type/ref}
   :owned     {:db/valueType :db.type/ref}
   :closed    {:db/valueType :db.type/ref}
   :urgent    {:db/valueType :db.type/ref}
   :important {:db/valueType :db.type/ref}
   :last-vote {:db/valueType :db.type/ref}

   :message-id   {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :subject      {:db/valueType :db.type/string
                  :db/fulltext  true}
   :body         {:db/valueType :db.type/string
                  :db/fulltext  true}
   :email        {:db/valueType :db.type/string
                  :db/unique    :db.unique/identity}
   :refs-count   {:db/valueType :db.type/long}
   ;; TODO: We store references but don't use them (yet)
   :references   {:db/valueType   :db.type/string
                  :db/cardinality :db.cardinality/many}
   :related-refs {:db/valueType   :db.type/string
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
