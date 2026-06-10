(ns ciwi.graph-optimize
  (:require [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
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

(defn- propagation-results
  [g mem propagation-options]
  (propagation/propagate g mem (merge {:partial? false
                                       :unique? true}
                                      propagation-options)))

(defn- score-propagated-leaves
  [g mem leaves value-dl-cache propagation-options]
  (reduce (fn [best prop-mem]
            (let [dl (leaves-dl value-dl-cache prop-mem leaves)]
              (if (< dl (:dl best))
                {:dl dl
                 :memory prop-mem
                 :bottleneck leaves}
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
        initial-dl (leaves-dl value-dl-cache mem leaves)]
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
                                                              propagation-options)]
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
