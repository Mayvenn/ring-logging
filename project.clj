(defproject ring-logging "0.2.0"
  :description "Logs ring requests and responses"
  :url "https://github.com/Mayvenn/ring-logging"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]]
  :deploy-repositories [["releases" :clojars]]
  :plugins [[s3-wagon-private "1.1.2"]])
