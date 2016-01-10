#ring-logging
A Ring middleware for logging requests and responses

##Referencing in Leiningen
```Clojure
[ring-logging "0.1.1"]
```

##Primary Namespace
```Clojure
ring-logging.core
```

##Example
```Clojure
(defn wrap-api [routes logger]
  (-> routes
      ring-logging/wrap-request-timing
      (ring-logging/wrap-logging logger ring-logging/simple-inbound-config)
      wrap-json-response
      (wrap-json-body {:keywords? true})
      ring-logging/wrap-trace-request
      (wrap-defaults api-defaults)))
```
