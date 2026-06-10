(ns ciwi.alice.wunderbaum.render
  (:require [ciwi.alice.wunderbaum.context :as context]
            [ciwi.cache :as cache]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.value :as value]))

(defn raw-tree
  [value-dl-cache v]
  (let [v (value/value v)]
    {:kind :raw
     :data (:data v)
     :dl (value/desc-len-cached value-dl-cache v)}))

(defn choice-tree
  [g value-dl-cache choice]
  (case (:kind choice)
    :raw
    (let [v (get-in g [:nodes (:node-id choice) :value])]
      {:kind :raw
       :data (:data v)
       :dl (or (:dl choice)
               (value/desc-len-cached value-dl-cache v))})

    :operator
    (let [op-node (graph/node g (:op-id choice))
          v (get-in g [:nodes (:node-id choice) :value])
          operator (:operator op-node)
          children (mapv #(choice-tree g value-dl-cache %)
                         (:child-choices choice))
          op-dl (:dl operator)]
      {:kind :operator
       :op-id (:id operator)
       :op-dl op-dl
       :children children
       :data (:data v)
       :dl (+ op-dl (reduce + 0.0 (map :dl children)))})))

(defn candidate-tree
  [{:keys [cache-context]} candidate target-id]
  (let [value-dl-cache (cache/value-dl-cache cache-context)
        description (mdl/node-dl (:graph candidate)
                                 target-id
                                 (cache/scoring-context cache-context))]
    (choice-tree (:graph candidate) value-dl-cache (:choice description))))

(defn- refresh-operator-tree
  [tree children]
  (assoc tree
         :children children
         :dl (+ (:op-dl tree) (reduce + 0.0 (map :dl children)))))

(defn tree-expr
  [tree]
  (case (:kind tree)
    :raw
    (value/plain-datum (:data tree))

    :operator
    (into [(:op-id tree)] (map tree-expr (:children tree)))))

(defn replace-tree
  [tree path replacement]
  (if (empty? path)
    replacement
    (let [idx (first path)
          path (subvec (vec path) 1)
          children (assoc (:children tree)
                          idx
                          (replace-tree (nth (:children tree) idx)
                                        path
                                        replacement))]
      (refresh-operator-tree tree children))))

(defn target-tree-leaves
  [target-index target-id tree]
  (letfn [(walk [node path]
            (if (= :raw (:kind node))
              [{:target-index target-index
                :target-id target-id
                :path path
                :data (:data node)
                :dl (:dl node)}]
              (mapcat (fn [[idx child]]
                        (walk child (conj path idx)))
                      (map-indexed vector (:children node)))))]
    (vec (walk tree []))))

(defn target-tree-dl
  [target-trees]
  (reduce + 0.0 (map :dl target-trees)))

(defn selected-targets
  [target-trees]
  (zipmap (context/target-ids (count target-trees))
          (map tree-expr target-trees)))
