;; Copyright (c) 2022 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns bzg.init
  (:require [org.httpkit.server :as server]
            [bzg.core :as core]
            [bzg.web :as web]
            [bzg.db :as db]
            ;; FIXME: Remove in production
            [ring.middleware.reload :as reload]
            [integrant.core :as ig]
            [tea-time.core :as tt]
            [taoensso.timbre :as timbre])
  (:gen-class))

(def components-config
  (let [server       (:inbox-server db/config)
        user         (:inbox-user db/config)
        password     (:inbox-password db/config)
        folder       (:inbox-folder db/config)
        monitor-opts {:server   server
                      :user     user
                      :password password
                      :folder   folder}]
    (conj
     {:inbox/monitor  monitor-opts
      :reload/monitor monitor-opts}
     (when (:enabled (:ui db/config))
       {:http/service {:port    (:port db/config)
                       :baseurl (:baseurl db/config)}}))))

(defmethod ig/init-key :http/service [_ {:keys [port baseurl]}]
  (server/run-server
   (reload/wrap-reload web/handler {:dirs ["src" "resources"]})
   {:port port}
   (timbre/info
    (format "Web server started on %s (port %s)" baseurl port))))

(defmethod ig/init-key :inbox/monitor [_ opts]
  (core/start-inbox-monitor! opts)
  (timbre/info
   (format "Inbox monitoring started on %s" (:user opts))))

(defmethod ig/init-key :reload/monitor [_ opts]
  (core/reload-monitor! opts))

(defn -main []
  (let [admin-address (:admin-address db/config)]
    (tt/start!)
    (core/set-defaults)
    (core/update-person! {:email    admin-address
                          :username (or (:admin-username db/config)
                                        admin-address)
                          :role     :admin}
                         ;; The root admin cannot be removed
                         {:root true})
    (core/start-mail-loop!)
    (ig/init components-config)))

;; (-main)
