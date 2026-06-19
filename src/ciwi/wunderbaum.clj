(ns ciwi.wunderbaum
  (:refer-clojure :exclude [iterate])
  (:require [ciwi.cache :as cache]
            [ciwi.delayed-builder :as delayed]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.propagation :as propagation]
            [ciwi.search.trace :as trace]
            [ciwi.spec :as spec]
            [ciwi.wunderbaum.attachment :as attachment]
            [ciwi.wunderbaum.declarations :as declarations]
            [ciwi.wunderbaum.lazy-frontier :as lazy-frontier]
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

(defn- next-frontier-order
  [opts order]
  (if-let [next-order! (:next-order! opts)]
    [(long (next-order!)) order]
    (let [order (inc order)]
      [order order])))

(defn- empty-queue
  []
  (sorted-set-by (fn [left right]
                   (compare (build-rank left)
                            (build-rank right)))))

(defn- stats-atom
  [context]
  (or (:stats context)
      (:wunderbaum-stats-atom context)))

(defn- bump
  [m k]
  (update m k (fnil inc 0)))

(defn- bump-by
  [m k item]
  (update m k (fn [counts]
                (update (or counts {}) item (fnil inc 0)))))

(defn- add-stat!
  [context k delta]
  (when-let [stats (stats-atom context)]
    (swap! stats update k (fnil + 0) delta)))

(defn- max-stat!
  [context k value]
  (when-let [stats (stats-atom context)]
    (swap! stats update k (fnil max 0) value)))

(defn- frontier-condition-stat-key
  [element condition-key]
  [(:id element) (:gen-cond element) condition-key])

(defn- record-frontier-decision!
  [opts element condition-key decision]
  (when-let [stats (stats-atom opts)]
    (let [op-id (:id element)
          condition-stat (frontier-condition-stat-key element condition-key)]
      (swap! stats
             (fn [m]
               (let [m (-> m
                           (bump :frontier-considered)
                           (bump-by :frontier-considered-by-op op-id)
                           (bump-by :frontier-considered-by-condition
                                    condition-stat)
                           (bump-by :frontier-decisions decision)
                           (bump-by :frontier-decisions-by-op
                                    [op-id decision]))]
                 (if (= :enqueued decision)
                   (-> m
                       (bump :frontier-kept)
                       (bump-by :frontier-kept-by-op op-id)
                       (bump-by :frontier-kept-by-condition condition-stat))
                   m)))))))

(defn- record-frontier-pop!
  [opts item]
  (when-let [stats (stats-atom opts)]
    (let [op-id (:operator-id item)]
      (swap! stats
             (fn [m]
               (cond-> (bump m :frontier-popped)
                 op-id (bump-by :frontier-popped-by-op op-id)))))))

(defn- record-materialization!
  [opts item result-count]
  (let [result-count (long result-count)]
    (add-stat! opts :materialized-results result-count)
    (when (zero? result-count)
      (add-stat! opts :empty-materializations 1))
    (when-let [op-id (:operator-id item)]
      (when-let [stats (stats-atom opts)]
        (swap! stats
               (fn [m]
                 (let [m (update m
                                 :materialized-results-by-op
                                 (fn [counts]
                                   (update (or counts {})
                                           op-id
                                           (fnil + 0)
                                           result-count)))]
                   (cond-> m
                     (zero? result-count)
                     (bump-by :empty-materializations-by-op op-id)))))))))

(defn- keep-frontier-item?
  [opts info]
  (if-let [pred (:frontier-predicate opts)]
    (boolean (pred info))
    true))

(defn- lazy-frontier?
  [opts]
  (or (:lazy-frontier? opts)
      (= :lazy (:frontier-mode opts))))

(defn- lazy-frontier-callbacks
  []
  {:node-condition-key node-condition-key
   :record-frontier-decision! record-frontier-decision!
   :keep-frontier-item? keep-frontier-item?
   :next-frontier-order next-frontier-order})

(defn expand-graph
  [wb queue graph memory dl {:keys [max-dag-dl max-tuple-len max-node-tuples
                                    primary-root-id root-order free-root-ids]
                             :or {max-dag-dl Double/POSITIVE_INFINITY
                                  max-tuple-len 2
                                  max-node-tuples 1000}
                             :as opts} order]
  (if (lazy-frontier? opts)
    (lazy-frontier/expand-graph wb
                                queue
                                graph
                                memory
                                dl
                                opts
                                order
                                (lazy-frontier-callbacks))
    (let [attachment-context (attachment/context graph
                                                 primary-root-id
                                                 free-root-ids
                                                 opts)
          tuple-root-order (if (:recent-roots-first? opts)
                             (let [fixed-roots (vec (distinct
                                                     (concat (when primary-root-id
                                                               [primary-root-id])
                                                             free-root-ids)))
                                   fixed-root-set (set fixed-roots)]
                               (vec (concat fixed-roots
                                            (remove fixed-root-set
                                                    (reverse (graph/roots graph))))))
                             root-order)
          preferred-nodes (when-let [preferred-node-fn (:preferred-node-fn opts)]
                            (preferred-node-fn {:graph graph
                                                :memory memory
                                                :opts opts}))]
      (reduce
       (fn [[queue order] {:keys [nodes]}]
         (let [k (node-condition-key graph nodes)
               elements (get (:elements-by-condition-key wb) k)]
           (reduce
            (fn [[queue order] [element-index element]]
              (let [new-dl (+ dl (double (:dl element)))
                    decision (cond
                               (> new-dl max-dag-dl)
                               :max-dag-dl

                               (attachment/invalid? graph
                                                    (:gen-cond element)
                                                    nodes
                                                    attachment-context)
                               :invalid-attachment

                               (not (keep-frontier-item?
                                     opts
                                     {:graph graph
                                      :memory memory
                                      :nodes nodes
                                      :condition-key k
                                      :element element
                                      :dl new-dl}))
                               :frontier-predicate

                               :else
                               :enqueued)]
                (record-frontier-decision! opts element k decision)
                (if (not= :enqueued decision)
                  [queue order]
                  (let [[item-order order] (next-frontier-order opts order)
                        build-info (delayed/build-info
                                    {:dl new-dl
                                     :graph graph
                                     :memory memory
                                     :conditioned-nodes nodes
                                     :condition-key k
                                     :element-index element-index})]
                    [(enqueue queue {:dl new-dl
                                     :order item-order
                                     :operator-id (:id element)
                                     :condition-key k
                                     :gen-cond (:gen-cond element)
                                     :input-specs (:input-specs element)
                                     :output-spec (:output-spec element)
                                     :build-info build-info})
                     order]))))
            [queue order]
            (map-indexed vector elements))))
       [queue order]
       (tuples/node-tuples graph {:max-tuple-len max-tuple-len
                                  :max-results max-node-tuples
                                  :root-order tuple-root-order
                                  :preferred-nodes preferred-nodes})))))

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

(defn- sample-index
  [x]
  (cond
    (number? x) x
    (sequential? x) (or (last x) 1)
    :else 1))

(defn- pop-queue
  [queue]
  (loop [queue queue]
    (when-let [item (first queue)]
      (let [queue (disj queue item)]
        (if (lazy-frontier/cursor? item)
          (let [[build-item next-cursor] (lazy-frontier/pop-cursor item)
                queue (cond-> queue next-cursor (enqueue next-cursor))]
            (if build-item
              [build-item queue]
              (recur queue)))
          [item queue])))))

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
        (do
          (add-stat! opts :candidate-predicate-rejected 1)
          [queue order yielded emitted false])
        (let [summary (transform-result-summary summary opts)
              emit? (or (not threshold?)
                        (< (:dl summary) (double threshold-dl)))]
          (cond
            (and threshold? emit?)
            (do
              (when (trace/enabled? opts)
                (trace/emit! opts
                             :accepted-candidate
                             {:dl (:dl summary)
                              :build-dl build-dl
                              :threshold? true
                              :target-ids target-ids
                              :summary summary}
                             (inc yielded)))
              (add-stat! opts :emitted 1)
              (request-halt! opts)
              (reduced [queue order (inc yielded) (conj emitted summary) true]))

            :else
            (let [[queue order] (expand-graph wb queue graph memory build-dl opts order)]
              (if emit?
                (do
                  (when (trace/enabled? opts)
                    (trace/emit! opts
                                 :accepted-candidate
                                 {:dl (:dl summary)
                                  :build-dl build-dl
                                  :threshold? false
                                  :target-ids target-ids
                                  :summary summary}
                                 (inc yielded)))
                  (add-stat! opts :emitted 1)
                  [queue order (inc yielded) (conj emitted summary) false])
                (do
                  (add-stat! opts :below-threshold-results 1)
                  [queue order yielded emitted false])))))))))

(defn- process-frontier-item
  [wb opts seen target-ids cache-context item queue order yielded]
  (let [build-info (:build-info item)
        _ (record-frontier-pop! opts item)
        {:keys [seen results]} (materialize-build wb
                                                  seen
                                                  build-info
                                                  cache-context)
        result-count (count results)
        _ (record-materialization! opts item result-count)
        _ (when (trace/enabled? opts)
            (trace/emit! opts
                         :frontier-materialized
                         {:frontier-order (:order item)
                          :frontier-dl (:dl item)
                          :operator-id (:operator-id item)
                          :condition-key (:condition-key item)
                          :gen-cond (:gen-cond item)
                          :input-specs (:input-specs item)
                          :output-spec (:output-spec item)
                          :result-count result-count}
                         (sample-index (:order item))))
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

(defn- now-ns
  []
  (System/nanoTime))

(defn- item-rank
  [item]
  (build-rank item))

(defn- expansion-item?
  [item]
  (= :expand (:kind item)))

(defn- build-item?
  [item]
  (not (expansion-item? item)))

(defn- rank-before?
  [left right]
  (neg? (compare left right)))

(defn- pending-candidate-queue
  []
  (sorted-set-by (fn [left right]
                   (compare (:candidate-rank left)
                            (:candidate-rank right)))))

(defn- frontier-batch-size
  [opts]
  (long (max 1 (or (:frontier-batch-size opts) 4))))

(defn- min-element-dl
  [wb]
  (let [dl (reduce min
                   Double/POSITIVE_INFINITY
                   (map :dl
                        (mapcat val (:elements-by-condition-key wb))))]
    (when (< dl Double/POSITIVE_INFINITY)
      dl)))

(defn- global-search-state
  [queue order opts]
  (let [stats (:wunderbaum-stats-atom opts)]
    (when stats
      (swap! stats merge {:strategy :global-best-first
                          :worker-count (:worker-count opts)
                          :initial-frontier-count (count queue)}))
    {:lock (Object.)
     :queue (atom queue)
     :order-counter (java.util.concurrent.atomic.AtomicLong. (long order))
     :candidate-counter (java.util.concurrent.atomic.AtomicLong. 0)
     :seen (cache/cache-store)
     :popped (atom 0)
     :yielded (atom 0)
     :active-ranks (atom (sorted-set))
     :pending-candidates (atom (pending-candidate-queue))
     :done? (atom false)
     :results (atom [])
     :stats stats}))

(defn- global-done?
  [state]
  @(:done? state))

(defn- mark-global-done!
  [{:keys [lock done?]}]
  (locking lock
    (reset! done? true)
    (.notifyAll lock)))

(defn- earlier-frontier-work?
  [{:keys [queue active-ranks]} frontier-rank]
  (boolean
   (or (when-let [item (first @queue)]
         (rank-before? (item-rank item) frontier-rank))
       (when-let [active-rank (first @active-ranks)]
         (rank-before? active-rank frontier-rank)))))

(defn- commit-ready-candidates!
  [{:keys [pending-candidates results yielded done?] :as state} opts]
  (loop [committed? false]
    (if (or @done? (empty? @pending-candidates))
      committed?
      (let [candidate (first @pending-candidates)
            max-yields (long (or (:max-yields opts) Long/MAX_VALUE))]
        (if (earlier-frontier-work? state (:frontier-rank candidate))
          committed?
          (do
            (swap! pending-candidates disj candidate)
            (swap! results conj (:summary candidate))
            (add-stat! state :emitted 1)
            (add-stat! state
                       :commit-wait-ns
                       (- (now-ns) (:found-ns candidate)))
            (let [yielded-count (swap! yielded inc)]
              (when (trace/enabled? opts)
                (trace/emit! opts
                             :accepted-candidate
                             {:dl (:dl (:summary candidate))
                              :build-dl (:build-dl (:summary candidate))
                              :threshold? (:threshold? candidate)
                              :target-ids (:target-ids (:summary candidate))
                              :summary (:summary candidate)}
                             yielded-count))
              (when (or (:threshold? candidate)
                        (<= max-yields yielded-count))
                (reset! done? true)))
            (recur true)))))))

(defn- min-pending-frontier-rank
  [{:keys [pending-candidates]}]
  (some-> (first @pending-candidates) :frontier-rank))

(defn- item-earlier-than-pending?
  [state item]
  (if-let [cutoff (min-pending-frontier-rank state)]
    (rank-before? (item-rank item) cutoff)
    true))

(defn- cancel-item-work?
  [state item]
  (or (global-done? state)
      (not (item-earlier-than-pending? state item))))

(defn- dispatchable-item?
  [state item]
  (item-earlier-than-pending? state item))

(defn- pop-dispatch-batch
  [queue n cutoff]
  (loop [queue queue
         n n
         items []]
    (if (or (zero? n)
            (empty? queue)
            (and cutoff
                 (not (rank-before? (item-rank (first queue)) cutoff))))
      [queue items]
      (let [[item queue] (pop-queue queue)]
        (recur queue (dec n) (conj items item))))))

(defn- take-global-frontier-batch!
  [{:keys [lock queue popped active-ranks done?] :as state} opts]
  (locking lock
    (let [wait-start (now-ns)]
      (loop []
        (commit-ready-candidates! state opts)
        (cond
          @done?
          (do
            (add-stat! state :cancelled-takes 1)
            nil)

          (and (seq @queue)
               (under-pop-limit? @popped (:max-popped opts))
               (dispatchable-item? state (first @queue)))
          (let [remaining-pop-budget (if-let [max-popped (:max-popped opts)]
                                       (- (long max-popped) @popped)
                                       Long/MAX_VALUE)
                n (min (frontier-batch-size opts) remaining-pop-budget)
                [next-queue items] (pop-dispatch-batch
                                    @queue
                                    n
                                    (min-pending-frontier-rank state))
                ranks (map item-rank items)
                build-count (count (filter build-item? items))
                expansion-count (- (count items) build-count)]
            (reset! queue next-queue)
            (swap! popped + build-count)
            (swap! active-ranks into ranks)
            (add-stat! state :queue-wait-ns (- (now-ns) wait-start))
            (add-stat! state :frontier-popped build-count)
            (add-stat! state :expansion-tasks-popped expansion-count)
            (max-stat! state :max-active-frontier-items (count @active-ranks))
            items)

          (and (or (empty? @queue)
                   (not (under-pop-limit? @popped (:max-popped opts))))
               (empty? @active-ranks))
          (do
            (commit-ready-candidates! state opts)
            (when (and (empty? @(:pending-candidates state))
                       (not @done?))
              (reset! done? true))
            (.notifyAll lock)
            nil)

          :else
          (do
            (.wait lock 10)
            (recur)))))))

(defn- enqueue-global-frontier*!
  [state items]
  (when (seq items)
    (swap! (:queue state) into items)
    (add-stat! state :frontier-enqueued (count items))))

(defn- enqueue-pending-candidates*!
  [{:keys [pending-candidates]} candidates]
  (when (seq candidates)
    (swap! pending-candidates into candidates)))

(defn- finish-global-frontier-item!
  [{:keys [lock active-ranks done?] :as state} opts item expansions candidates]
  (locking lock
    (swap! active-ranks disj (item-rank item))
    (when-not @done?
      (enqueue-global-frontier*! state expansions)
      (enqueue-pending-candidates*! state candidates))
    (commit-ready-candidates! state opts)
    (when (and (empty? @active-ranks)
               (empty? @(:queue state))
               (empty? @(:pending-candidates state))
               (not @done?))
      (reset! done? true))
    (.notifyAll lock)))

(defn- admit-materialized-result!
  [state result]
  (let [k (delayed/result-key result)]
    (nil? (cache/put-if-absent! (:seen state) k true))))

(defn- materialize-build-concurrent
  [state wb opts item build-info cache-context]
  (let [t0 (now-ns)
        raw-results (delayed/raw-delayed-dag-build
                     build-info
                     (:elements-by-condition-key wb)
                     {:registry (:registry wb)
                      :cache-context cache-context})
        t1 (now-ns)
        [duplicate-count admitted]
        (reduce (fn [[duplicates admitted] result]
                  (let [d0 (now-ns)
                        admitted? (admit-materialized-result! state result)]
                    (add-stat! state :dedupe-ns (- (now-ns) d0))
                    (if admitted?
                      [duplicates (conj! admitted result)]
                      [(inc duplicates) admitted])))
                [0 (transient [])]
                raw-results)]
    (add-stat! state :materialization-ns (- t1 t0))
    (add-stat! state :materialized-results (count raw-results))
    (add-stat! state :duplicate-results duplicate-count)
    (when (trace/enabled? opts)
      (trace/emit! opts
                   :frontier-materialized
                   {:frontier-order (:order item)
                    :frontier-dl (:dl item)
                    :result-count (count raw-results)
                    :admitted-count (count admitted)
                    :duplicate-count duplicate-count}
                   (:order item)))
    (persistent! admitted)))

(defn- expand-global-result
  [state wb opts graph memory build-dl]
  (let [t0 (now-ns)
        [items _order] (expand-graph wb
                                     (empty-queue)
                                     graph
                                     memory
                                     build-dl
                                     opts
                                     0)]
    (add-stat! state :expansion-ns (- (now-ns) t0))
    (add-stat! state :expanded-results 1)
    items))

(defn- deferred-expansion-item
  [state wb graph memory build-dl]
  (when-let [min-dl (min-element-dl wb)]
    (add-stat! state :deferred-expansions 1)
    {:kind :expand
     :dl (+ (double build-dl) (double min-dl))
     :order (.incrementAndGet (:order-counter state))
     :expand-info {:graph graph
                   :memory memory
                   :build-dl build-dl}}))

(defn- candidate-record
  [state item summary threshold?]
  (let [result-order (.incrementAndGet (:candidate-counter state))
        frontier-rank (item-rank item)]
    {:frontier-rank frontier-rank
     :candidate-rank (conj frontier-rank result-order)
     :summary summary
     :threshold? threshold?
     :found-ns (now-ns)}))

(defn- process-global-materialized-result
  [state wb opts target-ids cache-context item {:keys [graph memory]}]
  (when-not (cancel-item-work? state item)
    (let [{:keys [threshold-dl]} opts
          threshold? (threshold-active? threshold-dl)
          score-start (now-ns)
          summary (result-summary graph
                                  memory
                                  (:dl item)
                                  target-ids
                                  cache-context
                                  opts)
          score-end (now-ns)]
      (add-stat! state :scoring-ns (- score-end score-start))
      (if (or (cancel-item-work? state item)
              (not (keep-result-summary? summary opts)))
        {:expansions []
         :candidates []}
        (let [transform-start (now-ns)
              summary (transform-result-summary summary opts)
              transform-end (now-ns)
              emit? (or (not threshold?)
                        (< (:dl summary) (double threshold-dl)))
              expansions (if (or (and threshold? emit?)
                                 (cancel-item-work? state item))
                           []
                           (if-let [deferred (deferred-expansion-item state
                                                                      wb
                                                                      graph
                                                                      memory
                                                                      (:dl item))]
                             [deferred]
                             []))
              candidates (if emit?
                           [(candidate-record state item summary threshold?)]
                           [])]
          (add-stat! state :candidate-transform-ns
                     (- transform-end transform-start))
          {:expansions expansions
           :candidates candidates})))))

(defn- combine-global-work
  [left right]
  {:expansions (into (:expansions left) (:expansions right))
   :candidates (into (:candidates left) (:candidates right))})

(defn- process-global-frontier-item
  [state wb opts target-ids cache-context item]
  (if (cancel-item-work? state item)
    (do
      (add-stat! state :cancelled-items 1)
      {:expansions []
       :candidates []})
    (if (expansion-item? item)
      (let [{:keys [graph memory build-dl]} (:expand-info item)]
        {:expansions (expand-global-result state wb opts graph memory build-dl)
         :candidates []})
      (let [results (materialize-build-concurrent state
                                                  wb
                                                  opts
                                                  item
                                                  (:build-info item)
                                                  cache-context)]
        (reduce (fn [work result]
                  (if (cancel-item-work? state item)
                    (do
                      (add-stat! state :cancelled-results 1)
                      (reduced work))
                    (combine-global-work
                     work
                     (process-global-materialized-result state
                                                         wb
                                                         opts
                                                         target-ids
                                                         cache-context
                                                         item
                                                         result))))
                {:expansions []
                 :candidates []}
                results)))))

(defn- global-worker
  [state wb opts target-ids cache-context]
  (reify java.util.concurrent.Callable
    (call [_]
      (loop []
        (when-let [items (seq (take-global-frontier-batch! state opts))]
          (doseq [item items]
            (let [{:keys [expansions candidates]}
                  (if (cancel-item-work? state item)
                    {:expansions []
                     :candidates []}
                    (try
                      (process-global-frontier-item state
                                                    wb
                                                    opts
                                                    target-ids
                                                    cache-context
                                                    item)
                      (catch Throwable t
                        (mark-global-done! state)
                        (throw t))))]
              (finish-global-frontier-item! state
                                            opts
                                            item
                                            expansions
                                            candidates)))
          (recur)))
      nil)))

(defn- search-global-frontier
  [wb opts target-ids cache-context queue order n-workers]
  (let [opts (assoc opts :worker-count n-workers)
        state (global-search-state queue order opts)
        opts (assoc opts
                    :next-order! #(.incrementAndGet (:order-counter state)))
        executor (java.util.concurrent.Executors/newFixedThreadPool n-workers)]
    (try
      (let [futures (mapv (fn [_]
                            (.submit executor
                                     (global-worker state
                                                    wb
                                                    opts
                                                    target-ids
                                                    cache-context)))
                          (range n-workers))]
        (doseq [future futures]
          (.get future))
        @(:results state))
      (finally
        (mark-global-done! state)
        (.shutdownNow executor)))))

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
       (do
         (when (lazy-frontier? opts)
           (throw (ex-info "Lazy frontier mode is currently serial-only"
                           {:parallelism n-workers
                            :frontier-mode (:frontier-mode opts)})))
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
                                         partitions))))))))

(defn iterate-global-best-first
  "Yield materialized candidates using a coordinated global frontier.

  This is an experimental JVM-threaded strategy. It preserves a single shared
  best-first frontier and global pop/yield counters, but it is not the Python
  parity implementation and does not guarantee serial result order when
  different workers materialize candidates at different speeds.
  "
  ([wb targets]
   (iterate-global-best-first wb targets {}))
  ([wb targets opts]
   (let [n-workers (worker-count opts)]
     (if (<= n-workers 1)
       (iterate wb targets opts)
       (do
         (when (lazy-frontier? opts)
           (throw (ex-info "Lazy frontier mode is currently serial-only"
                           {:parallelism n-workers
                            :frontier-mode (:frontier-mode opts)})))
         (let [{:keys [queue order target-ids cache-context opts]}
               (initial-frontier wb targets opts)]
           (if (empty? queue)
             '()
             (search-global-frontier wb
                                     opts
                                     target-ids
                                     cache-context
                                     queue
                                     order
                                     n-workers))))))))
