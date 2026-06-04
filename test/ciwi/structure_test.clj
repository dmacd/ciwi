(ns ciwi.structure-test
  (:require [ciwi.dsl :as dsl]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [clojure.test :refer [deftest is]]))

(defn built
  [expr]
  (dsl/from-expr expr))

(deftest clojure-graph-literals-round-trip-as-data
  (let [{:keys [graph root]} (built [:concat [:brange 0 3] [:repeat 2 [:x]]])
        data (dsl/to-data graph)
        graph' (dsl/from-data data)]
    (is (= data (dsl/to-data graph')))
    (is (= [:concat [:brange 0 3] [:repeat 2 [:x]]]
           (dsl/to-expr graph root)))
    (is (= (mdl/selected-operators graph root)
           (mdl/selected-operators graph' root)))))

(deftest resembles-respects-commutativity-and-value-policy
  (let [{g1 :graph r1 :root} (built [:add 3 4])
        {g2 :graph r2 :root} (built [:add 4 3])
        {g3 :graph r3 :root} (built [:sub 3 4])
        {g4 :graph r4 :root} (built [:sub 4 3])]
    (is (graph/resembles? g1 r1 g2 r2))
    (is (not (graph/resembles? g3 r3 g4 r4)))
    (is (graph/resembles? g3 r3 g4 r4 {:check-values? false}))))

(deftest depth-leaves-and-subgraph-match-structure-expectations
  (let [{:keys [graph root]} (built [:concat [:brange 0 3] [:repeat 2 [:x]]])
        {sub-g :graph sub-root :root} (built [:brange 0 3])]
    (is (= 2 (graph/depth graph root)))
    (is (= [0 3 2 [:x]] (graph/leaves-data graph root)))
    (is (graph/subgraph? sub-g sub-root graph root))))
