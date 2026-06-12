(ns ciwi.cache
  (:refer-clojure :exclude [get])
  (:import [java.util.concurrent ConcurrentHashMap ConcurrentMap]
           [java.util.function Function]))

(defn cache-store
  "Create a concurrent mutable cache store for one cache slot."
  []
  (ConcurrentHashMap.))

(defn- normalize-cache
  [cache]
  (cond
    (nil? cache) (cache-store)
    (instance? ConcurrentMap cache) cache
    :else (throw (ex-info "CIWI caches must be java.util.concurrent.ConcurrentMap instances"
                          {:cache cache
                           :class (some-> cache class .getName)}))))

(defn get
  [cache k]
  (when cache
    (.get ^ConcurrentMap cache k)))

(defn put!
  [cache k v]
  (when cache
    (.put ^ConcurrentMap cache k v))
  v)

(defn put-if-absent!
  [cache k v]
  (when cache
    (.putIfAbsent ^ConcurrentMap cache k v)))

(defn get-or-compute!
  [cache k f]
  (if cache
    (.computeIfAbsent ^ConcurrentMap cache
                      k
                      (reify Function
                        (apply [_ _]
                          (f))))
    (f)))

(defn size
  [cache]
  (if cache
    (.size ^ConcurrentMap cache)
    0))

(defn root-context
  "Create or normalize a root cache context.

  Root/shared caches may outlive an individual candidate graph. Today that
  scope only contains value description-length memoization.
  "
  ([]
   {:shared {:value-dl-cache (cache-store)}})
  ([context]
   {:shared {:value-dl-cache (normalize-cache
                              (clojure.core/get-in context
                                                   [:shared :value-dl-cache]))}}))

(defn search-context
  "Create or normalize a cache context for one candidate search stream.

  Search caches are safe to share while walking one frontier/materialization
  stream. They should not be process globals.
  "
  ([]
   (search-context nil))
  ([context]
   (assoc (root-context context)
          :search {:value-content-cache
                   (normalize-cache
                    (clojure.core/get-in context [:search :value-content-cache]))
                   :inverse-cache
                   (normalize-cache
                    (clojure.core/get-in context [:search :inverse-cache]))})))

(defn scoring-context
  "Create or normalize a graph-local scoring cache context.

  `:node-dl-cache` is graph-local and should be fresh for a distinct graph
  scoring pass unless a caller deliberately provides one.
  "
  ([]
   (scoring-context nil))
  ([context]
   (let [root (root-context context)
         search (:search context)]
     (cond-> (assoc root
                    :graph {:node-dl-cache
                            (normalize-cache
                             (clojure.core/get-in context
                                                  [:graph :node-dl-cache]))})
       search (assoc :search search)))))

(defn value-dl-cache
  [context]
  (clojure.core/get-in context [:shared :value-dl-cache]))

(defn value-content-cache
  [context]
  (clojure.core/get-in context [:search :value-content-cache]))

(defn inverse-cache
  [context]
  (clojure.core/get-in context [:search :inverse-cache]))

(defn node-dl-cache
  [context]
  (clojure.core/get-in context [:graph :node-dl-cache]))
