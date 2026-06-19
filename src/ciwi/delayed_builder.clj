(ns ciwi.delayed-builder
  (:require [ciwi.cache :as cache]
            [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.hashing :as hashing]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.rewrite :as rewrite]
            [ciwi.spec :as spec]
            [ciwi.value :as value])
  (:import [java.lang.ref WeakReference]))

(defrecord BuildInfo [dl graph memory conditioned-nodes condition-key element-index])

(deftype IdentityKey [x purpose]
  Object
  (equals [_ other]
    (and (instance? IdentityKey other)
         (identical? x (.-x ^IdentityKey other))
         (= purpose (.-purpose ^IdentityKey other))))
  (hashCode [_]
    (hash [::identity-key
           (System/identityHashCode x)
           purpose])))

(deftype WeakIdentityKey [ref identity-hash purpose]
  Object
  (equals [_ other]
    (boolean
     (and (instance? WeakIdentityKey other)
          (= identity-hash (.-identity-hash ^WeakIdentityKey other))
          (= purpose (.-purpose ^WeakIdentityKey other))
          (let [other-ref (.-ref ^WeakIdentityKey other)
                x (when ref
                    (.get ^WeakReference ref))
                y (when other-ref
                    (.get ^WeakReference other-ref))]
            (and x y (identical? x y))))))
  (hashCode [_]
    (hash [::weak-identity-key identity-hash purpose])))

(defn- weak-identity-key
  [x purpose]
  (WeakIdentityKey. (WeakReference. x)
                    (System/identityHashCode x)
                    purpose))

(defn build-info
  [{:keys [dl graph memory conditioned-nodes condition-key element-index]
    :or {memory {}
         element-index 0}}]
  (->BuildInfo dl graph memory (vec conditioned-nodes) condition-key element-index))

(defn compare-build-info
  [left right]
  (compare (:dl left) (:dl right)))

(defn same-build-rank?
  [left right]
  (zero? (compare-build-info left right)))

(defn graph-element
  ([operator gen-cond]
   (graph-element operator gen-cond {}))
  ([operator gen-cond {:keys [arity input-specs output-spec dl id]}]
   {:operator operator
    :gen-cond (vec gen-cond)
    :arity arity
    :input-specs (vec input-specs)
    :output-spec output-spec
    :dl dl
    :id id}))

(defn- resolve-operator
  [registry operator]
  (cond
    (op/operator? operator) operator
    (keyword? operator) (get registry operator)
    :else nil))

(defn- normalize-element
  [registry {:keys [operator gen-cond arity] :as element}]
  (let [runtime-op (resolve-operator registry operator)
        known-child-positions (remove #{-1} gen-cond)
        arity (or arity
                  (some->> known-child-positions
                           seq
                           (apply max)
                           inc))]
    (when-not runtime-op
      (throw (ex-info "Unknown delayed builder operator" {:operator operator})))
    (when-not (integer? arity)
      (throw (ex-info "Delayed graph element requires explicit :arity"
                      {:element element})))
    (assoc element
           :operator runtime-op
           :arity arity
           :gen-cond (vec gen-cond))))

(defn- selected-element
  [build-info elements-by-key registry]
  (let [elements (get elements-by-key (:condition-key build-info))]
    (when-not (seq elements)
      (throw (ex-info "No delayed graph elements for condition key"
                      {:condition-key (:condition-key build-info)})))
    (normalize-element registry (nth elements (:element-index build-info)))))

(defn- entry-value
  [entry]
  (cond
    (and (map? entry) (contains? entry :value)) (:value entry)
    (value/value? entry) entry
    :else (value/value entry)))

(defn- memory-value
  [memory node-id]
  (if (contains? memory node-id)
    (entry-value (get memory node-id))
    (throw (ex-info "Delayed builder missing conditioned node value"
                    {:node-id node-id}))))

(defn- assoc-memory
  [memory node-id x]
  (assoc memory node-id (propagation/entry x)))

(defn- with-inferred-spec
  [x]
  (let [v (value/value x)]
    (if (:spec v)
      v
      (assoc v :spec (spec/infer-spec v)))))

(defn- add-generated-value
  [g memory base value]
  (let [node-id (graph/unique-id g base)]
    [(graph/add-value g node-id value)
     (assoc-memory memory node-id value)
     node-id]))

(defn- position-map
  [gen-cond conditioned-nodes]
  (when-not (= (count gen-cond) (count conditioned-nodes))
    (throw (ex-info "Length of gen-cond must match conditioned nodes"
                    {:gen-cond gen-cond
                     :conditioned-nodes conditioned-nodes})))
  (reduce (fn [positions [pos node-id]]
            (let [k (if (= -1 pos) :output pos)
                  existing (get positions k ::missing)]
              (when (and (not= existing ::missing)
                         (not= existing node-id))
                (throw (ex-info "Delayed builder position maps to multiple nodes"
                                {:position pos
                                 :left existing
                                 :right node-id})))
              (assoc positions k node-id)))
          {}
          (map vector gen-cond conditioned-nodes)))

(defn- raw-value-fingerprint
  [x]
  (let [v (value/value x)]
    [(spec/value-spec v) (hashing/content-fingerprint (:data v))]))

(defn- value-fingerprint
  ([x]
   (raw-value-fingerprint x))
  ([cache x]
   (if (and cache (value/value? x))
     (let [k (if (dense/ndarray? (:data x))
               (weak-identity-key x :delayed-value-fingerprint)
               (IdentityKey. x :delayed-value-fingerprint))]
       (cache/get-or-compute! cache k #(raw-value-fingerprint x)))
     (raw-value-fingerprint x))))

(defn- compare-structural-keys
  [left right]
  (let [c (compare (hash left) (hash right))]
    (cond
      (not (zero? c)) c
      (= left right) 0
      :else (compare (pr-str left) (pr-str right)))))

(defn- result-structural-key
  [graph root-id value-content-cache]
  (letfn [(key* [id trace]
            (if (contains? trace id)
              [:cycle]
              (let [n (graph/node graph id)
                    trace (conj trace id)]
                (cond
                  (graph/value-node? n)
                  [:value
                   (value-fingerprint value-content-cache (:value n))
                   (mapv #(key* % trace) (:options n))]

                  (graph/operator-node? n)
                  (let [op (:operator n)
                        child-keys (mapv #(key* % trace) (:children n))
                        child-keys (if (:commutative? op)
                                     (vec (sort compare-structural-keys
                                                child-keys))
                                     child-keys)]
                    [:operator (:id op) child-keys])

                  :else [:missing id]))))]
    (key* root-id #{})))

(defn result-key
  "Return the structural key used to de-duplicate materialized delayed builds."
  ([result]
   (result-key result nil))
  ([{:keys [graph memory]} value-content-cache]
   [(->> (graph/roots graph)
         (sort-by pr-str)
         (map #(result-structural-key graph % value-content-cache))
         vec)
    (->> memory
         (map (fn [[node-id entry]]
                [node-id
                 (value-fingerprint value-content-cache (entry-value entry))]))
         (sort-by (comp pr-str first))
         vec)]))

(defn- memory-value-fingerprint-buckets
  [cache memory]
  (reduce (fn [buckets entry]
            (let [v (entry-value entry)]
              (update buckets (value-fingerprint cache v) (fnil conj []) v)))
          {}
          (vals memory)))

(defn- duplicate-value-in-bucket?
  [candidate bucket]
  (let [candidate-data (:data (value/value candidate))]
    (boolean
     (some (fn [existing]
             (hashing/content-equal? candidate-data
                                     (:data (value/value existing))))
           bucket))))

(defn- duplicate-generated-value?
  [cache existing-value-buckets missing-values]
  (boolean
   (some (fn [missing-value]
           (let [fingerprint (value-fingerprint cache missing-value)]
             (when-let [bucket (get existing-value-buckets fingerprint)]
               (duplicate-value-in-bucket? missing-value bucket))))
         missing-values)))

(defn- dedupe-results
  [seen results value-content-cache]
  (loop [seen (or seen #{})
         remaining (seq results)]
    (if-let [result (first remaining)]
      (let [k (result-key result value-content-cache)]
        (if (contains? seen k)
          (recur seen (next remaining))
          [(conj seen k) [result]]))
      [seen []])))

(defn- try-apply-op
  [operator inputs]
  (try
    (op/apply-op operator inputs)
    (catch Exception _
      nil)))

(defn- inverse-cache-key
  [value-content-cache operator output known-inputs known-positions]
  [(:id operator)
   (vec known-positions)
   (value-fingerprint value-content-cache output)
   (mapv #(value-fingerprint value-content-cache %) known-inputs)])

(defn- raw-invert-op
  [operator output known-inputs known-positions]
  (try
    (vec (op/invert-op operator output known-inputs known-positions))
    (catch Exception _
      [])))

(defn- try-invert-op
  [operator output known-inputs known-positions opts]
  (let [cache-context (cache/search-context (:cache-context opts))]
    (if-let [inverse-cache (cache/inverse-cache cache-context)]
      (let [value-content-cache (cache/value-content-cache cache-context)
            k (inverse-cache-key value-content-cache
                                 operator
                                 output
                                 known-inputs
                                 known-positions)]
        (cache/get-or-compute!
         inverse-cache
         k
         #(raw-invert-op operator
                         output
                         known-inputs
                         known-positions)))
      (raw-invert-op operator output known-inputs known-positions))))

(defn- costed-operator
  [{:keys [operator dl]}]
  (cond-> operator
    (some? dl) (assoc :dl dl)))

(defn- value-conforms?
  [expected x]
  (let [v (with-inferred-spec x)]
    (when (or (nil? expected)
              (spec/conforms? expected (:spec v)))
      (if (or (nil? expected)
              (= expected (:spec v))
              (contains? #{:unknown :number :array :array-number} expected))
        v
        (assoc v :spec expected)))))

(defn- forward-build
  [g memory {:keys [operator arity output-spec] :as element} positions opts]
  (let [child-ids (mapv positions (range arity))]
    (when (every? some? child-ids)
      (let [inputs (mapv #(memory-value memory %) child-ids)
            output (try-apply-op operator inputs)]
        (when-let [output (and output
                               (value-conforms? output-spec output))]
          (let [value-content-cache (cache/value-content-cache
                                     (:cache-context opts))
                existing-value-buckets (memory-value-fingerprint-buckets
                                        value-content-cache
                                        memory)]
            (when-not (duplicate-generated-value? value-content-cache
                                                  existing-value-buckets
                                                  [output])
              (let [graph-op (costed-operator element)
                    root-id (graph/unique-id
                             g
                             (keyword (str "delayed-" (name (:id operator)))))
                    op-id (graph/unique-id
                           g
                           (keyword (str (name root-id) "-"
                                         (name (:id operator)))))
                    g (graph/add-value g root-id output)
                    memory (assoc-memory memory root-id output)
                    g (-> g
                          (graph/add-operator op-id graph-op root-id child-ids)
                          (graph/add-root root-id))]
                {:graph g
                 :memory memory
                 :root root-id
                 :operator-id op-id}))))))))

(defn- inverse-builds
  [g memory {:keys [operator arity input-specs] :as element} positions opts]
  (when-let [output-id (:output positions)]
    (let [known-positions (->> (range arity)
                               (filter #(contains? positions %))
                               vec)
          known-child-ids (mapv positions known-positions)]
      (when (every? #(rewrite/reusable-child-node? g output-id %) known-child-ids)
        (let [output (memory-value memory output-id)
              known-inputs (mapv #(memory-value memory %) known-child-ids)
              missing-positions (vec (remove (set known-positions) (range arity)))
              value-content-cache (cache/value-content-cache (:cache-context opts))
              existing-value-buckets (memory-value-fingerprint-buckets
                                      value-content-cache
                                      memory)]
          (for [missing-values (try-invert-op operator
                                              output
                                              known-inputs
                                              known-positions
                                              opts)
                :when (= (count missing-positions) (count missing-values))
                :when (not (duplicate-generated-value? value-content-cache
                                                       existing-value-buckets
                                                       missing-values))
                :let [built
                  (reduce (fn [[acc-g acc-memory ids] idx]
                            (if-let [node-id (get positions idx)]
                              [acc-g acc-memory (assoc ids idx node-id)]
                              (let [missing-idx (.indexOf missing-positions idx)
                                    missing-value (nth missing-values missing-idx)
                                    base (keyword (str "delayed-" (name (:id operator))
                                                       "-arg" idx))]
                                (if-let [missing-value (value-conforms?
                                                        (nth input-specs idx nil)
                                                        missing-value)]
                                  (let [[acc-g acc-memory node-id]
                                        (add-generated-value acc-g acc-memory base missing-value)]
                                    [acc-g acc-memory (assoc ids idx node-id)])
                                  (reduced nil)))))
                          [g memory {}]
                          (range arity))]
                :when (some? built)]
            (let [[g memory child-ids] built
                  child-ids (mapv child-ids (range arity))
                  op-id (graph/unique-id g (keyword (str (name output-id) "-"
                                                         (name (:id operator)))))
                  g (graph/add-operator g op-id (costed-operator element) output-id child-ids)]
              {:graph g
               :memory memory
               :root output-id
               :operator-id op-id})))))))

(defn raw-delayed-dag-build
  [build-info elements-by-key {:keys [registry]
                               :or {registry op/registry}
                               :as opts}]
  (let [cache-context (cache/search-context (:cache-context opts))
        opts (assoc opts :cache-context cache-context)
        element (selected-element build-info elements-by-key registry)
        positions (position-map (:gen-cond element) (:conditioned-nodes build-info))
        g (:graph build-info)
        memory (:memory build-info)
        results (if (contains? positions :output)
                  (inverse-builds g memory element positions opts)
                  (when-let [result (forward-build g memory element positions opts)]
                    [result]))]
    (vec results)))

(defn delayed-dag-build-with-seen
  "Like `delayed-dag-build`, but returns the updated functional seen set."
  ([build-info elements-by-key seen]
   (delayed-dag-build-with-seen build-info elements-by-key seen {}))
  ([build-info elements-by-key seen opts]
   (let [cache-context (cache/search-context (:cache-context opts))
         value-content-cache (cache/value-content-cache cache-context)
         opts (assoc opts :cache-context cache-context)
         [seen results] (dedupe-results
                         seen
                         (raw-delayed-dag-build build-info
                                                elements-by-key
                                                opts)
                         value-content-cache)]
     {:seen seen
      :results results})))

(defn delayed-dag-build
  "Attach one delayed graph element to the conditioned nodes in `build-info`.

  A `gen-cond` entry of `-1` means the corresponding conditioned node is the
  element output and the remaining conditioned nodes are known operator inputs.
  Otherwise every operator input must be conditioned, and the element is added
  above those existing nodes. Conditioned nodes may repeat, which preserves
  DAG-shared inputs such as `mult(d, d)`.
  "
  ([build-info elements-by-key]
   (raw-delayed-dag-build build-info elements-by-key {}))
  ([build-info elements-by-key seen]
   (delayed-dag-build build-info elements-by-key seen {}))
  ([build-info elements-by-key seen opts]
   (:results (delayed-dag-build-with-seen build-info
                                          elements-by-key
                                          seen
                                          opts))))
