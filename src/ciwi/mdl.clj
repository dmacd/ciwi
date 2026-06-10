(ns ciwi.mdl
  (:require [ciwi.cache :as cache]
            [ciwi.graph :as graph]
            [ciwi.value :as value]))

(defn scoring-context
  "Create a per-scoring context for MDL dynamic programming.

  `:value-dl-cache` may be shared across related candidate graphs. The
  `:node-dl-cache` is graph-local and should not be reused across different
  graph versions.
  "
  ([]
   (scoring-context {}))
  ([opts]
   (cache/scoring-context opts)))

(defn- value-dl
  [context v]
  (value/desc-len-cached (cache/value-dl-cache context) v))

(defn node-dl
  "Return the best description for a value node as
  `{:dl d :choice choice}`.
  "
  ([g id]
   (node-dl g id (scoring-context)))
  ([g id context]
   (let [context (scoring-context context)
         node-dl-cache (cache/node-dl-cache context)]
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

(defn- raw-choice
  [context g value-id]
  (let [raw-dl (value-dl context (get-in g [:nodes value-id :value]))]
    {:kind :raw
     :node-id value-id
     :dl raw-dl}))

(defn- operator-choice
  [g value-id op-id]
  (let [op-node (graph/node g op-id)]
    {:kind :operator
     :node-id value-id
     :op-id op-id
     :children (:children op-node)}))

(defn- option-combinations
  [g value-ids]
  (letfn [(step [ids]
            (if-let [id (first ids)]
              (let [n (graph/node g id)]
                (for [op-id (cons nil (:options n))
                      tail (step (rest ids))]
                  (cons op-id tail)))
              '(())))]
    (step value-ids)))

(declare section-description)

(defn- merge-trace
  [child-traces child-id parent-trace]
  (if (contains? child-traces child-id)
    (update child-traces child-id into parent-trace)
    child-traces))

(defn- append-child
  [g context parent-trace children seen child-traces child-id]
  (cond
    (contains? parent-trace child-id)
    {:children children
     :seen seen
     :child-traces child-traces
     :cycle-dl (value-dl context (get-in g [:nodes child-id :value]))
     :cycle-choice (raw-choice context g child-id)}

    (contains? seen child-id)
    {:children children
     :seen seen
     :child-traces (merge-trace child-traces child-id parent-trace)
     :cycle-dl 0.0}

    :else
    {:children (conj children child-id)
     :seen (conj seen child-id)
     :child-traces (assoc child-traces child-id (set parent-trace))
     :cycle-dl 0.0}))

(defn- score-operator
  [g context value-id op-id section-traces state]
  (let [op-node (graph/node g op-id)
        children (:children op-node)
        stop? (empty? children)]
    (if stop?
      (let [choice (raw-choice context g value-id)]
        (-> state
            (update :dl + (:dl choice))
            (assoc-in [:choices value-id] choice)))
      (let [op-dl (:dl (:operator op-node))
            choice (operator-choice g value-id op-id)
            parent-trace (get section-traces value-id #{value-id})]
        (reduce (fn [state child-id]
                  (let [result (append-child g
                                             context
                                             parent-trace
                                             (:children state)
                                             (:seen state)
                                             (:child-traces state)
                                             child-id)]
                    (cond-> (assoc state
                                    :children (:children result)
                                    :seen (:seen result)
                                    :child-traces (:child-traces result))
                      (pos? (:cycle-dl result))
                      (update :dl + (:cycle-dl result))

                      (:cycle-choice result)
                      (assoc-in [:choices child-id] (:cycle-choice result)))))
                (-> state
                    (update :dl + op-dl)
                    (assoc-in [:choices value-id] choice)
                    (update :selected conj op-id))
                children)))))

(defn- score-combination
  [g context value-ids op-ids seen traces]
  (let [section-seen (into seen value-ids)
        section-traces (reduce (fn [acc value-id]
                                 (update acc value-id
                                         (fn [trace]
                                           (conj (set trace) value-id))))
                               traces
                               value-ids)
        base-state {:dl 0.0
                    :choices {}
                    :selected []
                    :children []
                    :seen section-seen
                    :child-traces {}}
        state (reduce (fn [state [value-id op-id]]
                        (if op-id
                          (score-operator g context value-id op-id section-traces state)
                          (let [choice (raw-choice context g value-id)]
                            (-> state
                                (update :dl + (:dl choice))
                                (assoc-in [:choices value-id] choice)))))
                      base-state
                      (map vector value-ids op-ids))]
    (if (empty? (:children state))
      (select-keys state [:dl :choices :selected])
      (let [child-result (section-description g
                                              context
                                              (:children state)
                                              (:seen state)
                                              (:child-traces state))]
        {:dl (+ (:dl state) (:dl child-result))
         :choices (merge (:choices state) (:choices child-result))
         :selected (into (:selected state) (:selected child-result))}))))

(defn- section-description
  [g context value-ids seen traces]
  (if (empty? value-ids)
    {:dl 0.0
     :choices {}
     :selected []}
    (reduce (fn [best op-ids]
              (let [candidate (score-combination g context value-ids op-ids seen traces)]
                (if (< (:dl candidate) (:dl best))
                  candidate
                  best)))
            {:dl Double/POSITIVE_INFINITY
             :choices {}
             :selected []}
            (option-combinations g value-ids))))

(defn graph-description
  "Return the best graph-level description across explicit roots.

  Unlike `node-dl`, this minimizes over whole cross-sections so sibling roots
  can choose options that share downstream value descriptions, matching Python
  WILLIAM's `ValueMDL` behavior.
  "
  ([g]
   (graph-description g {}))
  ([g context]
   (let [context (scoring-context context)]
     (section-description g context (graph/roots g) #{} {}))))

(defn graph-dl
  "Return the selected graph DL across explicit roots."
  ([g]
   (graph-dl g {}))
  ([g context]
   (:dl (graph-description g context))))

(defn- root-choice-map
  [g context]
  (when (seq (graph/roots g))
    (:choices (graph-description g context))))

(defn- selected-root?
  [g id]
  (some #{id} (graph/roots g)))

(defn- choice-for
  ([g id context]
   (choice-for g id context (root-choice-map g context)))
  ([g id context choices]
   (if (selected-root? g id)
     (or (get choices id)
         (:choice (node-dl g id context)))
     (:choice (node-dl g id context)))))

(defn selected-operators
  ([g id]
   (selected-operators g id {}))
  ([g id context]
   (let [context (scoring-context context)
         choices (when (selected-root? g id)
                   (root-choice-map g context))]
     (letfn [(collect [choice trace]
             (if (= :operator (:kind choice))
               (cons (:op-id choice)
                     (mapcat (fn [child-id]
                               (if (contains? trace child-id)
                                 ()
                                 (collect (or (get choices child-id)
                                              (:choice (node-dl g child-id context)))
                                          (conj trace child-id))))
                             (:children choice)))
               ()))]
       (vec (collect (choice-for g id context choices) #{id}))))))

(defn selected-expression
  ([g id]
   (selected-expression g id {}))
  ([g id context]
   (let [context (scoring-context context)]
     (let [choices (when (selected-root? g id)
                     (root-choice-map g context))]
       (letfn [(expr [choice trace]
               (if (= :operator (:kind choice))
                 (let [op-node (graph/node g (:op-id choice))]
                   (into [(:id (:operator op-node))]
                         (map (fn [child-id]
                                (if (contains? trace child-id)
                                  [:ref child-id]
                                  (expr (or (get choices child-id)
                                            (:choice (node-dl g child-id context)))
                                        (conj trace child-id))))
                              (:children choice))))
                 (value/plain-datum (get-in g [:nodes (:node-id choice) :value]))))]
         (expr (choice-for g id context choices) #{id}))))))
