(ns ciwi.cache)

(defn root-context
  "Create or normalize a root cache context.

  Root/shared caches may outlive an individual candidate graph. Today that
  scope only contains value description-length memoization.
  "
  ([]
   {:shared {:value-dl-cache (atom {})}})
  ([context]
   {:shared {:value-dl-cache (or (get-in context [:shared :value-dl-cache])
                                 (atom {}))}}))

(defn search-context
  "Create or normalize a cache context for one candidate search stream.

  Search caches are safe to share while walking one frontier/materialization
  stream. They should not be process globals.
  "
  ([]
   (search-context nil))
  ([context]
   (assoc (root-context context)
          :search {:value-content-cache (or (get-in context [:search :value-content-cache])
                                            (atom {}))
                   :inverse-cache (or (get-in context [:search :inverse-cache])
                                      (atom {}))})))

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
                    :graph {:node-dl-cache (or (get-in context [:graph :node-dl-cache])
                                               (atom {}))})
       search (assoc :search search)))))

(defn value-dl-cache
  [context]
  (get-in context [:shared :value-dl-cache]))

(defn value-content-cache
  [context]
  (get-in context [:search :value-content-cache]))

(defn inverse-cache
  [context]
  (get-in context [:search :inverse-cache]))

(defn node-dl-cache
  [context]
  (get-in context [:graph :node-dl-cache]))
