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

(defn- choice-dl
  [g choice seen-value-ids]
  (let [value-id (:node-id choice)]
    (if (contains? seen-value-ids value-id)
      [seen-value-ids 0.0]
      (let [seen-value-ids (conj seen-value-ids value-id)]
        (case (:kind choice)
          :raw
          [seen-value-ids
           (value/desc-len (get-in g [:nodes value-id :value]))]

          :operator
          (let [op-node (graph/node g (:op-id choice))
                op-dl (:dl (:operator op-node))
                [seen-value-ids child-dl]
                (reduce (fn [[seen total] child-choice]
                          (let [[seen child-dl] (choice-dl g child-choice seen)]
                            [seen (+ total child-dl)]))
                        [seen-value-ids 0.0]
                        (:child-choices choice))]
            [seen-value-ids (+ op-dl child-dl)]))))))

(defn graph-dl
  "Return the selected graph DL across all roots, charging shared selected value
  nodes once."
  [g]
  (second
   (reduce (fn [[seen total] root-id]
             (let [[seen root-dl] (choice-dl g (:choice (node-dl g root-id)) seen)]
               [seen (+ total root-dl)]))
           [#{} 0.0]
           (graph/roots g))))

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
