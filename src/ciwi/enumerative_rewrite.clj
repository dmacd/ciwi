(ns ciwi.enumerative-rewrite
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
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

(defn- node-expr
  [g node-id]
  {:kind :node
   :node-id node-id
   :value (graph/value-data g node-id)
   :dl (:dl (mdl/node-dl g node-id))
   :depth 0
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

(defn- expr-rank
  [expr]
  [(:dl expr)
   (case (:kind expr)
     :node 0
     :literal 1
     :op 2)
   (pr-str (:form expr))])

(defn- better-expr
  [left right]
  (cond
    (nil? left) right
    (nil? right) left
    :else (first (sort-by expr-rank [left right]))))

(defn- add-expr
  [by-value expr]
  (update by-value (:value expr) better-expr expr))

(defn enumeration-result
  "Enumerate expressions in increasing bounded layers and return resource stats."
  [data {:keys [operators literal-values registry max-depth max-generated beam-width seed-expressions]
         :or {registry op/registry
              max-depth 2
              max-generated 1000
              beam-width 256}}]
  (let [operator-specs (mapv #(normalize-operator-spec registry %) operators)
        literals (mapv literal-expr (seed-literals data literal-values))
        seed-expressions (vec seed-expressions)
        seeds (vec (concat literals seed-expressions))]
    (loop [depth 1
           generated 0
           depth-reached 0
           by-value (reduce add-expr {} seeds)
           beam (sort-by expr-rank seeds)]
      (if (or (> depth max-depth)
              (>= generated max-generated))
        {:expressions (vals by-value)
         :resource {:generated-expressions generated
                    :depth-reached depth-reached
                    :beam-width beam-width
                    :max-depth max-depth
                    :max-generated max-generated
                    :literal-expressions (count literals)
                    :seed-expressions (count seed-expressions)
                    :initial-expressions (count seeds)
                    :retained-expressions (count beam)}}
        (let [remaining (- max-generated generated)
              new-exprs (->> (for [spec operator-specs
                                    children (product beam (:arity spec))
                                    :let [expr (op-expr spec children)]
                                    :when expr]
                                expr)
                              (take remaining)
                              vec)
              by-value (reduce add-expr by-value new-exprs)
              beam (->> (vals by-value)
                        (sort-by expr-rank)
                        (take beam-width)
                        vec)]
          (recur (inc depth)
                 (+ generated (count new-exprs))
                 depth
                 by-value
                 beam))))))

(defn enumerate-expressions
  "Enumerate expressions in increasing bounded layers."
  [data opts]
  (:expressions (enumeration-result data opts)))

(defn- child-ref
  [expr]
  (if (= :node (:kind expr))
    (rewrite/node-ref (:node-id expr))
    (rewrite/value-ref (:value expr))))

(defn- candidate-for-expression
  [g node-id reason resource expr]
  (when (= :op (:kind expr))
    (assoc (rewrite/candidate-from-refs g
                                        node-id
                                        (:op expr)
                                        (mapv child-ref (:children expr))
                                        reason
                                        (:dl expr))
           :expression (:form expr)
           :enum-depth (:depth expr)
           :resource resource)))

(defn enumerative-template
  "Create a bounded local enumerative rewrite template.

  Required option: `:operators`, a collection of maps like `{:op :brange
  :arity 2}`. Bounds are controlled by `:max-depth`, `:max-generated`, and
  `:beam-width`. `:literal-values` may be a collection or `(fn [data] ...)`.
  "
  [{:keys [id reason]
    :or {id :enumerative
         reason :enumerative}
    :as opts}]
  (rewrite/value-template
   id
   (fn [g node-id data search-opts]
     (let [node-exprs (when-not (= false (:use-local-nodes? opts))
                        (for [local-id (distinct (:local-node-ids search-opts))
                              :when (rewrite/reusable-child-node? g node-id local-id)]
                          (node-expr g local-id)))
           {:keys [expressions resource]} (enumeration-result data
                                                              (assoc opts
                                                                     :seed-expressions
                                                                     node-exprs))]
       (->> expressions
            (filter #(= data (:value %)))
            (keep #(candidate-for-expression g node-id reason resource %)))))))
