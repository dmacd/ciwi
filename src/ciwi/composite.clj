(ns ciwi.composite
  (:require [ciwi.conditions :as conditions]
            [ciwi.dsl :as dsl]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.value :as value]))

(defn leaf-ids
  [g root]
  (graph/leaves g root))

(defn- graph-spec
  [expr opts]
  (cond
    (and (:graph opts) (:root opts))
    {:graph (:graph opts) :root (:root opts)}

    (and (map? expr) (:graph expr) (:root expr))
    expr

    :else
    (dsl/from-expr expr)))

(defn- validate-constant-indices!
  [constant-indices arity]
  (doseq [idx constant-indices]
    (when-not (and (integer? idx) (<= 0 idx) (< idx arity))
      (throw (ex-info "Composite constant index out of bounds"
                      {:index idx
                       :arity arity
                       :constant-indices constant-indices}))))
  constant-indices)

(defn- variable-indices
  [arity constant-indices]
  (vec (remove constant-indices (range arity))))

(defn- composite-conditions
  [g root leaves constant-indices]
  (let [raw (conditions/get-conditions g root)
        arity (count leaves)
        variable-idxs (variable-indices arity constant-indices)]
    (if (empty? constant-indices)
      raw
      (let [constant-set (set constant-indices)
            old->new (zipmap variable-idxs (range))]
        (->> raw
             (keep (fn [condition]
                     (let [condition-set (set condition)]
                       (when (every? condition-set constant-set)
                         (->> condition
                              (remove constant-set)
                              (keep old->new)
                              conditions/normalize-condition)))))
             conditions/remove-redundant-conditions)))))

(defn- memory-with-inputs
  [base leaves variable-idxs inputs]
  (when-not (= (count variable-idxs) (count inputs))
    (throw (ex-info "Composite input arity mismatch"
                    {:expected (count variable-idxs)
                     :actual (count inputs)})))
  (reduce (fn [mem [leaf-idx input]]
            (assoc mem (nth leaves leaf-idx) (propagation/entry input)))
          base
          (map vector variable-idxs inputs)))

(defn- first-root-value
  [g root mem]
  (some-> (first (propagation/propagate g mem))
          (propagation/value-at root)))

(defn- call-composite
  [g root leaves variable-idxs constant-indices inputs]
  (let [base (into {}
                   (for [[idx leaf-id] (map-indexed vector leaves)
                         :when (contains? constant-indices idx)]
                     [leaf-id (propagation/entry (graph/value-data g leaf-id))]))
        mem (memory-with-inputs base leaves variable-idxs inputs)
        output (first-root-value g root mem)]
    (if output
      (value/datum output)
      (throw (ex-info "Composite call could not propagate to root"
                      {:root root
                       :inputs inputs})))))

(defn- inverse-composite
  [g root leaves variable-idxs constant-indices output cond-inputs cond]
  (let [condition-set (set cond)
        missing-variable-idxs (vec (remove condition-set (range (count variable-idxs))))]
    (when (seq missing-variable-idxs)
      (let [base (into {root (propagation/entry output)}
                       (for [[idx leaf-id] (map-indexed vector leaves)
                             :when (contains? constant-indices idx)]
                         [leaf-id (propagation/entry (graph/value-data g leaf-id))]))
            mem (reduce (fn [acc [condition-idx input]]
                          (let [leaf-idx (nth variable-idxs condition-idx)
                                leaf-id (nth leaves leaf-idx)]
                            (assoc acc leaf-id (propagation/entry input))))
                        base
                        (map vector cond cond-inputs))
            missing-leaf-ids (mapv (fn [condition-idx]
                                     (nth leaves (nth variable-idxs condition-idx)))
                                   missing-variable-idxs)]
        (->> (propagation/propagate g mem)
             (keep (fn [result]
                     (when (every? #(propagation/value-at result %) missing-leaf-ids)
                       (mapv #(value/datum (propagation/value-at result %))
                             missing-leaf-ids))))
             distinct)))))

(defn operator
  "Create a graph-backed composite Operator from a Clojure graph literal.

  `constant-indices` names root leaves that are captured as constants from the
  graph literal. Remaining leaves become the composite operator inputs.
  "
  ([id expr]
   (operator id expr {}))
  ([id expr {:keys [constant-indices dl]
             :or {constant-indices #{}
                  dl 1.0}
             :as opts}]
   (let [{:keys [graph root]} (graph-spec expr opts)
         leaves (leaf-ids graph root)
         constant-indices (validate-constant-indices! (set constant-indices) (count leaves))
         variable-idxs (variable-indices (count leaves) constant-indices)
         conditions (composite-conditions graph root leaves constant-indices)]
     (op/operator
      {:id id
       :conditions conditions
       :commutative? false
       :dl dl
       :call (fn [inputs]
               (call-composite graph root leaves variable-idxs constant-indices inputs))
       :inverse (fn [output cond-inputs cond]
                  (inverse-composite graph root leaves variable-idxs constant-indices
                                     output cond-inputs cond))}))))
