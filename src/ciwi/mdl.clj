(ns ciwi.mdl
  (:require [ciwi.graph :as graph]
            [ciwi.value :as value]))

(defn node-dl
  "Return the best description for a value node as
  `{:dl d :choice choice}`.
  "
  [g id]
  (let [memo (atom {})]
    (letfn [(best-value [value-id trace]
              (if-let [cached (get @memo value-id)]
                cached
                (let [n (graph/node g value-id)]
                  (when-not (graph/value-node? n)
                    (throw (ex-info "MDL expects a value node" {:id value-id :node n})))
                  (let [raw {:dl (value/desc-len (:value n))
                             :choice {:kind :raw
                                      :node-id value-id}}
                        option-choices
                        (for [op-id (:options n)
                              :when (not (contains? trace op-id))]
                          (let [op-node (graph/node g op-id)
                                child-results (mapv #(best-value % (conj trace op-id))
                                                    (:children op-node))
                                op-dl (:dl (:operator op-node))
                                dl (+ op-dl (reduce + 0.0 (map :dl child-results)))]
                            {:dl dl
                             :choice {:kind :operator
                                      :node-id value-id
                                      :op-id op-id
                                      :children (:children op-node)
                                      :child-choices (mapv :choice child-results)}}))
                        best (first (sort-by (juxt :dl #(str (:choice %)))
                                             (cons raw option-choices)))]
                    (swap! memo assoc value-id best)
                    best))))]
      (best-value id #{}))))

(defn graph-dl
  [g]
  (reduce + 0.0 (map #(:dl (node-dl g %)) (graph/roots g))))

(defn selected-operators
  [g id]
  (letfn [(collect [choice]
            (if (= :operator (:kind choice))
              (cons (:op-id choice)
                    (mapcat collect (:child-choices choice)))
              ()))]
    (vec (collect (:choice (node-dl g id))))))


(defn selected-expression
  [g id]
  (letfn [(expr [choice]
            (if (= :operator (:kind choice))
              (let [op-node (graph/node g (:op-id choice))]
                (into [(:id (:operator op-node))]
                      (map expr (:child-choices choice))))
              (get-in g [:nodes (:node-id choice) :value :data])))]
    (expr (:choice (node-dl g id)))))
