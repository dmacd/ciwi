(ns ciwi.graph
  (:require [ciwi.operator :as operator]
            [ciwi.value :as value]))

(defrecord Graph [nodes])

(defn empty-graph
  []
  (->Graph {}))

(defn value-node?
  [node]
  (= :value (:kind node)))

(defn operator-node?
  [node]
  (= :operator (:kind node)))

(defn node
  [g id]
  (get-in g [:nodes id]))

(defn value-node
  [id output]
  {:id id
   :kind :value
   :value (value/value output)
   :parents []
   :options []})

(defn operator-node
  [id op parent children]
  {:id id
   :kind :operator
   :operator op
   :parent parent
   :children (vec children)})

(defn add-value
  [g id output]
  (assoc-in g [:nodes id] (value-node id output)))

(defn- require-value-node
  [g id role]
  (let [n (node g id)]
    (when-not (value-node? n)
      (throw (ex-info "Expected value node"
                      {:id id
                       :role role
                       :node n}))))
  g)

(defn- conj-parent
  [parents parent-id]
  (if (some #{parent-id} parents)
    parents
    (conj parents parent-id)))

(defn add-operator
  [g id op parent children]
  (when-not (operator/operator? op)
    (throw (ex-info "Expected ciwi.operator/Operator" {:id id :operator op})))
  (reduce (fn [acc child]
            (-> acc
                (require-value-node child :child)
                (update-in [:nodes child :parents] conj-parent id)))
          (-> g
              (require-value-node parent :parent)
              (assoc-in [:nodes id] (operator-node id op parent children))
              (update-in [:nodes parent :options] conj id))
          children))

(defn unique-id
  [g base]
  (let [base (keyword (name base))]
    (loop [candidate base
           n 1]
      (if (contains? (:nodes g) candidate)
        (recur (keyword (str (name base) "-" n)) (inc n))
        candidate))))

(defn add-derived-option
  "Add an operator option under an existing value node.

  `child-values` are plain data or `Value` records. Returns `[new-graph op-id]`.
  "
  [g parent-id op child-values]
  (let [op-id (unique-id g (keyword (str (name parent-id) "-" (name (:id op)))))
        [g child-ids]
        (reduce (fn [[acc ids] [idx child-value]]
                  (let [child-id (unique-id acc (keyword (str (name op-id) "-arg" idx)))]
                    [(add-value acc child-id child-value) (conj ids child-id)]))
                [g []]
                (map-indexed vector child-values))]
    [(add-operator g op-id op parent-id child-ids) op-id]))

(defn ids-by-kind
  [g kind]
  (->> (:nodes g)
       (keep (fn [[id n]]
               (when (= kind (:kind n))
                 id)))
       vec))

(defn value-ids
  [g]
  (ids-by-kind g :value))

(defn operator-ids
  [g]
  (ids-by-kind g :operator))

(defn neighbors
  [g id {:keys [above? below?]
         :or {above? true
              below? true}}]
  (let [n (node g id)]
    (cond
      (value-node? n)
      (cond-> []
        below? (into (:options n))
        above? (into (:parents n)))

      (operator-node? n)
      (cond-> []
        above? (conj (:parent n))
        below? (into (:children n)))

      :else [])))

(defn walk
  ([g start-id]
   (walk g start-id {}))
  ([g start-id {:keys [above? below? values? operators? include-self?]
                :or {above? true
                     below? true
                     values? true
                     operators? true
                     include-self? true}}]
   (loop [stack [start-id]
          seen #{}
          result []]
     (if-let [id (peek stack)]
       (if (contains? seen id)
         (recur (pop stack) seen result)
         (let [n (node g id)
               include? (and (or include-self?
                                  (not= id start-id))
                              (or (and values? (value-node? n))
                                  (and operators? (operator-node? n))))
               next-ids (remove seen (neighbors g id {:above? above?
                                                      :below? below?}))]
           (recur (into (pop stack) (reverse next-ids))
                  (conj seen id)
                  (cond-> result
                    include? (conj id)))))
       result))))

(defn roots
  [g]
  (->> (value-ids g)
       (filter (fn [id]
                 (empty? (:parents (node g id)))))
       vec))

(defn leaves
  ([g start-id]
   (leaves g start-id {}))
  ([g start-id opts]
   (->> (walk g start-id (merge {:above? false
                                 :below? true
                                 :values? true
                                 :operators? true}
                                opts))
        (filter (fn [id]
                  (let [n (node g id)]
                    (and (value-node? n)
                         (empty? (:options n))))))
        vec)))

(defn neighborhood
  "Return a breadth-first local neighborhood capped by `budget` node ids."
  [g start-id budget]
  (loop [queue [start-id]
         seen #{}
         result []]
    (cond
      (or (empty? queue)
          (>= (count result) budget))
      result

      (contains? seen (peek queue))
      (recur (pop queue) seen result)

      :else
      (let [id (peek queue)
            next-ids (remove seen (neighbors g id {}))]
        (recur (into (pop queue) (reverse next-ids))
               (conj seen id)
               (conj result id))))))


(defn breadth-first-walk
  ([g start-id]
   (breadth-first-walk g start-id {}))
  ([g start-id {:keys [above? below? values? operators?]
                :or {above? true
                     below? true
                     values? true
                     operators? true}}]
   (loop [queue [start-id]
          seen #{}
          result []]
     (if (empty? queue)
       result
       (let [id (first queue)
             queue (subvec (vec queue) 1)]
         (if (contains? seen id)
           (recur queue seen result)
           (let [n (node g id)
                 include? (or (and values? (value-node? n))
                              (and operators? (operator-node? n)))
                 next-ids (remove seen (neighbors g id {:above? above?
                                                        :below? below?}))]
             (recur (into queue next-ids)
                    (conj seen id)
                    (cond-> result include? (conj id))))))))))

(defn depth
  ([g id]
   (depth g id #{}))
  ([g id trace]
   (if (contains? trace id)
     0
     (let [n (node g id)
           trace (conj trace id)]
       (cond
         (value-node? n)
         (if (empty? (:options n))
           0
           (apply max (map #(depth g % trace) (:options n))))

         (operator-node? n)
         (if (empty? (:children n))
           1
           (inc (apply max (map #(depth g % trace) (:children n)))))

         :else 0)))))

(defn graph-depth
  [g]
  (if-let [rs (seq (roots g))]
    (apply max (map #(depth g %) rs))
    0))

(defn value-data
  [g id]
  (get-in g [:nodes id :value :data]))

(defn leaves-data
  [g start-id]
  (mapv #(value-data g %) (leaves g start-id)))

(defn structural-key
  ([g id]
   (structural-key g id {}))
  ([g id {:keys [check-values?]
          :or {check-values? true}}]
   (letfn [(key* [id trace]
             (if (contains? trace id)
               [:cycle]
               (let [n (node g id)
                     trace (conj trace id)]
                 (cond
                   (value-node? n)
                   (let [options (mapv #(key* % trace) (:options n))]
                     (cond-> [:value]
                       check-values? (conj (:data (:value n)))
                       true (conj options)))

                   (operator-node? n)
                   (let [op (:operator n)
                         child-keys (mapv #(key* % trace) (:children n))
                         child-keys (if (:commutative? op)
                                      (vec (sort-by pr-str child-keys))
                                      child-keys)]
                     [:operator (:id op) child-keys])

                   :else [:missing id]))))]
     (key* id #{}))))

(defn resembles?
  ([g1 id1 g2 id2]
   (resembles? g1 id1 g2 id2 {}))
  ([g1 id1 g2 id2 opts]
   (= (structural-key g1 id1 opts)
      (structural-key g2 id2 opts))))

(defn subgraph?
  ([sub-g sub-root g root]
   (subgraph? sub-g sub-root g root {}))
  ([sub-g sub-root g root opts]
   (let [needle (structural-key sub-g sub-root opts)]
     (boolean
      (some #(= needle (structural-key g % opts))
            (walk g root opts))))))
