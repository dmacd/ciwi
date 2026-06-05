(ns ciwi.notebook.core-machinery
  (:require [ciwi.compress :as compress]
            [ciwi.dsl :as dsl]
            [ciwi.enumerative-rewrite :as enum]
            [ciwi.graph :as graph]
            [ciwi.graph-rewrite :as graph-rewrite]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.rewrite :as rewrite]
            [ciwi.search :as search]
            [ciwi.value :as value]))

;; # CIWI Core Machinery
;;
;; This notebook is a guided tour through the current Clojure prototype. It
;; starts with the small data records, then builds graphs, scores descriptions,
;; proposes rewrites, and finally compares exhaustive and bounded convergence.
;;
;; The values below are intentionally plain maps, vectors, records, and keywords.
;; The point is to see the actual shapes the algorithms trade in.

(defn node-row
  [[id n]]
  (case (:kind n)
    :value {:id id
            :kind :value
            :data (get-in n [:value :data])
            :parents (:parents n)
            :options (:options n)}
    :operator {:id id
               :kind :operator
               :op (get-in n [:operator :id])
               :parent (:parent n)
               :children (:children n)}))

(defn graph-rows
  [g]
  (->> (:nodes g)
       (sort-by (comp str key))
       (mapv node-row)))

(defn candidate-row
  [candidate]
  (select-keys candidate
               [:node-id :rewrite-operator-id :template-id :reason :op
                :child-refs :before :after :delta :expression :edit-form]))

(defn candidate-rows
  [candidates]
  (mapv candidate-row candidates))

(defn step-row
  [step]
  {:step (:step step)
   :reason (get-in step [:candidate :reason])
   :rewrite-operator-id (get-in step [:candidate :rewrite-operator-id])
   :node-id (get-in step [:candidate :node-id])
   :dl-before (:dl-before step)
   :dl-after (:dl-after step)
   :delta (get-in step [:candidate :delta])
   :mode (get-in step [:resource :mode])
   :candidates-accepted (get-in step [:resource :candidates-accepted])})

(defn step-rows
  [result]
  (mapv step-row (:steps result)))

(defn expression-row
  [expr]
  {:kind (:kind expr)
   :form (:form expr)
   :value (:value expr)
   :dl (:dl expr)
   :depth (:depth expr)})

(defn expression-rows
  [expressions]
  (->> expressions
       (sort-by (juxt :depth :dl (comp pr-str :form)))
       (mapv expression-row)))

(defn edit-row
  [edit]
  {:kind (:kind edit)
   :form (:form edit)
   :value (:value edit)
   :dl (:dl edit)
   :depth (:depth edit)
   :child-refs (:child-refs edit)})

(defn edit-rows
  [edits]
  (->> edits
       (sort-by (juxt :depth :dl (comp pr-str :form)))
       (mapv edit-row)))

;; ## Values
;;
;; Every payload is wrapped in a `ciwi.value/Value`. The rewrite engine compares
;; raw data against structured descriptions using `desc-len`.

(def sample-values
  [nil
   0
   42
   :z
   [0 1 2 3 4 5]
   [:z :z :z :z]
   {:left [0 1 2] :right 10}])

(mapv (fn [x]
        {:data x
         :value-record (value/value x)
         :description-length (value/desc-len (value/value x))})
      sample-values)

;; ## Operators
;;
;; Operators are records. They carry an id, a call function, optional inverse
;; logic for propagation, commutativity, and their own description-length cost.

(select-keys op/add [:id :conditions :commutative? :dl])

(op/apply-op op/add [(value/value [1 2 3])
                     (value/value 10)])

(op/apply-op op/brange [(value/value 3)
                        (value/value 5)])

;; ## Graphs
;;
;; A graph is a map of node ids to value nodes and operator nodes. Value nodes
;; point down to alternative operator descriptions through `:options`. Operator
;; nodes point up to their parent value and down to child values.

(def addition
  (dsl/from-expr [:add 3 4]))

(:root addition)

(graph-rows (:graph addition))

(dsl/to-expr (:graph addition) (:root addition))

;; Literal data that looks like an operator form can be wrapped explicitly.

(def literal-operator-looking-data
  (dsl/from-expr (dsl/literal [:add 3 4])))

(graph-rows (:graph literal-operator-looking-data))

;; ## MDL Selection
;;
;; A value node can have multiple descriptions. MDL picks the cheapest one,
;; recursively. Here the raw vector is more expensive than a `brange` option.

(def raw-range
  (-> (graph/empty-graph)
      (graph/add-value :out [0 1 2 3 4 5 6 7])))

(mdl/node-dl raw-range :out)

(def range-with-option
  (first (graph/add-derived-option raw-range :out op/brange [0 8])))

(graph-rows range-with-option)

(mdl/node-dl range-with-option :out)

(mdl/selected-operators range-with-option :out)

(mdl/selected-expression range-with-option :out)

;; ## Primitive Template Rewrites
;;
;; The default rewrite operator runs a small set of exact templates over value
;; nodes. A candidate is accepted only when its description length delta is
;; negative.

(mapv rewrite/template-id (rewrite/primitive-templates))

(def primitive-search
  (search/rewrite-search raw-range [:out] {:parallel? false}))

(:resource primitive-search)

(candidate-rows (:candidates primitive-search))

(def raw-range-after-one-rewrite
  (rewrite/apply-candidate raw-range (first (:candidates primitive-search))))

(graph-rows raw-range-after-one-rewrite)

(mdl/selected-expression raw-range-after-one-rewrite :out)

;; ## Exhaustive Convergence
;;
;; Exhaustive convergence searches all value nodes each step. It applies the
;; best candidate, repeats, and stops at a fixed point or `:max-steps`.

(def affine-graph
  (-> (graph/empty-graph)
      (graph/add-value :out [2 5 8 11 14 17])))

(def exhaustive-affine
  (search/exhaustive-converge affine-graph {:parallel? false}))

(:stopped exhaustive-affine)

(step-rows exhaustive-affine)

(mdl/selected-expression (:graph exhaustive-affine) :out)

(:resource exhaustive-affine)

;; ## Bounded Convergence
;;
;; Bounded convergence starts from target ids and rewrites only a local
;; neighborhood. The re-evaluation budget controls how many graph nodes are
;; considered around each target on each search.

(def bounded-affine
  (search/bounded-converge affine-graph
                           [:out]
                           {:parallel? false
                            :re-eval-budget 4}))

(step-rows bounded-affine)

(get-in bounded-affine [:steps 0 :neighborhoods])

(= (:dl exhaustive-affine) (:dl bounded-affine))

(mdl/selected-expression (:graph bounded-affine) :out)

;; ## Compression API
;;
;; `ciwi.compress` wraps convergence and returns selected expressions for the
;; roots or target set. This is the easiest public entry point for experiments.

(def compression-workload
  (-> (graph/empty-graph)
      (graph/add-value :range [0 1 2 3 4])
      (graph/add-value :repeat [:z :z :z :z])
      (graph/add-value :affine [10 12 14 16 18])))

(def exhaustive-compression
  (compress/compress-exhaustive compression-workload {:parallel? false}))

(def bounded-compression
  (compress/compress-bounded compression-workload
                             [:range :repeat :affine]
                             {:parallel? false
                              :re-eval-budget 64}))

(:selected exhaustive-compression)

(:selected bounded-compression)

(compress/same-compression? exhaustive-compression
                            bounded-compression
                            [:range :repeat :affine])

;; ## Bounded Enumerative Rewrite
;;
;; Primitive templates only find patterns that someone wrote down. The
;; enumerative operator builds expressions from operators and literal seeds, then
;; keeps the best expression per value inside resource bounds.

(defn square-literals
  [data]
  (if (vector? data)
    [0 (count data)]
    [0 1 data]))

(def square-data
  [0 1 4 9 16 25])

(def shallow-square-enumeration
  (enum/enumeration-result square-data
                           {:operators [{:op :brange :arity 2}
                                        {:op :mult :arity 2}]
                            :literal-values square-literals
                            :max-depth 1
                            :max-generated 200
                            :beam-width 64}))

(:resource shallow-square-enumeration)

(take 12 (expression-rows (:expressions shallow-square-enumeration)))

(def deep-square-enumeration
  (enum/enumeration-result square-data
                           {:operators [{:op :brange :arity 2}
                                        {:op :mult :arity 2}]
                            :literal-values square-literals
                            :max-depth 2
                            :max-generated 200
                            :beam-width 64}))

(:resource deep-square-enumeration)

(->> (:expressions deep-square-enumeration)
     expression-rows
     (filter #(= square-data (:value %))))

(def square-graph
  (-> (graph/empty-graph)
      (graph/add-value :out square-data)))

(def square-enumerator
  (enum/enumerative-operator
   {:id :square-enum
    :reason :square-enum
    :operators [{:op :brange :arity 2}
                {:op :mult :arity 2}]
    :literal-values square-literals
    :max-depth 2
    :max-generated 200
    :beam-width 64}))

(def square-enum-result
  (search/exhaustive-converge square-graph
                              {:parallel? false
                               :rewrite-operators [square-enumerator]}))

(step-rows square-enum-result)

(mdl/selected-expression (:graph square-enum-result) :out)

;; ## Graph Rewrite
;;
;; Graph rewrite is similar to enumerative rewrite, but it enumerates edit
;; operands and can reuse nearby value nodes by reference. This is how a local
;; DAG can share one discovered range twice instead of materializing it twice.

(def square-with-range
  (let [g (-> (graph/empty-graph)
              (graph/add-value :square square-data)
              (graph/add-value :range [0 1 2 3 4 5]))]
    (first (graph/add-derived-option g :range op/brange [0 6]))))

(graph-rows square-with-range)

(def graph-edit-enumeration
  (graph-rewrite/enumerate-edits square-data
                                 {:operators [{:op :mult :arity 2}]
                                  :literal-values [0 1]
                                  :seed-operands [{:kind :node
                                                   :node-id :range
                                                   :value [0 1 2 3 4 5]
                                                   :dl (:dl (mdl/node-dl square-with-range :range))
                                                   :depth 0
                                                   :ref (rewrite/node-ref :range)
                                                   :form [:node :range]}]
                                  :max-depth 1
                                  :max-generated 64
                                  :beam-width 64}))

(:resource graph-edit-enumeration)

(->> (:edits graph-edit-enumeration)
     edit-rows
     (filter #(= square-data (:value %))))

(def graph-edit-operator
  (graph-rewrite/graph-rewrite-operator
   {:id :graph-edit
    :operators [{:op :mult :arity 2}]
    :literal-values [0 1]
    :max-depth 1
    :max-generated 64
    :beam-width 64}))

(def graph-edit-result
  (search/bounded-converge square-with-range
                           [:square :range]
                           {:parallel? false
                            :re-eval-budget 1
                            :rewrite-operators [graph-edit-operator]}))

(candidate-rows (:history graph-edit-result))

(mdl/selected-expression (:graph graph-edit-result) :square)

(graph-rows (:graph graph-edit-result))

;; ## What To Change Next
;;
;; Good knobs to edit in this notebook:
;;
;; * Change `:max-depth` from 2 to 1 in `square-enumerator`.
;; * Lower `:max-generated` until the square rewrite disappears.
;; * Change `:re-eval-budget` in bounded convergence and inspect neighborhoods.
;; * Add another operator spec such as `{:op :add :arity 2}` to the enumerator.
;; * Replace the vectors with your own values and watch candidate deltas.
