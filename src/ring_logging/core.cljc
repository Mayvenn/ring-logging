(ns ring-logging.core
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            #?(:clj [cheshire.core :as json])))

(defn to-json [value]
  #?(:clj (json/generate-string value)
     :cljs (js/JSON.stringify (clj->js value))))

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
     :headers      {\"Authentication\" \"safe\"}} [:method :url :form-params {:query-params [:a :b]}])

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

(defn json-req
  "A json request format, which converts the request to json."
  [req]
  (to-json {:status   "Starting"
            :request  req}))

(defn structured-req
  "A map request format, which returns structured data"
  [req]
  {:domain  "http.ring.logging"
   :event   "request/started"
   :request req})

(defn pr-resp
  "A basic response format, which just uses pr-str on the request and response."
  [req resp]
  (str "Finished " (pr-str req) " " (pr-str resp)))

(defn json-resp
  "A json response format, which converts the resp and the req to json."
  [req resp]
  (to-json {:status   "Finished"
            :request  req
            :response resp}))

(defn structured-resp
  "A map response format, which returns structured data"
  [req resp]
  {:domain   "http.ring.logging"
   :event    "request/finished"
   :request  req
   :response resp})

(def default-censor-keys #{"password" "token"})

(def simple-inbound-config
  "A basic configuration for wrap-logging, when receiving requests,
  usually via ring."
  {:censor-keys default-censor-keys
   :txfm-req    txfm-inbound-req
   :format-req  pr-req
   :txfm-resp   txfm-resp
   :format-resp pr-resp})

(def simple-outbound-config
  "A basic configuration for wrap-logging, when making outbound
  requests to other services, usually via clj-http/client."
  {:censor-keys default-censor-keys
   :txfm-req    txfm-inbound-req
   :format-req  pr-req
   :txfm-resp   txfm-resp
   :format-resp pr-resp})

(def json-inbound-config
  "A basic configuration for wrap-logging, when receiving requests,
  usually via ring. Logs as json"
  {:censor-keys default-censor-keys
   :txfm-req    txfm-inbound-req
   :format-req  json-req
   :txfm-resp   txfm-resp
   :format-resp json-resp})

(def json-outbound-config
  "A basic configuration for wrap-logging, when making outbound
  requests to other services, usually via clj-http/client. Logs as json"
  {:censor-keys default-censor-keys
   :txfm-req    txfm-outbound-req
   :format-req  json-req
   :txfm-resp   txfm-resp
   :format-resp json-resp})

(def structured-inbound-config
  {:censor-keys default-censor-keys
   :txfm-req    txfm-inbound-req
   :format-req  structured-req
   :txfm-resp   txfm-resp
   :format-resp structured-resp})

(def structured-outbound-config
  {:censor-keys default-censor-keys
   :txfm-req    txfm-outbound-req
   :format-req  structured-req
   :txfm-resp   txfm-resp
   :format-resp structured-resp})

;; Aliased to prevent issues caused by relying on a previous typo
(def structured-output-config
  structured-outbound-config)

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
    (let [start (system-millis)]
      (when-let [resp (handler req)]
        (assoc resp :request-time (- (system-millis) start))))))

(defn ^:private censor-key? [keys-to-censor k]
  (some (partial string/includes? (string/lower-case (str k)))
        keys-to-censor))

(def ^:private redacted "█")

(defn ^:private censor-values [keys-to-censor m]
  (reduce-kv (fn [result k v]
               (assoc result
                      k (if (censor-key? keys-to-censor k) redacted v)))
             {}
             m))

(defn ^:private censor-params [keys-to-censor req]
  (walk/postwalk (fn [form]
                   (cond->> form
                     (map? form) (censor-values keys-to-censor)))
                 req))

(defn exp [x n]
  (reduce * (repeat n x)))

(def trace-radix 36)
(def trace-length 4)
(def max-trace-opts (exp trace-radix trace-length))
(def trace-padding (reduce str (repeat trace-length "0")))

(defn trace
  "Returns a 4 character string of random alphanumeric characters"
  []
  (let [i (rand-int max-trace-opts)]
    #?(:clj
       (let [rand-str (Integer/toString i trace-radix)]
         (subs (str trace-padding rand-str) (max (count rand-str) trace-length)))
       :cljs
       (.slice (str trace-padding (.toString i trace-radix)) (- trace-length)))))

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
  :param: censor-keys  A set of keys, which if they match (lower-case contain), will
                       appear in the log but whose values will be redacted.
                       Default: #{}.
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
  ([handler logger {:keys [censor-keys txfm-req format-req txfm-resp format-resp]
                    :or   {censor-keys #{}
                           txfm-req    identity
                           format-req  pr-req
                           txfm-resp   identity
                           format-resp pr-resp}}]
   (fn [req]
     (let [normalized-censor-keys (->> censor-keys (map string/lower-case) set)
           txfmed-req (->> req txfm-req (censor-params normalized-censor-keys))]
       (logger :info (format-req txfmed-req))
       (when-let [resp (handler req)]
         (let [txfmed-resp (->> resp txfm-resp (censor-params normalized-censor-keys))]
           (logger :info (format-resp txfmed-req txfmed-resp)))
         resp)))))
