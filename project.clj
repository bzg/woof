;; Copyright (c) 2022-2023 Bastien Guerry <bzg@gnu.org>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(defproject woof "0.3.3"
  :description "Watch Over Our Folders"
  :url "https://github.com/bzg/woof"
  :license {:name "Eclipse Public License - v 2.0"
            :url  "http://www.eclipse.org/legal/epl-v20.html"}

  :dependencies [[org.clojure/clojure       "1.12.0"]
                 [org.clojure/core.async    "1.6.681"]
                 [org.clojure/data.json     "2.5.1"]
                 [io.forward/clojure-mail   "1.0.8"]
                 [http-kit/http-kit         "2.8.0"]
                 [clj-rss/clj-rss           "0.4.0"]
                 [markdown-clj/markdown-clj "1.12.2"]
                 [metosin/reitit            "0.7.2"]
                 [metosin/reitit-ring       "0.7.2"]
                 [metosin/reitit-middleware "0.7.2"]
                 [ring/ring-devel           "1.13.0"]
                 [ring-cors/ring-cors       "0.1.13"]
                 [integrant/integrant       "0.13.1"]
                 [tea-time/tea-time         "1.0.1"]
                 [com.draines/postal        "2.0.5"]
                 [com.taoensso/timbre       "6.6.1"]
                 [selmer/selmer             "1.12.61"]
                 [datalevin/datalevin       "0.9.14"]
                 [com.cognitect/transit-clj "1.0.333"]
                 [medley/medley             "1.4.0"]
                 [aero/aero                 "1.1.6"]
                 [clojure.java-time/clojure.java-time "1.4.3"]
                 [version-clj/version-clj "2.0.3"]
                 [lambdaisland/kaocha "1.91.1392"]]

  :jvm-opts  ["--add-opens=java.base/java.nio=ALL-UNNAMED"
              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]

  :source-paths ["src"]
  :resource-paths ["resources"]
  :profiles {:uberjar {:omit-source  true
                       :aot          :all
                       :uberjar-name "woof.jar"}}
  :main bzg.init)
