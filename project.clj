(defproject woof "0.2.3"
  :description "Watch Over Our Folders"
  :url "https://github.com/bzg/woof"
  :license {:name "Eclipse Public License - v 2.0"
            :url  "http://www.eclipse.org/legal/epl-v20.html"}

  :dependencies [[org.clojure/clojure       "1.11.1"]
                 [org.clojure/core.async    "1.6.673"]
                 [org.clojure/data.json    "2.4.0"]
                 [io.forward/clojure-mail   "1.0.8"]
                 [http-kit/http-kit         "2.6.0"]
                 [clj-rss/clj-rss           "0.4.0"]
                 [markdown-clj/markdown-clj "1.11.4"]
                 [metosin/reitit            "0.5.18"]
                 [metosin/reitit-ring       "0.5.18"]
                 [metosin/reitit-middleware "0.5.18"]
                 [ring/ring-devel           "1.9.6"]
                 [ring-cors/ring-cors       "0.1.13"]
                 [integrant/integrant       "0.8.0"]
                 [tea-time/tea-time         "1.0.1"]
                 [com.draines/postal        "2.0.5"]
                 [com.taoensso/timbre       "6.0.2"]
                 [selmer/selmer             "1.12.55"]
                 [datalevin/datalevin       "0.6.25"]
                 [com.cognitect/transit-clj "1.0.329"]
                 [medley/medley             "1.4.0"]
                 [aero/aero                 "1.1.6"]
                 [clojure.java-time/clojure.java-time "1.1.0"]
                 [version-clj/version-clj "2.0.2"]]

  :jvm-opts  ["--add-opens=java.base/java.nio=ALL-UNNAMED"
              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]

  :source-paths ["src"]
  :resource-paths ["resources"]
  :profiles {:uberjar {:omit-source  true
                       :aot          :all
                       :uberjar-name "woof.jar"}}
  :main bzg.web)
