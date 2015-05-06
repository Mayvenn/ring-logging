(ns ring-logging.core
  (:import (java.text SimpleDateFormat)
           (java.util Date)
           (java.util TimeZone)
           (java.util Calendar)))

(def utc-iso8601-formatter
  (doto (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ssZZ")
    (.setTimeZone (TimeZone/getTimeZone "UTC"))))

(defn serialized-date [date]
  (.format utc-iso8601-formatter date))

(defn now []
  (Date.))

(defn request-details
  [{:keys [request-method uri remote-addr query-string params headers]}]
  (str
   request-method " "
   uri
   (when query-string
     (str "?" query-string))
   (when (seq params)
     (str " with params " params))
   " for " remote-addr
   " at " (serialized-date (now))
   " with headers " headers))

(defn pre-logger
  [logger req]
  (logger :info (str "Starting " (request-details req))))

(defn post-logger
  [logger req {:keys [status] :as resp} total-time]
  (logger :info
          (str
           "Finished "
           (request-details req)
           " in " total-time " ms"
           " Status: " status
           (when (= status 302)
             (str " redirect to " (get-in resp [:headers "Location"]))))))

(defn make-logger-middleware
  [handler logger]
  (fn [req]
    (let [start (System/currentTimeMillis)]
      (pre-logger logger req)
      (let [resp (handler req)
            total-time (- (System/currentTimeMillis) start)]
        (post-logger logger req resp total-time)
        resp))))
