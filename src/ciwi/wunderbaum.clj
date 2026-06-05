(ns ciwi.wunderbaum
  (:refer-clojure :exclude [iterate])
  (:require [ciwi.delayed-builder :as delayed]
            [ciwi.enumerator :as enumerator]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.spec :as spec]
            [ciwi.value :as value]))

(defrecord Wunderbaum [registry elements-by-condition-key opts])

(defn- require-registry
  [registry]
  (when-not (map? registry)
    (throw (ex-info "Wunderbaum requires an injected operator registry"
                    {:registry registry})))
  registry)

(defn- resolve-operator
  [registry operator]
  (cond
    (op/operator? operator) operator
    (keyword? operator) (get registry operator)
    :else nil))

(defn generalized-conditions
  "Return Python-Wunderbaum-style generalized conditions for an operator arity.

  `-1` denotes the operator output. Nonnegative entries denote conditioned child
  positions. Inverse attachments are `[-1 ...conditioned-inputs]`; the forward
  attachment is all child positions.
  "
  [conditions arity]
  (let [conditions (or conditions [])]
    (vec
     (concat
      (for [condition conditions
            :let [condition (vec condition)]
            :when (< (count condition) arity)]
        (into [-1] condition))
      [(vec (range arity))]))))

(defn- normalize-op-count
  [op-count]
  (cond
    (map? op-count)
    op-count

    (and (vector? op-count) (= 2 (count op-count)))
    {:op (first op-count)
     :count (second op-count)}

    (and (vector? op-count) (= 3 (count op-count)))
    (assoc (nth op-count 2)
           :op (first op-count)
           :count (second op-count))

    :else
    (throw (ex-info "Expected Wunderbaum operator/count declaration"
                    {:op-count op-count}))))

(defn- normalize-declaration
  [registry op-count]
  (let [{operator :op
         op-count-value :count
         :keys [arity input-specs output-spec conditions dl id jitter]
         :as declaration} (normalize-op-count op-count)
        op-count-value (or op-count-value 0)
        runtime-op (resolve-operator registry operator)
        input-specs (vec input-specs)
        arity (or arity (count input-specs))
        conditions (or conditions (:conditions runtime-op))]
    (when-not runtime-op
      (throw (ex-info "Unknown Wunderbaum operator" {:operator operator})))
    (when-not (seq input-specs)
      (throw (ex-info "Wunderbaum operator declaration requires :input-specs"
                      {:declaration declaration})))
    (when-not output-spec
      (throw (ex-info "Wunderbaum operator declaration requires :output-spec"
                      {:declaration declaration})))
    (when-not (= arity (count input-specs))
      (throw (ex-info "Wunderbaum operator arity must match :input-specs"
                      {:arity arity
                       :input-specs input-specs
                       :declaration declaration})))
    {:id (or id (:id runtime-op))
     :operator runtime-op
     :arity arity
     :input-specs input-specs
     :output-spec output-spec
     :conditions (vec conditions)
     :count op-count-value
     :dl (or dl (:dl runtime-op))
     :jitter (double (or jitter 0.0))}))

(defn- condition-key
  [{:keys [input-specs output-spec]} gen-cond]
  (mapv (fn [position]
          (if (= -1 position)
            output-spec
            (nth input-specs position)))
        gen-cond))

(defn operator-elements-by-condition-key
  "Index operator elements by the specs of their conditioned attachment nodes."
  [registry ops-with-counts]
  (let [registry (require-registry registry)
        declarations (mapv #(normalize-declaration registry %) ops-with-counts)
        total-count (reduce + 0.0 (map :count declarations))]
    (reduce
     (fn [elements declaration]
       (let [effective-dl (enumerator/effective-dl (:dl declaration)
                                                   (:count declaration)
                                                   total-count
                                                   enumerator/default-concentration
                                                   (:jitter declaration))]
         (reduce
          (fn [elements gen-cond]
            (let [k (condition-key declaration gen-cond)
                  element (delayed/graph-element
                           (:operator declaration)
                           gen-cond
                           {:arity (:arity declaration)
                            :input-specs (:input-specs declaration)
                            :output-spec (:output-spec declaration)
                            :dl effective-dl
                            :id (:id declaration)})]
              (update elements k (fnil conj []) element)))
          elements
          (generalized-conditions (:conditions declaration)
                                  (:arity declaration)))))
     {}
     declarations)))

(defn wunderbaum
  [{:keys [registry ops-with-counts] :as opts}]
  (let [registry (require-registry registry)]
    (->Wunderbaum registry
                  (operator-elements-by-condition-key registry ops-with-counts)
                  opts)))

(defn- indexed-id
  [prefix idx]
  (keyword (str (name prefix) idx)))

(defn initial-state
  [targets]
  (let [target-ids (mapv #(indexed-id :target %) (range (count targets)))
        g (reduce (fn [g [id target]]
                    (graph/add-value g id target))
                  (graph/empty-graph)
                  (map vector target-ids targets))
        memory (into {}
                     (map (fn [[id target]]
                            [id (propagation/entry target)]))
                     (map vector target-ids targets))]
    {:graph g
     :memory memory
     :target-ids target-ids}))

(defn- graph-value-order
  [g]
  (loop [roots (graph/roots g)
         seen #{}
         result []]
    (if-let [root-id (first roots)]
      (let [ids (graph/breadth-first-walk g root-id {:above? false
                                                     :below? true
                                                     :values? true
                                                     :operators? false})
            new-ids (remove seen ids)]
        (recur (rest roots)
               (into seen new-ids)
               (into result new-ids)))
      result)))

(defn- node-index-dl
  [idx]
  (value/elias-discrete (inc idx)))

(defn- tuple-item
  [ids indices]
  {:dl (reduce + 0.0 (map node-index-dl indices))
   :indices (vec indices)
   :nodes (mapv ids indices)})

(defn- tuple-rank
  [item]
  [(:dl item) (:indices item)])

(defn- tuple-queue
  []
  (sorted-set-by (fn [left right]
                   (compare (tuple-rank left)
                            (tuple-rank right)))))

(defn- pop-tuple
  [queue]
  (let [item (first queue)]
    [item (disj queue item)]))

(defn- starting-tuples
  [ids max-tuple-len]
  (for [n (range 1 (inc max-tuple-len))]
    (tuple-item ids (vec (repeat n 0)))))

(defn- next-tuple-indices
  [id-count indices]
  (for [position (range (count indices))
        :let [next-index (inc (nth indices position))]
        :when (< next-index id-count)]
    (assoc indices position next-index)))

(defn- enqueue-next-tuples
  [ids queue seen indices]
  (reduce (fn [[queue seen] next-indices]
            (if (contains? seen next-indices)
              [queue seen]
              [(conj queue (tuple-item ids next-indices))
               (conj seen next-indices)]))
          [queue seen]
          (next-tuple-indices (count ids) indices)))

(defn node-tuples
  "Enumerate graph value-node tuples in Python NodeTupleEnumerator order."
  [g {:keys [max-tuple-len max-results]
      :or {max-tuple-len 2
           max-results 1000}}]
  (let [ids (vec (graph-value-order g))]
    (if (empty? ids)
      []
      (loop [queue (into (tuple-queue) (starting-tuples ids max-tuple-len))
             seen (set (map :indices queue))
             result []]
        (if (or (empty? queue)
                (>= (count result) max-results))
          result
          (let [[item queue] (pop-tuple queue)
                [queue seen] (enqueue-next-tuples ids queue seen (:indices item))]
            (recur queue seen (conj result item))))))))

(defn- node-condition-key
  [g node-ids]
  (mapv #(spec/value-spec (get-in g [:nodes % :value]))
        node-ids))

(defn- below?
  [g root-id node-id]
  (contains? (set (graph/walk g root-id {:above? false
                                         :below? true
                                         :values? true
                                         :operators? true
                                         :include-self? true}))
             node-id))

(defn- invalid-attachment?
  [g gen-cond conditioned-nodes]
  (let [root-id (first (graph/roots g))]
    (boolean
     (some (fn [[position node-id]]
             (cond
               (and (= -1 position)
                    (seq (:options (graph/node g node-id))))
               true

               (and (= -1 position)
                    root-id
                    (not (below? g root-id node-id)))
               true

               (and (not= -1 position)
                    (= root-id node-id))
               true

               :else false))
           (map vector gen-cond conditioned-nodes)))))

(defn- build-rank
  [item]
  [(:dl item) (:order item)])

(defn- enqueue
  [queue item]
  (conj queue item))

(defn- empty-queue
  []
  (sorted-set-by (fn [left right]
                   (compare (build-rank left)
                            (build-rank right)))))

(defn expand-graph
  [wb queue graph memory dl {:keys [max-dag-dl max-tuple-len max-node-tuples]
                             :or {max-dag-dl Double/POSITIVE_INFINITY
                                  max-tuple-len 2
                                  max-node-tuples 1000}} order]
  (reduce
   (fn [[queue order] {:keys [nodes]}]
     (let [k (node-condition-key graph nodes)
           elements (get (:elements-by-condition-key wb) k)]
       (reduce
        (fn [[queue order] [element-index element]]
          (let [new-dl (+ dl (double (:dl element)))]
            (if (or (> new-dl max-dag-dl)
                    (invalid-attachment? graph (:gen-cond element) nodes))
              [queue order]
              (let [order (inc order)
                    build-info (delayed/build-info
                                {:dl new-dl
                                 :graph graph
                                 :memory memory
                                 :conditioned-nodes nodes
                                 :condition-key k
                                 :element-index element-index})]
                [(enqueue queue {:dl new-dl
                                 :order order
                                 :build-info build-info})
                 order]))))
        [queue order]
        (map-indexed vector elements))))
   [queue order]
   (node-tuples graph {:max-tuple-len max-tuple-len
                       :max-results max-node-tuples})))

(defn- result-summary
  [graph memory build-dl target-ids value-dl-cache]
  {:graph graph
   :memory memory
   :build-dl build-dl
   :dl (mdl/graph-dl graph {:value-dl-cache value-dl-cache})
   :target-ids target-ids})

(defn realize-selected
  "Attach selected target expressions to a materialized Wunderbaum summary.

  Candidate enumeration intentionally leaves `:selected` absent because most
  yielded graphs are only scored and discarded by the greedy Alice loop.
  "
  [summary]
  (if (contains? summary :selected)
    summary
    (let [context (mdl/scoring-context)]
      (assoc summary
             :selected
             (into {}
                   (map (fn [id]
                          [id (mdl/selected-expression (:graph summary)
                                                       id
                                                       context)]))
                   (:target-ids summary))))))

(defn- pop-queue
  [queue]
  (let [item (first queue)]
    [item (disj queue item)]))

(defn- under-pop-limit?
  [popped max-popped]
  (or (nil? max-popped)
      (< popped max-popped)))

(defn- frontier-active?
  [queue popped yielded {:keys [max-popped max-yields]
                         :or {max-yields Long/MAX_VALUE}}]
  (and (seq queue)
       (< yielded max-yields)
       (under-pop-limit? popped max-popped)))

(defn- initial-frontier
  [wb targets opts]
  (let [{:keys [graph memory target-ids]} (initial-state targets)
        [queue order] (expand-graph wb (empty-queue) graph memory 0.0 opts 0)]
    {:queue queue
     :order order
     :seen #{}
     :value-dl-cache (or (:value-dl-cache opts) (atom {}))
     :target-ids target-ids}))

(defn- materialize-build
  [wb seen build-info]
  (delayed/delayed-dag-build-with-seen build-info
                                       (:elements-by-condition-key wb)
                                       seen
                                       {:registry (:registry wb)}))

(defn- add-materialized-result
  [wb opts target-ids value-dl-cache build-dl [queue order yielded emitted] {:keys [graph memory]}]
  (let [summary (result-summary graph memory build-dl target-ids value-dl-cache)
        [queue order] (expand-graph wb queue graph memory build-dl opts order)]
    [queue order (inc yielded) (conj emitted summary)]))

(defn- process-frontier-item
  [wb opts seen target-ids value-dl-cache item queue order yielded]
  (let [build-info (:build-info item)
        {:keys [seen results]} (materialize-build wb seen build-info)
        [queue order yielded emitted]
        (reduce (partial add-materialized-result wb opts target-ids value-dl-cache (:dl item))
                [queue order yielded []]
                results)]
    [seen queue order yielded emitted]))

(defn- walk-frontier
  [wb opts seen target-ids value-dl-cache queue order popped yielded]
  (lazy-seq
   (when (frontier-active? queue popped yielded opts)
     (let [[item queue] (pop-queue queue)
           [seen queue order yielded emitted]
           (process-frontier-item wb
                                  opts
                                  seen
                                  target-ids
                                  value-dl-cache
                                  item
                                  queue
                                  order
                                  yielded)]
       (concat emitted
               (walk-frontier wb
                              opts
                              seen
                              target-ids
                              value-dl-cache
                              queue
                              order
                              (inc popped)
                              yielded))))))

(defn iterate
  "Yield materialized Wunderbaum candidate graphs in frontier order.

  This is the first straight-port slice: it uses Python-shaped conditioned
  attachments and delayed graph building, but it is not yet the bounded local
  `RewriteOperator` variant.
  "
  ([wb targets]
   (iterate wb targets {}))
  ([wb targets opts]
   (let [{:keys [queue order seen target-ids value-dl-cache]}
         (initial-frontier wb targets opts)]
     (walk-frontier wb opts seen target-ids value-dl-cache queue order 0 0))))
