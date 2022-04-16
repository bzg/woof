(defproject woof "0.2.3"
  :description "Watch Over Our Folders"
  :url "https://github.com/bzg/woof"
  :license {:name "Eclipse Public License - v 2.0"
            :url  "http://www.eclipse.org/legal/epl-v20.html"}

  :dependencies [[org.clojure/clojure       "1.10.3"]
                 [io.forward/clojure-mail   "1.0.8"]
                 [http-kit/http-kit         "2.5.3"]
                 [clj-rss/clj-rss           "0.2.7"]
                 [markdown-clj/markdown-clj "1.10.6"]
                 [metosin/reitit            "0.5.15"]
                 [metosin/reitit-ring       "0.5.15"]
                 [metosin/reitit-middleware "0.5.15"]
                 [metosin/jsonista          "0.3.4"]
                 [metosin/muuntaja          "0.6.8"]
                 [ring/ring-devel           "1.9.4"]
                 [ring-cors/ring-cors       "0.1.13"]
                 [mount/mount               "0.1.16"]
                 [tea-time/tea-time         "1.0.1"]
                 [com.draines/postal        "2.0.4"]
                 [org.clojure/core.async    "1.3.622"]
                 [com.taoensso/timbre       "5.1.2"]
                 [selmer/selmer             "1.12.44"]
                 [datalevin/datalevin       "0.5.25"]
                 [com.cognitect/transit-clj "1.0.329"]]

  :jvm-opts  ["--add-opens=java.base/java.nio=ALL-UNNAMED"
              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]

  :source-paths ["src"]
  :resource-paths ["resources"]
  :profiles {:uberjar {:omit-source  true
                       :aot          :all
                       :uberjar-name "woof.jar"}}
  :main bzg.web)
