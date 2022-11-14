(defproject ring-logging "0.3.4"
  :description "Logs ring requests and responses"
  :url "https://github.com/Mayvenn/ring-logging"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.5.0"]]
  :lein-release {:deploy-via :clojars}
  :deploy-repositories [["releases" :clojars]])
