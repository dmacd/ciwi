(ns ciwi.dsl
  (:require [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.value :as value]))

(defn- operator-for
  [head registry]
  (cond
    (op/operator? head) head
    (keyword? head) (get registry head)
    :else nil))

(defn operator-form?
  ([expr]
   (operator-form? expr op/registry))
  ([expr registry]
   (and (or (vector? expr) (seq? expr))
        (seq expr)
        (boolean (operator-for (first expr) registry)))))

(defn literal
  "Force `x` to be interpreted as literal data, even when it looks like an
  operator form."
  [x]
  [:value x])

(defn from-expr
  "Build a graph from Clojure data.

  Operator forms are vectors/lists whose first element is an operator keyword or
  Operator record. Everything else is a literal value. Returns
  `{:graph g :root id}`.
  "
  ([expr]
   (from-expr expr {}))
  ([expr {:keys [registry]
          :or {registry op/registry}}]
   (let [counter (atom 0)
         next-id (fn [prefix]
                   (keyword (str (name prefix) (swap! counter inc))))]
     (letfn [(build [g form]
               (cond
                 (and (vector? form) (= :value (first form)))
                 (let [id (next-id :v)]
                   [(graph/add-value g id (second form)) id])

                 (operator-form? form registry)
                 (let [operator (operator-for (first form) registry)
                       args (vec (rest form))
                       [g child-ids]
                       (reduce (fn [[acc ids] arg]
                                 (let [[acc child-id] (build acc arg)]
                                   [acc (conj ids child-id)]))
                               [g []]
                               args)
                       child-values (mapv #(get-in g [:nodes % :value]) child-ids)
                       output (op/apply-op operator child-values)
                       parent-id (next-id :v)
                       op-id (next-id :op)]
                   [(-> g
                        (graph/add-value parent-id output)
                        (graph/add-operator op-id operator parent-id child-ids))
                    parent-id])

                 :else
                 (let [id (next-id :v)]
                   [(graph/add-value g id (value/value form)) id])))]
       (let [[g root-id] (build (graph/empty-graph) expr)]
         {:graph (graph/set-roots g [root-id])
          :root root-id})))))

(defn graph
  [expr]
  (:graph (from-expr expr)))

(defn root
  [expr]
  (:root (from-expr expr)))


(defn to-expr
  "Convert a graph rooted at `root-id` back to the Clojure graph literal form.
  The first operator option is used when a value node has alternatives."
  [g root-id]
  (letfn [(literal-if-needed [x]
            (if (operator-form? x)
              (literal x)
              x))
          (expr* [id trace]
            (if (contains? trace id)
              [:ref id]
              (let [n (graph/node g id)
                    trace (conj trace id)]
                (cond
                  (graph/value-node? n)
                  (if-let [op-id (first (:options n))]
                    (expr* op-id trace)
                    (literal-if-needed (:data (:value n))))

                  (graph/operator-node? n)
                  (into [(:id (:operator n))]
                        (map #(expr* % trace) (:children n)))

                  :else nil))))]
    (expr* root-id #{})))

(defn to-data
  "Serialize graph structure to EDN-friendly data. Operators are stored by id."
  [g]
  {:roots (graph/roots g)
   :nodes
   (into {}
         (map (fn [[id n]]
                [id (case (:kind n)
                      :value {:kind :value
                              :value (:data (:value n))
                              :parents (:parents n)
                              :options (:options n)}
                      :operator {:kind :operator
                                 :operator (:id (:operator n))
                                 :parent (:parent n)
                                 :children (:children n)})])
              (:nodes g)))})

(defn from-data
  ([data]
   (from-data data {}))
  ([data {:keys [registry]
          :or {registry op/registry}}]
   (let [value-nodes (filter (fn [[_ n]] (= :value (:kind n))) (:nodes data))
         op-nodes (filter (fn [[_ n]] (= :operator (:kind n))) (:nodes data))]
     (graph/set-roots
      (reduce (fn [g [id n]]
                (graph/add-operator g id (get registry (:operator n)) (:parent n) (:children n)))
              (reduce (fn [g [id n]]
                        (graph/add-value g id (:value n)))
                      (graph/empty-graph)
                      value-nodes)
              op-nodes)
      (:roots data)))))
