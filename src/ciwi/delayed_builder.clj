(ns ciwi.delayed-builder
  (:require [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.rewrite :as rewrite]
            [ciwi.spec :as spec]
            [ciwi.value :as value]))

(defrecord BuildInfo [dl graph memory conditioned-nodes condition-key element-index])

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

(defn- result-key
  [{:keys [graph root memory]}]
  [(graph/structural-key graph root)
   (->> memory
        (map (fn [[node-id entry]]
               [node-id (value/datum (entry-value entry))]))
        (sort-by (comp pr-str first))
        vec)])

(defn- dedupe-results
  [seen results]
  (reduce (fn [[seen emitted] result]
            (let [k (result-key result)]
              (if (contains? seen k)
                [seen emitted]
                [(conj seen k) (conj emitted result)])))
          [(or seen #{}) []]
          results))

(defn- try-apply-op
  [operator inputs]
  (try
    (op/apply-op operator inputs)
    (catch Exception _
      nil)))

(defn- try-invert-op
  [operator output known-inputs known-positions]
  (try
    (op/invert-op operator output known-inputs known-positions)
    (catch Exception _
      ())))

(defn- costed-operator
  [{:keys [operator dl]}]
  (cond-> operator
    (some? dl) (assoc :dl dl)))

(defn- value-conforms?
  [expected x]
  (or (nil? expected)
      (spec/conforms? expected (spec/value-spec x))))

(defn- forward-build
  [g memory {:keys [operator arity output-spec] :as element} positions]
  (let [child-ids (mapv positions (range arity))]
    (when (every? some? child-ids)
      (let [inputs (mapv #(memory-value memory %) child-ids)
            output (try-apply-op operator inputs)]
        (when (and output
                   (value-conforms? output-spec output))
          (let [graph-op (costed-operator element)
                root-id (graph/unique-id g (keyword (str "delayed-" (name (:id operator)))))
                op-id (graph/unique-id g (keyword (str (name root-id) "-" (name (:id operator)))))
                g (graph/add-value g root-id output)
                memory (assoc-memory memory root-id output)
                g (graph/add-operator g op-id graph-op root-id child-ids)]
            {:graph g
             :memory memory
             :root root-id
             :operator-id op-id}))))))

(defn- inverse-builds
  [g memory {:keys [operator arity input-specs] :as element} positions]
  (when-let [output-id (:output positions)]
    (let [known-positions (->> (range arity)
                               (filter #(contains? positions %))
                               vec)
          known-child-ids (mapv positions known-positions)]
      (when (every? #(rewrite/reusable-child-node? g output-id %) known-child-ids)
        (let [output (memory-value memory output-id)
              known-inputs (mapv #(memory-value memory %) known-child-ids)
              missing-positions (vec (remove (set known-positions) (range arity)))]
          (for [missing-values (try-invert-op operator output known-inputs known-positions)
                :when (= (count missing-positions) (count missing-values))
                :let [built
                  (reduce (fn [[acc-g acc-memory ids] idx]
                            (if-let [node-id (get positions idx)]
                              [acc-g acc-memory (assoc ids idx node-id)]
                              (let [missing-idx (.indexOf missing-positions idx)
                                    missing-value (nth missing-values missing-idx)
                                    base (keyword (str "delayed-" (name (:id operator))
                                                       "-arg" idx))]
                                (if-not (value-conforms? (nth input-specs idx nil)
                                                         missing-value)
                                  (reduced nil)
                                  (let [[acc-g acc-memory node-id]
                                        (add-generated-value acc-g acc-memory base missing-value)]
                                    [acc-g acc-memory (assoc ids idx node-id)])))))
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

(defn- raw-delayed-dag-build
  [build-info elements-by-key {:keys [registry]
                               :or {registry op/registry}}]
  (let [element (selected-element build-info elements-by-key registry)
        positions (position-map (:gen-cond element) (:conditioned-nodes build-info))
        g (:graph build-info)
        memory (:memory build-info)
        results (if (contains? positions :output)
                  (inverse-builds g memory element positions)
                  (when-let [result (forward-build g memory element positions)]
                    [result]))]
    (vec results)))

(defn delayed-dag-build-with-seen
  "Like `delayed-dag-build`, but returns the updated functional seen set."
  ([build-info elements-by-key seen]
   (delayed-dag-build-with-seen build-info elements-by-key seen {}))
  ([build-info elements-by-key seen opts]
   (let [[seen results] (dedupe-results seen
                                        (raw-delayed-dag-build build-info
                                                               elements-by-key
                                                               opts))]
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
