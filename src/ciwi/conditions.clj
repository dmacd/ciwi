(ns ciwi.conditions
  (:require [ciwi.graph :as graph]
            [ciwi.operator :as op]))

(defn normalize-condition
  "Canonicalize a condition as a vector of distinct leaf indices.

  Python WILLIAM treats conditions with set semantics for redundancy, but keeps
  tuple order in a few public helpers. Preserve first occurrence order here and
  sort collections only when a specific helper does so.
  "
  [condition]
  (->> condition
       distinct
       vec))

(defn- subset-condition?
  [small big]
  (let [big-set (set big)]
    (every? big-set small)))

(defn- compare-condition
  [left right]
  (let [cmp (compare (vec left) (vec right))]
    (if (zero? cmp)
      (compare (count left) (count right))
      cmp)))

(defn- sort-conditions
  [conditions]
  (vec (sort compare-condition conditions)))

(defn condition-combinations
  "Cartesian product of child conditions, offsetting each child into root leaves."
  [conditions-by-child offset-list]
  (let [shifted (mapv (fn [conditions offset]
                        (mapv (fn [condition]
                                (mapv #(+ offset %) condition))
                              conditions))
                      conditions-by-child
                      offset-list)]
    (->> (reduce (fn [prefixes conditions]
                   (for [prefix prefixes
                         condition conditions]
                     (into prefix condition)))
                 [[]]
                 shifted)
         (mapv normalize-condition))))

(defn filter-redundant
  "Remove conditions made redundant by shorter subsets.

  This mirrors Python WILLIAM's filter_redundant semantics, including the
  special case where an empty condition does not subsume a non-full condition.
  "
  [conditions arity]
  (loop [remaining (vec (sort-by count (map normalize-condition conditions)))
         filtered []]
    (if-let [condition (peek remaining)]
      (let [shorter (pop remaining)
            redundant? (some (fn [candidate]
                               (and (subset-condition? candidate condition)
                                    (not (and (empty? candidate)
                                              (< (count condition) arity)))))
                             shorter)]
        (recur shorter
               (cond-> filtered
                 (not redundant?) (conj condition))))
      (sort-conditions filtered))))

(defn remove-redundant-conditions
  "Remove smaller conditions that are contained in larger conditions.

  `contained-in` optionally limits which larger conditions are allowed to remove
  smaller ones. This is the behavior used by Python WILLIAM when constants are
  part of composite operator conditions.
  "
  ([conditions]
   (remove-redundant-conditions conditions nil))
  ([conditions contained-in]
   (let [contained-in-set (some-> contained-in set)]
     (loop [remaining (vec (sort-by count (map normalize-condition conditions)))
            idx (dec (count remaining))]
       (if (neg? idx)
         remaining
         (let [condition (nth remaining idx)
               supers (subvec remaining (inc idx))
               redundant? (some (fn [super-condition]
                                  (and (subset-condition? condition super-condition)
                                       (or (nil? contained-in-set)
                                           (subset-condition? super-condition contained-in-set))))
                                supers)
               remaining (if redundant?
                           (vec (concat (subvec remaining 0 idx)
                                        (subvec remaining (inc idx))))
                           remaining)]
           (recur remaining (dec idx))))))))

(defn- first-option
  [g value-id]
  (first (:options (graph/node g value-id))))

(defn- callable-operator
  [x]
  (cond
    (op/operator? x) x
    (keyword? x) (get op/registry x)
    :else nil))

(defn- callable-condition-operator?
  [operator]
  (contains? #{:map :bmap} (:id operator)))

(defn- adopted-callable-conditions
  [g op-node]
  (let [operator (:operator op-node)]
    (if (callable-condition-operator? operator)
      (if-let [callable (some->> (:children op-node)
                                 first
                                 (graph/value-data g)
                                 callable-operator)]
        (mapv (fn [condition]
                (vec (cons 0 (map inc condition))))
              (:conditions callable))
        [])
      (:conditions operator))))

(defn- tree-leaves
  "Selected-tree leaves, preserving repeated child positions.

  `graph/leaves` intentionally returns unique reachable leaves. Python
  WILLIAM's condition code uses a separate repeated-leaf walk when projecting
  raw tree conditions for DAGs, so the condition path needs this local helper.
  "
  ([g value-id]
   (tree-leaves g value-id #{}))
  ([g value-id trace]
   (if (contains? trace value-id)
     [value-id]
     (if-let [op-id (first-option g value-id)]
       (mapcat #(tree-leaves g % (conj trace value-id))
               (:children (graph/node g op-id)))
       [value-id]))))

(defn- filter-repeating-leaves
  [raw-conditions repeated-leaves arity]
  (let [conditions
        (keep
         (fn [condition]
           (let [condition-set (set condition)]
             (loop [idx 0
                    leaves repeated-leaves
                    seen {}
                    projected []
                    projected-idx 0]
               (if-let [leaf-id (first leaves)]
                 (let [conditioned? (contains? condition-set idx)]
                   (if-let [previous (find seen leaf-id)]
                     (when (= (val previous) conditioned?)
                       (recur (inc idx)
                              (next leaves)
                              seen
                              projected
                              projected-idx))
                     (recur (inc idx)
                            (next leaves)
                            (assoc seen leaf-id conditioned?)
                            (cond-> projected
                              conditioned? (conj projected-idx))
                            (inc projected-idx))))
                 projected))))
         raw-conditions)]
    (if (seq conditions)
      (vec conditions)
      [(vec (range arity))])))

(defn- offsets-for
  [counts]
  (vec (butlast (reductions + 0 counts))))

(defn tree-conditions
  "Return root-leaf conditions for an operator tree rooted at `op-id`.

  The first option under each value node is treated as the selected tree, which
  matches the rest of CIWI's Clojure graph literal and structural helpers.
  "
  ([g op-id]
   (tree-conditions g op-id #{}))
  ([g op-id trace]
   (if (contains? trace op-id)
     []
     (let [{:keys [children] :as op-node} (graph/node g op-id)
           trace (conj trace op-id)
           repeated-counts (mapv #(count (tree-leaves g %)) children)
           offsets (offsets-for (mapv #(count (graph/leaves g %)) children))
           operator-conditions (adopted-callable-conditions g op-node)]
       (if-not (seq operator-conditions)
         []
         (->> operator-conditions
              (mapcat
               (fn [condition]
                 (let [condition-set (set condition)
                       child-conditions
                       (loop [idx 0
                              remaining children
                              counts repeated-counts
                              inferred #{}
                              result []]
                         (if-let [child-id (first remaining)]
                           (let [child-leaf-count (first counts)]
                             (cond
                               (contains? condition-set idx)
                               (recur (inc idx)
                                      (next remaining)
                                      (next counts)
                                      inferred
                                      (conj result [(vec (range child-leaf-count))]))

                               (contains? inferred child-id)
                               nil

                               (first-option g child-id)
                               (recur (inc idx)
                                      (next remaining)
                                      (next counts)
                                      (conj inferred child-id)
                                      (conj result
                                            (tree-conditions g
                                                             (first-option g child-id)
                                                             trace)))

                               :else
                               (recur (inc idx)
                                      (next remaining)
                                      (next counts)
                                      (conj inferred child-id)
                                      (conj result [[]]))))
                           result))]
                   (when child-conditions
                     (condition-combinations child-conditions offsets)))))
              (mapv normalize-condition)))))))

(defn get-conditions
  "Return filtered root-leaf conditions for a value node."
  [g value-id]
  (if-let [op-id (first-option g value-id)]
    (let [arity (count (graph/leaves g value-id))]
      (filter-redundant
       (filter-repeating-leaves (tree-conditions g op-id)
                                (vec (tree-leaves g value-id))
                                arity)
       arity))
    [[]]))
