(ns ciwi.mdl
  (:require [ciwi.graph :as graph]
            [ciwi.value :as value]))

(defn scoring-context
  "Create a per-scoring context for MDL dynamic programming.

  `:value-dl-cache` may be shared across related candidate graphs. The
  `:node-dl-cache` is graph-local and should not be reused across different
  graph versions.
  "
  ([]
   (scoring-context {}))
  ([{:keys [value-dl-cache node-dl-cache]}]
   {:value-dl-cache (or value-dl-cache (atom {}))
    :node-dl-cache (or node-dl-cache (atom {}))}))

(defn- value-dl
  [{:keys [value-dl-cache]} v]
  (value/desc-len-cached value-dl-cache v))

(defn node-dl
  "Return the best description for a value node as
  `{:dl d :choice choice}`.
  "
  ([g id]
   (node-dl g id (scoring-context)))
  ([g id context]
   (let [{:keys [node-dl-cache] :as context} (scoring-context context)]
     (letfn [(best-value [value-id trace]
               (if-let [cached (get @node-dl-cache value-id)]
                 cached
                 (let [n (graph/node g value-id)]
                   (when-not (graph/value-node? n)
                     (throw (ex-info "MDL expects a value node" {:id value-id :node n})))
                   (let [raw-dl (value-dl context (:value n))
                         raw {:dl raw-dl
                              :choice {:kind :raw
                                       :node-id value-id
                                       :dl raw-dl}}
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
                     (swap! node-dl-cache assoc value-id best)
                     best))))]
       (best-value id #{})))))

(defn- choice-dl
  [g context choice seen-value-ids]
  (let [value-id (:node-id choice)]
    (if (contains? seen-value-ids value-id)
      [seen-value-ids 0.0]
      (let [seen-value-ids (conj seen-value-ids value-id)]
        (case (:kind choice)
          :raw
          [seen-value-ids
           (or (:dl choice)
               (value-dl context (get-in g [:nodes value-id :value])))]

          :operator
          (let [op-node (graph/node g (:op-id choice))
                op-dl (:dl (:operator op-node))
                [seen-value-ids child-dl]
                (reduce (fn [[seen total] child-choice]
                          (let [[seen child-dl] (choice-dl g context child-choice seen)]
                            [seen (+ total child-dl)]))
                        [seen-value-ids 0.0]
                        (:child-choices choice))]
            [seen-value-ids (+ op-dl child-dl)]))))))

(defn graph-dl
  "Return the selected graph DL across all roots, charging shared selected value
  nodes once."
  ([g]
   (graph-dl g {}))
  ([g context]
   (let [context (scoring-context context)]
     (second
      (reduce (fn [[seen total] root-id]
                (let [[seen root-dl] (choice-dl g
                                                context
                                                (:choice (node-dl g root-id context))
                                                seen)]
                  [seen (+ total root-dl)]))
              [#{} 0.0]
              (graph/roots g))))))

(defn selected-operators
  ([g id]
   (selected-operators g id {}))
  ([g id context]
   (letfn [(collect [choice]
             (if (= :operator (:kind choice))
               (cons (:op-id choice)
                     (mapcat collect (:child-choices choice)))
               ()))]
     (vec (collect (:choice (node-dl g id (scoring-context context))))))))

(defn selected-expression
  ([g id]
   (selected-expression g id {}))
  ([g id context]
   (let [context (scoring-context context)]
     (letfn [(expr [choice]
               (if (= :operator (:kind choice))
                 (let [op-node (graph/node g (:op-id choice))]
                   (into [(:id (:operator op-node))]
                         (map expr (:child-choices choice))))
                 (get-in g [:nodes (:node-id choice) :value :data])))]
       (expr (:choice (node-dl g id context)))))))
