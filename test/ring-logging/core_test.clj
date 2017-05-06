(ns ring-logging.core-test
  (:require [clojure.test :refer :all]
            [ring-logging.core :refer :all]))

(defn fake-logger [log]
  (fn [_ s]
    (reset! log s)))

(def test-config
  (assoc simple-inbound-config
         :txfm-req #(-> % :body (deep-select-keys :abc))
         :txfm-resp #(-> % :body (deep-select-keys :password))))

(deftest key-censorship
  (testing "Censors keys insensitively"
    (let [log (atom "")
          handler (constantly {:status 200 :headers {} :body {:password "ABC123"}})
          config  (assoc test-config :censor-keys #{"aBc" "PASSWORD"})
          request {:headers {} :body {:abc "super-secret"}}
          response ((wrap-logging default-handler (fake-logger log) config) request)]
      (is (= @log "Finished {:abc \"█\"} {:password \"█\"}")))))
