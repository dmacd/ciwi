(ns ciwi.graph-optimize
  (:require [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.hashing :as hashing]
            [ciwi.optimize :as optimize]
            [ciwi.propagation :as propagation]
            [ciwi.value :as value]))

(def max-opt-array-len 10)

(defn- root-id
  [g {:keys [root-id section-ids]}]
  (or root-id
      (first (graph/roots g))
      (first section-ids)
      (throw (ex-info "try-to-optimize needs a root id"
                      {:roots (graph/roots g)
                       :section-ids section-ids}))))

(defn- entry-value
  [mem id]
  (some-> mem (get id) :value))

(defn- value-present?
  [mem id]
  (some? (value/datum (entry-value mem id))))

(defn- preserve-value-metadata
  [old-value data]
  (value/value data {:name (:name old-value)
                     :spec (:spec old-value)
                     :permeable? (:permeable? old-value)
                     :dummy? (:dummy? old-value)}))

(defn- assoc-entry-value
  [mem id data]
  (let [old-value (entry-value mem id)]
    (assoc mem id (propagation/entry false
                                     (preserve-value-metadata old-value data)))))

(defn apply-memory-values
  "Return `g` with value-node outputs replaced by matching values in `mem`."
  [g mem]
  (reduce-kv (fn [acc id entry]
               (let [n (graph/node acc id)
                     v (:value entry)]
                 (if (and (graph/value-node? n) v)
                   (assoc-in acc [:nodes id :value] v)
                   acc)))
             g
             mem))

(defn- optimizable-scalar-slot
  [id data start]
  (cond
    (integer? data)
    {:node-id id
     :start start
     :end (inc start)
     :kind :scalar
     :int? true}

    (number? data)
    {:node-id id
     :start start
     :end (inc start)
     :kind :scalar
     :int? false}))

(defn- optimizable-array-slot
  [id data start]
  (when (and (dense/ndarray? data)
             (= :float64 (dense/dtype data))
             (< 1 (dense/size data) (inc max-opt-array-len)))
    {:node-id id
     :start start
     :end (+ start (dense/size data))
     :kind :array
     :int? false
     :template data}))

(defn- add-slot
  [{:keys [slots x0 int-mask] :as state} id v]
  (let [data (value/datum v)
        start (count x0)
        slot (or (optimizable-scalar-slot id data start)
                 (optimizable-array-slot id data start))]
    (if-not slot
      state
      (let [values (case (:kind slot)
                     :scalar [data]
                     :array (dense/ravel data))
            int-flags (repeat (count values) (:int? slot))]
        {:slots (conj slots slot)
         :x0 (into x0 (map double) values)
         :int-mask (into int-mask int-flags)}))))

(defn- extract-optimizables
  [g mem root-id]
  (let [leaves (graph/leaves g root-id)
        result (reduce (fn [state leaf-id]
                         (let [v (entry-value mem leaf-id)]
                           (if (and v (:permeable? v))
                             (add-slot state leaf-id v)
                             state)))
                       {:slots []
                        :x0 []
                        :int-mask []}
                       leaves)]
    (assoc result :leaves leaves)))

(defn- slot-values
  [slot x]
  (subvec (vec x) (:start slot) (:end slot)))

(defn- slot-data
  [slot x]
  (case (:kind slot)
    :scalar
    (let [v (first (slot-values slot x))]
      (if (:int? slot)
        (long (Math/rint (double v)))
        (double v)))

    :array
    (dense/array-like (:template slot) (slot-values slot x))))

(defn- apply-opt-values
  [mem slots x]
  (reduce (fn [acc slot]
            (assoc-entry-value acc (:node-id slot) (slot-data slot x)))
          mem
          slots))

(defn- leaf-dl
  [value-dl-cache mem id]
  (if (value-present? mem id)
    (value/desc-len-cached value-dl-cache (entry-value mem id))
    Double/POSITIVE_INFINITY))

(defn- leaves-dl
  [value-dl-cache mem leaves]
  (reduce + 0.0 (map #(leaf-dl value-dl-cache mem %) leaves)))

(defn- upward-value-traces
  [g start-id]
  (letfn [(walk-value [id trace]
            (if (some #{id} trace)
              []
              (let [trace (conj trace id)
                    parents (:parents (graph/node g id))]
                (if (empty? parents)
                  [trace]
                  (mapcat #(walk-operator % trace) parents)))))
          (walk-operator [id trace]
            (let [parent (:parent (graph/node g id))]
              (walk-value parent trace)))]
    (walk-value start-id [])))

(defn- selected-leaves
  [g start-id blocked]
  (let [seen (atom (set blocked))]
    (letfn [(walk [id]
              (if (contains? @seen id)
                []
                (do
                  (swap! seen conj id)
                  (let [n (graph/node g id)]
                    (cond
                      (graph/value-node? n)
                      (if-let [option-id (first (:options n))]
                        (walk option-id)
                        [id])

                      (graph/operator-node? n)
                      (mapcat walk (:children n))

                      :else [])))))]
      (vec (walk start-id)))))

(defn- product
  [xss]
  (if (empty? xss)
    [[]]
    (for [x (first xss)
          tail (product (rest xss))]
      (into [x] tail))))

(defn- cross-sections
  [g root-id target-leaves]
  (let [traces (mapcat #(upward-value-traces g %) target-leaves)
        traces-with-indices (mapv #(map-indexed vector %) traces)]
    (loop [remaining (product traces-with-indices)
           seen #{}
           result []]
      (if-let [trace-comb (first remaining)]
        (let [trace-comb (vec trace-comb)
              section (reduce-kv
                       (fn [{:keys [bn num-op-nodes]} index [trace-index value-id]]
                         (let [nodes-above (subvec (vec (nth traces index)) trace-index)]
                           (if (some (fn [[other-index _ other-id]]
                                       (and (not= index other-index)
                                            (some #{other-id} nodes-above)))
                                     (map-indexed (fn [idx [_ value-id]]
                                                    [idx nil value-id])
                                                  trace-comb))
                             {:bn bn
                              :num-op-nodes num-op-nodes}
                             {:bn (conj bn value-id)
                              :num-op-nodes (+ num-op-nodes
                                               (dec (count nodes-above)))})))
                       {:bn []
                        :num-op-nodes 0}
                       trace-comb)
              leaves (selected-leaves g root-id (:bn section))
              bn (into (:bn section) leaves)
              key (set bn)]
          (if (contains? seen key)
            (recur (rest remaining) seen result)
            (recur (rest remaining)
                   (conj seen key)
                   (conj result {:bottleneck bn
                                 :num-op-nodes (:num-op-nodes section)}))))
        result))))

(defn- value-content-equal?
  [left right]
  (hashing/content-equal? (value/datum left) (value/datum right)))

(defn- target-leaves
  [g mem root-id section-ids leaves]
  (let [target (entry-value mem root-id)
        section-leaves (set (rest section-ids))]
    (vec (filter (fn [id]
                   (and (contains? section-leaves id)
                        (some #{id} leaves)
                        (value-content-equal? (entry-value mem id) target)))
                 leaves))))

(defn- below-any?
  [g id bottleneck]
  (boolean
   (some (set bottleneck)
         (graph/walk g id {:above? true
                           :below? false
                           :values? true
                           :operators? true
                           :include-self? false}))))

(defn- section-extra-dl
  [g mem value-dl-cache section-ids bottleneck]
  (reduce (fn [total id]
            (let [v (entry-value mem id)]
              (if (and v
                       (not (:permeable? v))
                       (below-any? g id bottleneck))
                (+ total (leaf-dl value-dl-cache mem id))
                total)))
          0.0
          section-ids))

(defn- cross-section-score
  [g mem value-dl-cache section-ids cross-section]
  (let [bottleneck (:bottleneck cross-section)]
    {:dl (+ (leaves-dl value-dl-cache mem bottleneck)
            (:num-op-nodes cross-section)
            (section-extra-dl g mem value-dl-cache section-ids bottleneck))
     :bottleneck bottleneck}))

(defn- best-cross-section-score
  [g mem value-dl-cache section-ids cross-sections]
  (reduce (fn [best cross-section]
            (let [score (cross-section-score g
                                             mem
                                             value-dl-cache
                                             section-ids
                                             cross-section)]
              (if (< (:dl score) (:dl best))
                score
                best)))
          {:dl Double/POSITIVE_INFINITY
           :bottleneck []}
          cross-sections))

(defn- scoring-context
  [g mem root-id section-ids leaves]
  (let [target-leaves (target-leaves g mem root-id section-ids leaves)]
    (when (seq target-leaves)
      {:section-ids section-ids
       :cross-sections (cross-sections g root-id target-leaves)})))

(defn- propagation-results
  [g mem propagation-options]
  (propagation/propagate g mem (merge {:partial? false
                                       :unique? true}
                                      propagation-options)))

(defn- score-propagated-leaves
  [g mem leaves value-dl-cache propagation-options scoring-context]
  (reduce (fn [best prop-mem]
            (let [{:keys [dl bottleneck]}
                  (if scoring-context
                    (best-cross-section-score g
                                              prop-mem
                                              value-dl-cache
                                              (:section-ids scoring-context)
                                              (:cross-sections scoring-context))
                    {:dl (leaves-dl value-dl-cache prop-mem leaves)
                     :bottleneck leaves})]
              (if (< dl (:dl best))
                {:dl dl
                 :memory prop-mem
                 :bottleneck bottleneck}
                best)))
          {:dl Double/POSITIVE_INFINITY
           :memory nil
           :bottleneck leaves}
          (propagation-results g mem propagation-options)))

(defn- trial-memory
  [mem section-ids slots x]
  (-> (select-keys mem section-ids)
      (apply-opt-values slots x)))

(defn try-to-optimize
  "Optimize permeable scalar or short dense-array leaves inside an existing graph.

  `section-ids` names the cross-section values copied into each trial memory.
  Values outside that section may still participate in initial scoring, but are
  re-inferred by propagation during optimization.
  "
  [g mem {:keys [section-ids optimizer-fn propagation-options value-dl-cache]
          :or {propagation-options {}
               value-dl-cache (atom {})}
          :as opts}]
  (let [root-id (root-id g opts)
        section-ids (vec (or section-ids (keys mem)))
        {:keys [slots x0 int-mask leaves]} (extract-optimizables g mem root-id)
        initial-dl (leaves-dl value-dl-cache mem leaves)
        scoring-context (scoring-context g mem root-id section-ids leaves)]
    (if (empty? slots)
      {:dl initial-dl
       :initial-dl initial-dl
       :memory mem
       :bottleneck leaves
       :x x0
       :slots slots
       :improved? false}
      (let [optimizer ((or optimizer-fn
                           #(optimize/adaptive-grid-search {:int-mask %
                                                            :n-points 5}))
                       int-mask)
            objective (fn [x]
                        (let [trial-mem (trial-memory mem section-ids slots x)
                              result (score-propagated-leaves g
                                                              trial-mem
                                                              leaves
                                                              value-dl-cache
                                                              propagation-options
                                                              scoring-context)]
                          {:score (:dl result)
                           :params result}))
            result (optimize/optimize optimizer objective x0 initial-dl nil)
            params (:params result)]
        (merge {:dl (:score result)
                :initial-dl initial-dl
                :x (:x result)
                :slots slots
                :improved? (< (:score result) initial-dl)}
               params)))))
