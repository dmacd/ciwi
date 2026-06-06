(ns ciwi.graph-rewrite
  (:require [ciwi.dense :as dense]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.rewrite :as rewrite]
            [ciwi.value :as value]))

(defn- distinct-stable
  [xs]
  (vec (distinct xs)))

(defn default-literals
  [data]
  (let [values (when (or (dense/ndarray? data) (vector? data))
                 (if (dense/ndarray? data) (dense/ravel data) data))]
    (distinct-stable
     (cond-> [0 1]
       (number? data) (conj data)
       values (conj (count values))
       (and values (seq values) (number? (first values))) (conj (first values))
       (and values (> (count values) 1) (number? (first values)) (number? (second values)))
       (conj (- (second values) (first values)))))))

(defn- seed-literals
  [data literal-values]
  (distinct-stable
   (cond
     (nil? literal-values) (default-literals data)
     (fn? literal-values) (literal-values data)
     :else literal-values)))

(defn- literal-operand
  [x]
  {:kind :literal
   :value x
   :dl (value/desc-len (value/value x))
   :depth 0
   :ref (rewrite/value-ref x)
   :form (value/plain-datum x)})

(defn- node-operand
  [g node-id]
  {:kind :node
   :node-id node-id
   :value (graph/value-data g node-id)
   :dl (:dl (mdl/node-dl g node-id))
   :depth 0
   :ref (rewrite/node-ref node-id)
   :form [:node node-id]})

(defn- resolve-operator
  [registry operator]
  (cond
    (op/operator? operator) operator
    (keyword? operator) (get registry operator)
    :else nil))

(defn- normalize-operator-spec
  [registry spec]
  (let [{operator :op arity :arity} (cond
                                      (map? spec) spec
                                      (vector? spec) {:op (first spec)
                                                      :arity (second spec)}
                                      :else {:op spec})
        runtime-op (resolve-operator registry operator)]
    (when-not runtime-op
      (throw (ex-info "Unknown graph rewrite operator" {:operator operator})))
    (when-not (integer? arity)
      (throw (ex-info "Graph rewrite operator requires explicit :arity"
                      {:operator (:id runtime-op)
                       :spec spec})))
    {:op runtime-op
     :arity arity}))

(defn- safe-apply
  [operator operands]
  (try
    (let [inputs (mapv #(value/value (:value %)) operands)]
      {:ok? true
       :value (value/datum (op/apply-op operator inputs))})
    (catch Throwable _
      {:ok? false})))

(defn- edit-form
  [operator operands]
  (into [(:id operator)] (map :form operands)))

(defn- edit-operand
  [{operator :op} operands]
  (let [{:keys [ok? value]} (safe-apply operator operands)]
    (when ok?
      (let [dl (+ (:dl operator) (reduce + 0.0 (map :dl operands)))]
        {:kind :edit
         :op operator
         :operands (vec operands)
         :child-refs (mapv :ref operands)
         :value value
         :dl dl
         :depth (inc (reduce max 0 (map :depth operands)))
         :ref (rewrite/edit-ref operator (mapv :ref operands) value dl)
         :form (edit-form operator operands)}))))

(defn- product
  [xs n]
  (if (zero? n)
    [[]]
    (for [x xs
          tail (product xs (dec n))]
      (into [x] tail))))

(defn- operand-rank
  [operand]
  [(:dl operand)
   (case (:kind operand)
     :node 0
     :literal 1
     :edit 2)
   (pr-str (:form operand))])

(defn- better-operand
  [left right]
  (cond
    (nil? left) right
    (nil? right) left
    :else (first (sort-by operand-rank [left right]))))

(defn- add-operand
  [by-value operand]
  (update by-value (:value operand) better-operand operand))

(defn- termination
  [depth max-depth generated max-generated]
  (cond
    (>= generated max-generated) :max-generated
    (> depth max-depth) :max-depth
    :else :exhausted))

(defn enumerate-edits
  "Enumerate local graph-edit operands under resource bounds."
  [data {:keys [operators literal-values registry max-depth max-generated beam-width seed-operands]
         :or {registry op/registry
              max-depth 1
              max-generated 1000
              beam-width 256}}]
  (let [operator-specs (mapv #(normalize-operator-spec registry %) operators)
        literals (mapv literal-operand (seed-literals data literal-values))
        seed-operands (vec seed-operands)
        seeds (vec (concat literals seed-operands))]
    (loop [depth 1
           generated 0
           depth-reached 0
           edits []
           by-value (reduce add-operand {} seeds)
           beam (sort-by operand-rank seeds)]
      (if (or (> depth max-depth)
              (>= generated max-generated))
        {:edits edits
         :operands (vals by-value)
         :resource {:generated-edits generated
                    :depth-reached depth-reached
                    :beam-width beam-width
                    :max-depth max-depth
                    :max-generated max-generated
                    :literal-operands (count literals)
                    :node-operands (count seed-operands)
                    :initial-operands (count seeds)
                    :retained-operands (count beam)
                    :termination (termination depth max-depth generated max-generated)}}
        (let [remaining (- max-generated generated)
              new-edits (->> (for [spec operator-specs
                                    operands (product beam (:arity spec))
                                    :let [edit (edit-operand spec operands)]
                                    :when edit]
                                edit)
                              (take remaining)
                              vec)
              by-value (reduce add-operand by-value new-edits)
              beam (->> (vals by-value)
                        (sort-by operand-rank)
                        (take beam-width)
                        vec)]
          (recur (inc depth)
                 (+ generated (count new-edits))
                 depth
                 (into edits new-edits)
                 by-value
                 beam))))))

(defn- candidate-for-edit
  [g node-id rewrite-operator-id resource edit]
  (when-let [candidate (rewrite/candidate-from-refs g
                                                     node-id
                                                     (:op edit)
                                                     (:child-refs edit)
                                                     (:id (:op edit))
                                                     (:dl edit))]
    (assoc candidate
           :rewrite-operator-id rewrite-operator-id
           :edit-form (:form edit)
           :edit-depth (:depth edit)
           :resource resource)))

(defn- sum-resource
  [results k]
  (reduce + 0 (map #(get-in % [:resource k] 0) results)))

(defn- node-result
  [g node-id search-opts {:keys [id use-local-nodes?] :as opts}]
  (let [data (graph/value-data g node-id)
        node-operands (when-not (= false use-local-nodes?)
                        (for [local-id (distinct (:local-node-ids search-opts))
                              :when (rewrite/reusable-child-node? g node-id local-id)]
                          (node-operand g local-id)))
        {:keys [edits resource]} (enumerate-edits data
                                                  (assoc opts
                                                         :seed-operands
                                                         node-operands))
        proposed (->> edits
                      (filter #(= data (:value %)))
                      (keep #(candidate-for-edit g node-id id resource %))
                      vec)
        accepted (filterv rewrite/neg-delta? proposed)]
    {:node-id node-id
     :candidates accepted
     :resource (assoc resource
                      :nodes-considered 1
                      :candidates-proposed (count proposed)
                      :candidates-accepted (count accepted)
                      :candidates-rejected (- (count proposed) (count accepted)))
     :trace [{:kind :graph-edit-enumeration
              :node-id node-id
              :rewrite-operator-id id
              :generated-edits (:generated-edits resource)
              :matched-edits (count proposed)
              :accepted-count (count accepted)
              :termination (:termination resource)
              :resource resource}
             {:kind :graph-edit-candidates
              :node-id node-id
              :rewrite-operator-id id
              :edits (mapv (fn [candidate]
                             {:operator-id (get-in candidate [:op :id])
                              :child-refs (:child-refs candidate)
                              :edit-form (:edit-form candidate)
                              :accepted? (rewrite/neg-delta? candidate)})
                           proposed)}]}))

(defrecord GraphRewriteOperator [id opts]
  rewrite/RewriteOperator
  (rewrite-operator-id [_]
    id)
  (run-rewrite [_ g node-ids search-opts]
    (let [items (rewrite/value-node-ids g node-ids)
          opts (assoc opts :id id)
          node-fn #(node-result g % search-opts opts)
          node-results (if (:parallel? search-opts)
                         (rewrite/parallel-mapv node-fn items)
                         (mapv node-fn items))
          candidates (->> node-results
                          (mapcat :candidates)
                          (sort-by (juxt :after :delta (comp str :node-id) (comp str :reason)))
                          vec)
          resource {:rewrite-operators-considered 1
                    :nodes-considered (sum-resource node-results :nodes-considered)
                    :candidates-proposed (sum-resource node-results :candidates-proposed)
                    :candidates-accepted (count candidates)
                    :candidates-rejected (sum-resource node-results :candidates-rejected)
                    :generated-edits (sum-resource node-results :generated-edits)}]
      {:rewrite-operator-id id
       :node-ids items
       :candidates candidates
       :resource resource
       :trace (into [{:kind :rewrite-operator
                      :rewrite-operator-id id
                      :resource resource}]
                    (mapcat :trace node-results))})))

(defn graph-rewrite-operator
  "Create a bounded direct local graph-edit rewrite operator."
  [{:keys [id]
    :or {id :graph-rewrite}
    :as opts}]
  (->GraphRewriteOperator id opts))
