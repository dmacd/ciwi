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

(defn add-operator
  [g id op parent children]
  (when-not (operator/operator? op)
    (throw (ex-info "Expected ciwi.operator/Operator" {:id id :operator op})))
  (reduce (fn [acc [child idx]]
            (-> acc
                (require-value-node child :child)
                (update-in [:nodes child :parents] conj id)))
          (-> g
              (require-value-node parent :parent)
              (assoc-in [:nodes id] (operator-node id op parent children))
              (update-in [:nodes parent :options] conj id))
          (map vector children (range))))

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
