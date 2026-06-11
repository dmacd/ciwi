(ns ciwi.wunderbaum
  (:refer-clojure :exclude [iterate])
  (:require [ciwi.cache :as cache]
            [ciwi.delayed-builder :as delayed]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.propagation :as propagation]
            [ciwi.spec :as spec]
            [ciwi.wunderbaum.attachment :as attachment]
            [ciwi.wunderbaum.declarations :as declarations]
            [ciwi.wunderbaum.tuples :as tuples]))

(defrecord Wunderbaum [registry elements-by-condition-key opts])

(defn wunderbaum
  [{:keys [registry ops-with-counts] :as opts}]
  (let [registry (declarations/require-registry registry)]
    (->Wunderbaum registry
                  (declarations/operator-elements-by-condition-key registry
                                                                    ops-with-counts)
                  opts)))

(defn- indexed-id
  [prefix idx]
  (keyword (str (name prefix) idx)))

(defn initial-state
  [targets]
  (let [target-ids (mapv #(indexed-id :target %) (range (count targets)))
        g (-> (reduce (fn [g [id target]]
                        (graph/add-value g id target))
                      (graph/empty-graph)
                      (map vector target-ids targets))
              (graph/set-roots target-ids))
        memory (into {}
                     (map (fn [[id target]]
                            [id (propagation/entry target)]))
                     (map vector target-ids targets))]
    {:graph g
     :memory memory
     :target-ids target-ids}))

(defn- node-condition-key
  [g node-ids]
  (mapv #(spec/value-spec (get-in g [:nodes % :value]))
        node-ids))


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
  [wb queue graph memory dl {:keys [max-dag-dl max-tuple-len max-node-tuples
                                    primary-root-id root-order free-root-ids]
                             :or {max-dag-dl Double/POSITIVE_INFINITY
                                  max-tuple-len 2
                                  max-node-tuples 1000}} order]
  (let [attachment-context (attachment/context graph
                                               primary-root-id
                                               free-root-ids)]
    (reduce
     (fn [[queue order] {:keys [nodes]}]
       (let [k (node-condition-key graph nodes)
             elements (get (:elements-by-condition-key wb) k)]
         (reduce
          (fn [[queue order] [element-index element]]
            (let [new-dl (+ dl (double (:dl element)))]
              (if (or (> new-dl max-dag-dl)
                      (attachment/invalid? graph
                                           (:gen-cond element)
                                           nodes
                                           attachment-context))
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
     (tuples/node-tuples graph {:max-tuple-len max-tuple-len
                                :max-results max-node-tuples
                                :root-order root-order}))))

(defn- score-target-dl
  [graph target-ids cache-context score-target-count]
  (let [context (cache/scoring-context cache-context)]
    (if score-target-count
      (reduce + 0.0
              (map #(:dl (mdl/node-dl graph % context))
                   (take score-target-count target-ids)))
      (mdl/graph-dl graph context))))

(defn- result-summary
  [graph memory build-dl target-ids cache-context
   {:keys [score-target-count primary-root-id root-order free-root-ids]}]
  {:graph graph
   :memory memory
   :build-dl build-dl
   :dl (score-target-dl graph target-ids cache-context score-target-count)
   :target-ids target-ids
   :primary-root-id primary-root-id
   :root-order root-order
   :free-root-ids free-root-ids
   :score-target-count score-target-count
   :cache-context cache-context})

(defn- transform-result-summary
  [summary {:keys [candidate-transform]}]
  (if candidate-transform
    (candidate-transform summary)
    summary))

(defn- keep-result-summary?
  [summary {:keys [candidate-predicate]}]
  (if candidate-predicate
    (boolean (candidate-predicate summary))
    true))

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

(defn- halt-requested?
  [opts]
  (if-let [halted? (:halted? opts)]
    @halted?
    false))

(defn- frontier-active?
  [queue popped yielded {:keys [max-popped max-yields]
                         :or {max-yields Long/MAX_VALUE}
                         :as opts}]
  (and (seq queue)
       (not (halt-requested? opts))
       (< yielded max-yields)
       (under-pop-limit? popped max-popped)))

(defn- threshold-active?
  [threshold-dl]
  (and (some? threshold-dl)
       (< (double threshold-dl) Double/POSITIVE_INFINITY)))

(defn- request-halt!
  [opts]
  (when-let [halted? (:halted? opts)]
    (reset! halted? true)))

(defn- initial-frontier
  [wb targets opts]
  (let [{:keys [graph memory target-ids]} (initial-state targets)
        cache-context (cache/search-context (:cache-context opts))
        opts (assoc opts
                    :primary-root-id (first target-ids)
                    :root-order target-ids
                    :free-root-ids (subvec target-ids 1)
                    :cache-context cache-context)
        [queue order] (expand-graph wb (empty-queue) graph memory 0.0 opts 0)]
    {:queue queue
     :order order
     :seen #{}
     :cache-context cache-context
     :target-ids target-ids
     :opts opts}))

(defn- materialize-build
  [wb seen build-info cache-context]
  (delayed/delayed-dag-build-with-seen build-info
                                       (:elements-by-condition-key wb)
                                       seen
                                       {:registry (:registry wb)
                                        :cache-context cache-context}))

(defn- add-materialized-result
  [wb opts target-ids cache-context build-dl [queue order yielded emitted stop?] {:keys [graph memory]}]
  (if stop?
    (reduced [queue order yielded emitted stop?])
    (let [{:keys [threshold-dl]} opts
          threshold? (threshold-active? threshold-dl)
          summary (result-summary graph
                                  memory
                                  build-dl
                                  target-ids
                                  cache-context
                                  opts)]
      (if-not (keep-result-summary? summary opts)
        [queue order yielded emitted false]
        (let [summary (transform-result-summary summary opts)
              emit? (or (not threshold?)
                        (< (:dl summary) (double threshold-dl)))]
          (cond
            (and threshold? emit?)
            (do
              (request-halt! opts)
              (reduced [queue order (inc yielded) (conj emitted summary) true]))

            :else
            (let [[queue order] (expand-graph wb queue graph memory build-dl opts order)]
              (if emit?
                [queue order (inc yielded) (conj emitted summary) false]
                [queue order yielded emitted false]))))))))

(defn- process-frontier-item
  [wb opts seen target-ids cache-context item queue order yielded]
  (let [build-info (:build-info item)
        {:keys [seen results]} (materialize-build wb
                                                  seen
                                                  build-info
                                                  cache-context)
        [queue order yielded emitted stop?]
        (reduce (partial add-materialized-result wb opts target-ids cache-context (:dl item))
                [queue order yielded [] false]
                results)]
    [seen queue order yielded emitted stop?]))

(defn- walk-frontier
  [wb opts seen target-ids cache-context queue order popped yielded]
  (lazy-seq
   (when (frontier-active? queue popped yielded opts)
     (let [[item queue] (pop-queue queue)
           [seen queue order yielded emitted stop?]
           (process-frontier-item wb
                                  opts
                                  seen
                                  target-ids
                                  cache-context
                                  item
                                  queue
                                  order
                                  yielded)]
       (if stop?
         emitted
         (concat emitted
                 (walk-frontier wb
                                opts
                                seen
                                target-ids
                                cache-context
                                queue
                                order
                                (inc popped)
                                yielded)))))))

(defn- worker-count
  [opts]
  (long (max 1
             (or (:parallelism opts)
                 (:num-workers opts)
                 1))))

(defn- partition-frontier
  [n queue]
  (let [n (worker-count {:parallelism n})]
    (->> queue
         (map-indexed vector)
         (reduce (fn [parts [idx item]]
                   (update parts (mod idx n) conj item))
                 (vec (repeat n [])))
         (remove empty?)
         vec)))

(defn- frontier-order
  [default-order items]
  (reduce (fn [order item]
            (max order (:order item)))
          default-order
          items))

(defn- search-frontier-partition
  [wb opts target-ids cache-context initial-order items]
  (let [queue (into (empty-queue) items)
        order (frontier-order initial-order items)]
    (doall
     (walk-frontier wb
                    opts
                    #{}
                    target-ids
                    cache-context
                    queue
                    order
                    0
                    0))))

(defn- cap-yields
  [results opts]
  (if-let [max-yields (:max-yields opts)]
    (take max-yields results)
    results))

(defn- worker-result
  [wb opts target-ids cache-context order items]
  (reify java.util.concurrent.Callable
    (call [_]
      (search-frontier-partition wb
                                 opts
                                 target-ids
                                 cache-context
                                 order
                                 items))))

(defn- search-frontier-partitions
  [wb opts target-ids cache-context order partitions]
  (let [executor (java.util.concurrent.Executors/newFixedThreadPool
                  (count partitions))]
    (try
      (let [futures (mapv #(.submit executor
                                    (worker-result wb
                                                   opts
                                                   target-ids
                                                   cache-context
                                                   order
                                                   %))
                          partitions)
            results (mapcat #(.get %) futures)]
        (vec (cap-yields results opts)))
      (finally
        (.shutdownNow executor)))))

(defn iterate
  "Yield materialized Wunderbaum candidate graphs in frontier order.

  This is the first straight-port slice: it uses Python-shaped conditioned
  attachments and delayed graph building, but it is not yet the bounded local
  `RewriteOperator` variant.
  "
  ([wb targets]
   (iterate wb targets {}))
  ([wb targets opts]
   (let [{:keys [queue order seen target-ids cache-context opts]}
         (initial-frontier wb targets opts)]
     (walk-frontier wb
                    opts
                    seen
                    target-ids
                    cache-context
                    queue
                    order
                    0
                    0))))

(defn iterate-parallel
  "Yield materialized Wunderbaum candidate graphs from partitioned frontiers.

  This mirrors Python Wunderbaum's first parallel shape: split the current
  frontier across worker-local searches, keep operator registries and search
  opts injected, and let each worker own its queue and seen set. It is not yet
  the later resource-bounded local rewrite interface.
  "
  ([wb targets]
   (iterate-parallel wb targets {}))
  ([wb targets opts]
   (let [n-workers (worker-count opts)]
     (if (<= n-workers 1)
       (iterate wb targets opts)
       (let [{:keys [queue order target-ids cache-context opts]}
             (initial-frontier wb targets opts)
             opts (if (threshold-active? (:threshold-dl opts))
                    (assoc opts :halted? (atom false))
                    opts)
             partitions (partition-frontier n-workers queue)]
         (if (empty? partitions)
           '()
           (search-frontier-partitions wb
                                       opts
                                       target-ids
                                       cache-context
                                       order
                                       partitions)))))))
