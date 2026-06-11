(ns ciwi.alice.wunderbaum.context
  (:require [ciwi.alice :as alice]
            [ciwi.alice.declarations :as alice-declarations]
            [ciwi.cache :as cache]
            [ciwi.graph-optimize :as graph-optimize]
            [ciwi.mdl :as mdl]
            [ciwi.spec :as spec]
            [ciwi.value :as value]
            [ciwi.wunderbaum :as wunderbaum]))

(defn- require-registry
  [registry]
  (when-not (map? registry)
    (throw (ex-info "Alice Wunderbaum requires an injected operator registry"
                    {:registry registry})))
  registry)

(defn declarations-for-registry
  ([registry]
   (declarations-for-registry registry {}))
  ([registry {:keys [operator-ids counts]
              :or {counts {}}}]
   (let [registry (require-registry registry)
         requested (some-> operator-ids set)
         available? (fn [op-id]
                      (and (contains? registry op-id)
                           (or (nil? requested)
                               (contains? requested op-id))))
         declarations-by-op (group-by :op alice-declarations/operator-declarations)
         table-order (distinct (map :op alice-declarations/operator-declarations))
         op-order (or operator-ids table-order)]
     (let [declarations (->> op-order
                             (filter available?)
                             (mapcat #(get declarations-by-op %)))
           op-count (count (set (map :op declarations)))
           op-dl (if (pos? op-count)
                   (Math/ceil (value/jelias op-count))
                   0.0)]
       (mapv (fn [declaration]
               (let [k [(:op declaration)
                        (:input-specs declaration)
                        (:output-spec declaration)]]
                 (assoc declaration
                        :count (get counts (:op declaration) 0)
                        :dl op-dl
                        :jitter (get alice-declarations/python-dl-jitter k 0.0))))
             declarations)))))

(defn- specified-value
  [x opts]
  (let [v (value/value x opts)]
    (assoc v :spec (or (:spec v)
                       (spec/infer-spec v)))))

(defn target-value
  [x]
  (specified-value x {:permeable? false}))

(defn free-value
  [x]
  (specified-value x {}))

(defn free-anchor-value
  [x]
  (specified-value x {:dummy? true}))

(defn- task-values
  [task]
  {:targets (mapv target-value (:targets task))
   :free-values (mapv free-value (:free-values task))})

(defn- score-target-dl
  [graph target-ids cache-context score-target-count]
  (let [context (cache/scoring-context cache-context)]
    (if score-target-count
      (reduce + 0.0
              (map #(:dl (mdl/node-dl graph % context))
                   (take score-target-count target-ids)))
      (mdl/graph-dl graph context))))

(defn- optimized-candidate-summary
  [summary]
  (let [cache-context (:cache-context summary)
        optimization (graph-optimize/try-to-optimize
                      (:graph summary)
                      (:memory summary)
                      {:root-id (:primary-root-id summary)
                       :section-ids (:root-order summary)
                       :value-dl-cache (cache/value-dl-cache cache-context)})]
    (if-let [optimized-memory (:memory optimization)]
      (let [optimized-graph (graph-optimize/apply-memory-values
                             (:graph summary)
                             optimized-memory)]
        (assoc summary
               :graph optimized-graph
               :memory optimized-memory
               :optimizer-result optimization
               :dl (score-target-dl optimized-graph
                                    (:target-ids summary)
                                    cache-context
                                    (:score-target-count summary))))
      summary)))

(defn- with-candidate-transform
  [opts]
  (cond
    (:candidate-transform opts)
    opts

    (:optimize-candidates? opts)
    (assoc opts :candidate-transform optimized-candidate-summary)

    :else
    opts))

(defn target-ids
  [n]
  (mapv #(keyword (str "target" %)) (range n)))

(defn task-search-context
  [task opts]
  (let [{:keys [registry ops-with-counts]} opts
        registry (require-registry registry)
        cache-context (cache/search-context (:cache-context opts))
        value-dl-cache (cache/value-dl-cache cache-context)
        opts (-> opts
                 (assoc :cache-context cache-context)
                 with-candidate-transform)
        ops-with-counts (or ops-with-counts
                            (declarations-for-registry registry opts))
        {:keys [targets free-values]} (task-values task)
        all-values (vec (concat targets free-values))
        initial-dl (reduce + 0.0
                           (map #(value/desc-len-cached value-dl-cache %)
                                targets))
        wb (wunderbaum/wunderbaum {:registry registry
                                   :ops-with-counts ops-with-counts})]
    {:opts opts
     :ops-with-counts ops-with-counts
     :target-count (count targets)
     :targets targets
     :free-values free-values
     :all-values all-values
     :initial-dl initial-dl
     :cache-context cache-context
     :wunderbaum wb}))

(defn- parallel-search?
  [opts]
  (> (long (or (:parallelism opts)
               (:num-workers opts)
               1))
     1))

(defn- iterate-candidates
  [wunderbaum values opts]
  (if (parallel-search? opts)
    (wunderbaum/iterate-parallel wunderbaum values opts)
    (wunderbaum/iterate wunderbaum values opts)))

(defn candidate-seq
  ([context]
   (candidate-seq context (:all-values context)))
  ([{:keys [wunderbaum opts]} values]
   (iterate-candidates wunderbaum values opts))
  ([{:keys [wunderbaum opts]} values candidate-opts]
   (iterate-candidates wunderbaum values (merge opts candidate-opts))))

(defn first-candidate-at-rate
  [initial-dl threshold-rate candidates]
  (loop [remaining candidates
         consumed 0]
    (if-let [candidate (first remaining)]
      (let [consumed (inc consumed)
            rate (alice/compression-rate initial-dl (:dl candidate))]
        (if (>= rate threshold-rate)
          {:candidate (wunderbaum/realize-selected candidate)
           :candidates-consumed consumed
           :stop-reason :threshold-reached
           :compression-rate rate}
          (recur (rest remaining) consumed)))
      {:candidate nil
       :candidates-consumed consumed
       :stop-reason :exhausted
       :compression-rate 0.0})))
