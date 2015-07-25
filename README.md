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
(->
   ...
   (make-logger-middleware logger)
   ...)
```


