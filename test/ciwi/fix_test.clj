(ns ciwi.fix-test
  (:require [ciwi.composite :as composite]
            [ciwi.dsl :as dsl]
            [ciwi.fix :as sut]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(defn- data-results
  [results]
  (mapv #(mapv value/datum %) results))

(deftest fix-first-captures-a-primitive-operator-input
  (let [add5 (sut/fix-first (value/value 5) op/add)]
    (is (= 8
           (value/datum (op/apply-op add5 [(value/value 3)]))))
    (is (= [[]]
           (:conditions add5)))
    (is (= [[3]]
           (data-results (op/invert-op add5
                                       (value/value 8)
                                       []
                                       []))))))

(deftest fix-operator-can-be-built-through-a-clojure-graph-literal
  (let [{:keys [graph root]} (dsl/from-expr [sut/operator 6 op/mult])
        mult6 (graph/value-data graph root)]
    (is (op/operator? mult6))
    (is (= 42
           (value/datum (op/apply-op mult6 [(value/value 7)]))))
    (is (= [[7]]
           (data-results (op/invert-op mult6
                                       (value/value 42)
                                       []
                                       []))))))

(deftest fix-first-captures-a-composite-operator-input
  (let [mul-plus-two (composite/operator :mul-plus-two
                                         [:add [:mult [:input :x 0]
                                                [:input :y 0]]
                                          2])
        fixed (sut/fix-first 5 mul-plus-two)]
    (is (= 37
           (value/datum (op/apply-op fixed [(value/value 7)]))))))
