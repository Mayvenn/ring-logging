(ns ring-logging.core)

(defn deep-select-keys
  "A utility function for filtering requests and responses.  Behaves
  like select-keys, but with arbitrary nesting. When a value is missing
  in the source map, it will not be included in the result.  For
  example:

  ``` clojure
  (deep-select-keys
    {:method       :get
     :url          \"http://google.com\"
     :query-params {:a        \"some\"
                    :password \"safe\"}
     :headers      {\"Authentcation\" \"safe\"}}
    [:method :url :form-params {:query-params [:a :b]}])

  ; =>
    {:method       :get
     :url          \"http://google.com\"
     :query-params {:a \"some\"}}
  ```

  And

  ``` clojure
  (deep-select-keys
    {:status  302
     :headers {\"Location\"     \"http://elsewhere.com\"
               \"Authencation\" \"safe\"}
     :body    {:number 25
               :key    :xyz}}
    [:status {:headers \"Location\"
              :body    :number}])

  ; =>
    {:status  302
     :headers {\"Location\" \"http://elsewhere.com\"}
     :body    {:number 25}}
  ```"
  [m x]
  (cond
    (or (keyword? x)
        (symbol? x)
        (string? x)) (when-let [value (get m x)]
                       {x value})
    (map? x)         (into (array-map)
                           (for [[k v] x]
                             (when-let [value (deep-select-keys (get m k) v)]
                               [k value])))
    (coll? x)        (into (array-map)
                           (for [v x]
                             (deep-select-keys m v)))
    :else            nil))

;; Helpers for transforming and formatting requests and responses

(defn txfm-inbound-req
  "A basic tranformation for requests this service receives and
  processes, usually via ring. Includes request-method, uri, params,
  remote-addr and the host and request-trace headers."
  [req]
  (deep-select-keys req [:request-method :uri :params :remote-addr {:headers ["host" "request-trace"]}]))

(defn txfm-outbound-req
  "A basic tranformation for requests this service makes to other
  services, usually via clj-http/client. Includes method, url,
  query-params, form-params, and the request-trace header."
  [req]
  (deep-select-keys req [:method :url :query-params :form-params {:headers "request-trace"}]))

(defn txfm-resp
  "A basic response tranformation.  Includes response status,
  request-time and the Location header for redirects."
  [resp]
  (deep-select-keys resp [:status :request-time {:headers "Location"}]))

(defn pr-req
  "A basic request format, which just uses pr-str on the request."
  [req]
  (str "Starting " (pr-str req)))

(defn pr-resp
  "A basic response format, which just uses pr-str on the request and response."
  [req resp]
  (str "Finished " (pr-str req) " " (pr-str resp)))

(def simple-inbound-config
  "A basic configuration for wrap-logging, when receiving requests,
  usually via ring."
  {:txfm-req    txfm-inbound-req
   :format-req  pr-req
   :txfm-resp   txfm-resp
   :format-resp pr-resp})

(def simple-outbound-config
  "A basic configuration for wrap-logging, when making outbound
  requests to other services, usually via clj-http/client."
  {:txfm-req    txfm-outbound-req
   :format-req  pr-req
   :txfm-resp   txfm-resp
   :format-resp pr-resp})

;; Wrappers useful for logging requests and responses.

(defn- system-millis []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn wrap-request-timing
  "Middleware that times the request, putting the total time (in
  milliseconds) of the request into the :request-time key in the
  response. This is part of default-middleware in clj-http.client, so
  it doesn't always need to be added. Extends the response so must
  execute later than wrap-logging."
  [handler]
  (fn [req]
    (let [start (system-millis)
          resp (handler req)]
      (assoc resp :request-time (- (system-millis) start)))))

(defn trace
  "Returns a 4 character string of random hex digits"
  []
  (let [i (rand-int 0x10000)]
    #?(:clj
       (format "%04x" i)
       :cljs
       (.slice (str "0000" (.toString i 16)) -4))))

(defn extend-trace [prior]
  (if prior
    (str prior "." (trace))
    (trace)))

(defn wrap-trace-request
  "Middleware that adds an id (4 hex digits) into the request, at the
  provided key, as by assoc-in, by default at [:headers
  \"request-trace\"]. If an id is already present, extends it so that
  '6d3d' becomes '6d3d.ef66'. The recommended usage is to pass traces
  from one service to another, so that a log aggregator can follow
  requests between the services. Extends the request, so must execute
  earlier than wrap-logging."
  ([handler] (wrap-trace-request handler {:keyseq [:headers "request-trace"]}))
  ([handler {:keys [keyseq]}]
   (fn [req]
     (let [trace (extend-trace (get-in req keyseq))]
       (handler (assoc-in req keyseq trace))))))

(defn wrap-logging
  "Middleware that logs at the start of the request and the end of the response.
  Accepts optional configuration parameters:
  :param: txfm-req     A function which will transform the request before it is
                       formatted. Useful for filtering sensitive information from the
                       request. Default: identity.
  :param: format-req   A function which will format the request. Default: pr-req
  :param: txfm-resp    A function which will transform the response before it is
                       formatted. Useful for selecting a small piece of the response
                       to be logged, possibly in combination with pr-resp. Default:
                       identity.
  :param: format-resp  A function which will format the response. Receives both the
                       request and response as arguments. Default: pr-resp."
  ([handler logger] (wrap-logging handler logger {}))
  ([handler logger {:keys [txfm-req format-req txfm-resp format-resp]
                    :or   {txfm-req    identity
                           format-req  pr-req
                           txfm-resp   identity
                           format-resp pr-resp}}]
   (fn [req]
     (let [txfmed-req (txfm-req req)]
       (logger :info (format-req txfmed-req))
       (let [resp (handler req)]
         (logger :info (format-resp txfmed-req (txfm-resp resp)))
         resp)))))