(ns ciwi.enumerative-rewrite
  (:require [ciwi.operator :as op]
            [ciwi.rewrite :as rewrite]
            [ciwi.value :as value]))

(defn- distinct-stable
  [xs]
  (vec (distinct xs)))

(defn default-literals
  [data]
  (distinct-stable
   (cond-> [0 1]
     (number? data) (conj data)
     (vector? data) (conj (count data))
     (and (vector? data) (seq data) (number? (first data))) (conj (first data))
     (and (vector? data) (> (count data) 1) (number? (first data)) (number? (second data)))
     (conj (- (second data) (first data))))))

(defn- seed-literals
  [data literal-values]
  (distinct-stable
   (cond
     (nil? literal-values) (default-literals data)
     (fn? literal-values) (literal-values data)
     :else literal-values)))

(defn- literal-expr
  [x]
  {:kind :literal
   :value x
   :dl (value/desc-len (value/value x))
   :depth 0
   :form x})

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
      (throw (ex-info "Unknown enumerative rewrite operator" {:operator operator})))
    (when-not (integer? arity)
      (throw (ex-info "Enumerative rewrite operator requires explicit :arity"
                      {:operator (:id runtime-op)
                       :spec spec})))
    {:op runtime-op
     :arity arity}))

(defn- expression-form
  [expr]
  (if (= :op (:kind expr))
    (into [(:id (:op expr))] (map expression-form (:children expr)))
    (:form expr)))

(defn- safe-apply
  [operator children]
  (try
    (let [inputs (mapv #(value/value (:value %)) children)]
      {:ok? true
       :value (value/datum (op/apply-op operator inputs))})
    (catch Throwable _
      {:ok? false})))

(defn- op-expr
  [{operator :op} children]
  (let [{:keys [ok? value]} (safe-apply operator children)]
    (when ok?
      (let [dl (+ (:dl operator) (reduce + 0.0 (map :dl children)))
            expr {:kind :op
                  :op operator
                  :children (vec children)
                  :value value
                  :dl dl
                  :depth (inc (reduce max 0 (map :depth children)))}]
        (assoc expr :form (expression-form expr))))))

(defn- product
  [xs n]
  (if (zero? n)
    [[]]
    (for [x xs
          tail (product xs (dec n))]
      (into [x] tail))))

(defn- better-expr
  [left right]
  (cond
    (nil? left) right
    (nil? right) left
    :else (first (sort-by (juxt :dl #(pr-str (:form %))) [left right]))))

(defn- add-expr
  [by-value expr]
  (update by-value (:value expr) better-expr expr))

(defn enumerate-expressions
  "Enumerate expressions in increasing bounded layers.

  This is intentionally small and local: it enumerates expression trees over a
  configured operator set and literal generator, deduping by produced value while
  keeping the cheapest expression seen for that value.
  "
  [data {:keys [operators literal-values registry max-depth max-generated max-pool-size]
         :or {registry op/registry
              max-depth 2
              max-generated 1000
              max-pool-size 256}}]
  (let [operator-specs (mapv #(normalize-operator-spec registry %) operators)
        literals (mapv literal-expr (seed-literals data literal-values))]
    (loop [depth 1
           generated 0
           by-value (reduce add-expr {} literals)
           pool (sort-by (juxt :dl #(pr-str (:form %))) literals)]
      (if (or (> depth max-depth)
              (>= generated max-generated))
        (vals by-value)
        (let [remaining (- max-generated generated)
              new-exprs (->> (for [spec operator-specs
                                    children (product pool (:arity spec))
                                    :let [expr (op-expr spec children)]
                                    :when expr]
                                expr)
                              (take remaining)
                              vec)
              by-value (reduce add-expr by-value new-exprs)
              pool (->> (vals by-value)
                        (sort-by (juxt :dl #(pr-str (:form %))))
                        (take max-pool-size)
                        vec)]
          (recur (inc depth)
                 (+ generated (count new-exprs))
                 by-value
                 pool))))))

(defn- candidate-for-expression
  [g node-id reason expr]
  (when (= :op (:kind expr))
    (assoc (rewrite/candidate g
                              node-id
                              (:op expr)
                              (mapv :value (:children expr))
                              reason
                              (:dl expr))
           :expression (:form expr)
           :enum-depth (:depth expr))))

(defn enumerative-template
  "Create a bounded local enumerative rewrite template.

  Required option: `:operators`, a collection of maps like `{:op :brange
  :arity 2}`. Bounds are controlled by `:max-depth`, `:max-generated`, and
  `:max-pool-size`. `:literal-values` may be a collection or `(fn [data] ...)`.
  "
  [{:keys [id reason]
    :or {id :enumerative
         reason :enumerative}
    :as opts}]
  (rewrite/value-template
   id
   (fn [g node-id data]
     (->> (enumerate-expressions data opts)
          (filter #(= data (:value %)))
          (keep #(candidate-for-expression g node-id reason %))))))
