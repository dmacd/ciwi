(ns ciwi.composite
  (:require [ciwi.conditions :as conditions]
            [ciwi.dsl :as dsl]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.spec :as spec]
            [ciwi.value :as value]))

(defn leaf-ids
  [g root]
  (graph/leaves g root))

(defn input-form?
  [x]
  (and (vector? x)
       (= :input (first x))
       (= 3 (count x))))

(defn- analyze-template
  [expr registry]
  (let [leaf-idx (atom 0)
        input-order (atom [])
        input-groups (atom {})]
    (letfn [(remember-input! [input-id]
              (let [idx @leaf-idx]
                (swap! leaf-idx inc)
                (when-not (contains? @input-groups input-id)
                  (swap! input-order conj input-id))
                (swap! input-groups update input-id (fnil conj []) idx)))
            (remember-literal! []
              (swap! leaf-idx inc))
            (walk [form]
              (cond
                (input-form? form)
                (let [[_ input-id sample] form]
                  (remember-input! input-id)
                  sample)

                (and (vector? form) (= :value (first form)))
                (do
                  (remember-literal!)
                  form)

                (dsl/operator-form? form registry)
                (into [(first form)] (map walk (rest form)))

                :else
                (do
                  (remember-literal!)
                  form)))]
      (let [sample-expr (walk expr)
            ordered-groups (mapv #(vec (get @input-groups %)) @input-order)]
        {:expr sample-expr
         :input-groups ordered-groups
         :input-ids (vec @input-order)}))))

(defn- graph-spec
  [expr opts]
  (cond
    (and (:graph opts) (:root opts))
    {:graph (:graph opts)
     :root (:root opts)
     :input-groups (:input-groups opts)
     :input-ids (:input-ids opts)}

    (and (map? expr) (:graph expr) (:root expr))
    expr

    :else
    (let [registry (:registry opts op/registry)
          analyzed (analyze-template expr registry)]
      (merge (dsl/from-expr (:expr analyzed) {:registry registry})
             (select-keys analyzed [:input-groups :input-ids])))))

(defn- validate-constant-indices!
  [constant-indices arity]
  (doseq [idx constant-indices]
    (when-not (and (integer? idx) (<= 0 idx) (< idx arity))
      (throw (ex-info "Composite constant index out of bounds"
                      {:index idx
                       :arity arity
                       :constant-indices constant-indices}))))
  constant-indices)

(defn- variable-indices
  [arity constant-indices]
  (vec (remove constant-indices (range arity))))

(defn- effective-constant-indices
  [arity declared-constants input-groups]
  (let [input-leaves (set (mapcat identity input-groups))
        all-leaves (set (range arity))]
    (when (seq (filter input-leaves declared-constants))
      (throw (ex-info "Composite input leaves cannot also be constants"
                      {:constant-indices declared-constants
                       :input-groups input-groups})))
    (if (seq input-groups)
      (into (set declared-constants) (remove input-leaves all-leaves))
      (set declared-constants))))

(defn- default-input-groups
  [arity constant-indices input-groups]
  (if (seq input-groups)
    (mapv vec input-groups)
    (mapv vector (variable-indices arity constant-indices))))

(defn- raw-condition->input-condition
  [condition input-groups constant-indices]
  (let [condition-set (set condition)]
    (when (every? condition-set constant-indices)
      (loop [idx 0
             remaining input-groups
             result []]
        (if-let [leaf-idxs (first remaining)]
          (let [states (mapv #(contains? condition-set %) leaf-idxs)]
            (cond
              (every? true? states)
              (recur (inc idx) (next remaining) (conj result idx))

              (some true? states)
              nil

              :else
              (recur (inc idx) (next remaining) result)))
          (conditions/normalize-condition result))))))

(defn- composite-conditions
  [g root input-groups constant-indices]
  (let [projected (keep #(raw-condition->input-condition % input-groups constant-indices)
                        (conditions/get-conditions g root))
        projected (if (seq projected)
                    projected
                    [(vec (range (count input-groups)))])]
    (conditions/filter-redundant projected (count input-groups))))

(defn- memory-with-inputs
  [base leaves input-groups inputs]
  (when-not (= (count input-groups) (count inputs))
    (throw (ex-info "Composite input arity mismatch"
                    {:expected (count input-groups)
                     :actual (count inputs)})))
  (reduce (fn [mem [leaf-idxs input]]
            (reduce (fn [acc leaf-idx]
                      (assoc acc (nth leaves leaf-idx) (propagation/entry input)))
                    mem
                    leaf-idxs))
          base
          (map vector input-groups inputs)))

(defn- first-root-value
  [g root mem]
  (some-> (first (propagation/propagate g mem))
          (propagation/value-at root)))

(defn- call-composite
  [g root leaves input-groups constant-indices inputs]
  (let [base (into {}
                   (for [[idx leaf-id] (map-indexed vector leaves)
                         :when (contains? constant-indices idx)]
                     [leaf-id (propagation/entry (graph/value-data g leaf-id))]))
        mem (memory-with-inputs base leaves input-groups inputs)
        output (first-root-value g root mem)]
    (if output
      (value/datum output)
      (throw (ex-info "Composite call could not propagate to root"
                      {:root root
                       :inputs inputs})))))

(defn- group-value
  [result leaf-ids]
  (let [values (mapv #(propagation/value-at result %) leaf-ids)]
    (when (every? some? values)
      (let [data (mapv value/datum values)]
        (when (apply = data)
          (first data))))))

(defn- inverse-composite
  [g root leaves input-groups constant-indices output cond-inputs cond]
  (let [condition-set (set cond)
        missing-input-idxs (vec (remove condition-set (range (count input-groups))))]
    (when (seq missing-input-idxs)
      (let [base (into {root (propagation/entry output)}
                       (for [[idx leaf-id] (map-indexed vector leaves)
                             :when (contains? constant-indices idx)]
                         [leaf-id (propagation/entry (graph/value-data g leaf-id))]))
            mem (reduce (fn [acc [condition-idx input]]
                          (reduce (fn [acc leaf-idx]
                                    (assoc acc (nth leaves leaf-idx)
                                           (propagation/entry input)))
                                  acc
                                  (nth input-groups condition-idx)))
                        base
                        (map vector cond cond-inputs))
            missing-leaf-groups (mapv (fn [input-idx]
                                        (mapv #(nth leaves %) (nth input-groups input-idx)))
                                      missing-input-idxs)]
        (->> (propagation/propagate g mem)
             (keep (fn [result]
                     (let [values (mapv #(group-value result %) missing-leaf-groups)]
                       (when (every? some? values)
                         values))))
             distinct)))))

(defn- leaf-input-index
  [input-groups]
  (into {}
        (for [[input-idx leaf-idxs] (map-indexed vector input-groups)
              leaf-idx leaf-idxs]
          [leaf-idx input-idx])))

(defn- symbolic-key
  [g root leaves input-groups input-index-transform]
  (let [leaf-position (zipmap leaves (range))
        leaf->input (leaf-input-index input-groups)]
    (letfn [(key* [id trace]
              (if (contains? trace id)
                [:cycle]
                (let [n (graph/node g id)
                      trace (conj trace id)]
                  (cond
                    (graph/value-node? n)
                    (if-let [leaf-idx (get leaf-position id)]
                      (if-let [input-idx (get leaf->input leaf-idx)]
                        [:input (input-index-transform input-idx)]
                        [:constant (graph/value-data g id)])
                      (if-let [op-id (first (:options n))]
                        (key* op-id trace)
                        [:constant (graph/value-data g id)]))

                    (graph/operator-node? n)
                    (let [operator (:operator n)
                          child-keys (mapv #(key* % trace) (:children n))
                          child-keys (if (:commutative? operator)
                                       (vec (sort-by pr-str child-keys))
                                       child-keys)]
                      [:op (:id operator) child-keys])

                    :else nil))))]
      (key* root #{}))))

(defn- composite-commutative?
  [g root leaves input-groups]
  (and (= 2 (count input-groups))
       (= (symbolic-key g root leaves input-groups identity)
          (symbolic-key g root leaves input-groups {0 1, 1 0}))))

(defn- condition-covers?
  [requested sufficient]
  (let [requested (set requested)]
    (every? requested sufficient)))

(defn- cartesian-product
  [colls]
  (reduce (fn [prefixes coll]
            (for [prefix prefixes
                  x coll]
              (conj prefix x)))
          [[]]
          colls))

(defn- intersect-spec
  [left right]
  (cond
    (nil? left) right
    (nil? right) left
    (= left right) left
    (spec/conforms? left right) right
    (spec/conforms? right left) left
    :else nil))

(defn- assign-spec
  [specs value-id new-spec]
  (let [merged (intersect-spec (get specs value-id) new-spec)]
    (when merged
      (assoc specs value-id merged))))

(defn- declarations-by-op
  [declarations]
  (group-by :op declarations))

(defn- operator-declaration-choices
  [g declarations-by-id op-id]
  (let [operator-id (:id (:operator (graph/node g op-id)))]
    (get declarations-by-id operator-id)))

(defn- apply-declaration
  [g specs op-id {:keys [input-specs output-spec]}]
  (let [{:keys [parent children]} (graph/node g op-id)]
    (when-let [specs (assign-spec specs parent output-spec)]
      (loop [specs specs
             children children
             input-specs input-specs]
        (if-let [child-id (first children)]
          (when-let [specs (assign-spec specs child-id (first input-specs))]
            (recur specs (next children) (next input-specs)))
          specs)))))

(defn- apply-constant-specs
  [g specs leaves input-groups]
  (let [input-leaves (set (mapcat identity input-groups))]
    (reduce (fn [specs [idx leaf-id]]
              (if (or (nil? specs)
                      (contains? input-leaves idx))
                specs
                (let [constant-spec (spec/value-spec (graph/value-data g leaf-id))]
                  (if (= :unknown constant-spec)
                    specs
                    (assign-spec specs leaf-id constant-spec)))))
            specs
            (map-indexed vector leaves))))

(defn- grouped-input-specs
  [specs leaves input-groups]
  (loop [groups input-groups
         result []]
    (if-let [leaf-idxs (first groups)]
      (let [group-spec (reduce (fn [acc leaf-idx]
                                 (intersect-spec acc
                                                 (get specs (nth leaves leaf-idx))))
                               nil
                               leaf-idxs)]
        (when group-spec
          (recur (next groups) (conj result group-spec))))
      result)))

(defn composite-specs
  "Enumerate concrete CIWI spec signatures for a native composite graph.

  `declarations` is the same style of operator declaration table used by the
  Wunderbaum path: each entry has `:op`, `:input-specs`, and `:output-spec`.
  This intentionally stays in CIWI's keyword spec model instead of porting
  Python's generic runtime type objects.
  "
  ([expr declarations]
   (composite-specs expr declarations {}))
  ([expr declarations {:keys [constant-indices fixed-output-spec]
                       :or {constant-indices #{}}}]
   (let [{:keys [graph root input-groups]} (graph-spec expr {})
         leaves (leaf-ids graph root)
         declared-constants (validate-constant-indices! (set constant-indices) (count leaves))
         input-groups (default-input-groups (count leaves) declared-constants input-groups)
         constant-indices (effective-constant-indices (count leaves)
                                                      declared-constants
                                                      input-groups)
         input-groups (default-input-groups (count leaves) constant-indices input-groups)
         op-ids (graph/operator-ids graph)
         declarations-by-id (declarations-by-op declarations)
         choices (mapv #(operator-declaration-choices graph declarations-by-id %) op-ids)]
     (if (some empty? choices)
       []
       (->> (cartesian-product choices)
            (keep (fn [declaration-combo]
                    (let [initial-specs (cond-> {}
                                          fixed-output-spec
                                          (assoc root fixed-output-spec))
                          specs (reduce (fn [specs [op-id declaration]]
                                          (when specs
                                            (apply-declaration graph specs op-id declaration)))
                                        initial-specs
                                        (map vector op-ids declaration-combo))
                          specs (apply-constant-specs graph specs leaves input-groups)]
                      (when specs
                        (when-let [input-specs (grouped-input-specs specs leaves input-groups)]
                          {:input-specs input-specs
                           :output-spec (get specs root)})))))
            distinct
            vec)))))

(defn operator
  "Create a graph-backed composite Operator from a Clojure graph literal.

  `constant-indices` names root leaves that are captured as constants from the
  graph literal. Remaining leaves become the composite operator inputs. A leaf
  written as `[:input id sample]` is a named input placeholder; repeated ids tie
  multiple graph leaves to one operator input, and non-input leaves are captured
  as constants.
  "
  ([id expr]
   (operator id expr {}))
  ([id expr {:keys [constant-indices dl]
             :or {constant-indices #{}
                  dl 1.0}
             :as opts}]
   (let [{:keys [graph root input-groups]} (graph-spec expr opts)
         leaves (leaf-ids graph root)
         declared-constants (validate-constant-indices! (set constant-indices) (count leaves))
         input-groups (default-input-groups (count leaves) declared-constants input-groups)
         constant-indices (effective-constant-indices (count leaves)
                                                      declared-constants
                                                      input-groups)
         conditions (composite-conditions graph root input-groups constant-indices)]
     (op/operator
      {:id id
       :conditions conditions
       :commutative? (composite-commutative? graph root leaves input-groups)
       :dl dl
       :call (fn [inputs]
               (call-composite graph root leaves input-groups constant-indices inputs))
       :inverse (fn [output cond-inputs cond]
                  (when (some #(condition-covers? cond %) conditions)
                    (inverse-composite graph root leaves input-groups constant-indices
                                       output cond-inputs cond)))}))))
