(ns ciwi.propagation
  (:require [ciwi.graph :as graph]
            [ciwi.operator :as operator]
            [ciwi.value :as value]))

(defrecord MapEntry [same? value])

(defn entry
  ([x]
   (entry false x))
  ([same? x]
   (->MapEntry same? (value/value x))))

(defn memory
  [values-by-id]
  (into {}
        (map (fn [[id x]]
               [id (entry x)]))
        values-by-id))

(defn value-at
  [mem id]
  (some-> mem (get id) :value))

(defn known-value?
  [mem id]
  (some? (value/datum (value-at mem id))))

(defn- remember
  [mem id x]
  (assoc mem id (entry false x)))

(defn- fire-up
  [g mem op-id]
  (let [{:keys [operator parent children]} (graph/node g op-id)]
    (when (and (not (contains? mem parent))
               (every? #(known-value? mem %) children))
      (let [inputs (mapv #(value-at mem %) children)
            output (operator/apply-op operator inputs)]
        [(-> mem
             (remember parent output)
             (assoc op-id (->MapEntry false operator)))]))))

(defn- inferable-children
  [children cond]
  (keep-indexed (fn [idx id]
                  (when-not (some #{idx} cond)
                    id))
                children))

(defn- fire-down-for-cond
  [g mem op-id cond]
  (let [{:keys [operator parent children]} (graph/node g op-id)
        output (value-at mem parent)]
    (when (some? (value/datum output))
      (let [cond-input-ids (mapv children cond)
            inf-ids (vec (inferable-children children cond))]
        (when (and (seq inf-ids)
                   (every? #(known-value? mem %) cond-input-ids)
                   (every? #(not (contains? mem %)) inf-ids)
                   (= (count (distinct inf-ids)) (count inf-ids)))
          (let [cond-inputs (mapv #(value-at mem %) cond-input-ids)]
            (for [inf-values (operator/invert-op operator output cond-inputs cond)
                  :when (= (count inf-ids) (count inf-values))]
              (-> (reduce (fn [acc [id inferred]]
                            (remember acc id inferred))
                          mem
                          (map vector inf-ids inf-values))
                  (assoc op-id (->MapEntry false operator))))))))))

(defn- fire-down
  [g mem op-id]
  (let [{:keys [operator]} (graph/node g op-id)]
    (mapcat #(fire-down-for-cond g mem op-id %) (:conditions operator))))

(defn try-to-fire
  [g mem op-id]
  (concat (fire-up g mem op-id)
          (fire-down g mem op-id)))

(defn- propagate*
  [g mem unsatisfied partial?]
  (if (empty? unsatisfied)
    [mem]
    (let [attempts (for [op-id unsatisfied
                         new-mem (try-to-fire g mem op-id)]
                     [op-id new-mem])]
      (if (seq attempts)
        (mapcat (fn [[op-id new-mem]]
                  (propagate* g
                              new-mem
                              (vec (remove #{op-id} unsatisfied))
                              partial?))
                attempts)
        (when partial?
          [mem])))))

(defn propagate
  ([g mem]
   (propagate g mem {}))
  ([g mem {:keys [partial? unique?]
           :or {partial? false
                unique? true}}]
   (let [results (propagate* g mem (graph/operator-ids g) partial?)]
     (if unique?
       (distinct results)
       results))))
