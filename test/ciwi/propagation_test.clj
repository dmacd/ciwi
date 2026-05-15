(ns ciwi.propagation-test
  (:require [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as sut]
            [clojure.test :refer [deftest is testing]]))

(def william-co4-fire-down-golden
  "Transcribed from ../william/william/tests/test_propagation.py:
  graph co4, inputs [None, 12, 5], expected inferred child value 12 - 5."
  {:output 12
   :known-child 5
   :expected-missing-child 7})

(defn add-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :left nil)
      (graph/add-value :right nil)
      (graph/add-operator :add-out op/add :out [:left :right])))

(deftest propagates-add-up
  (let [g (add-graph)
        mem (sut/memory {:left 3 :right 4})
        result (first (sut/propagate g mem))]
    (is (= 7 (:data (sut/value-at result :out))))))

(deftest propagates-add-down-from-william-golden
  (testing (:doc (meta #'william-co4-fire-down-golden))
    (let [{:keys [output known-child expected-missing-child]} william-co4-fire-down-golden
          g (add-graph)
          mem (sut/memory {:out output :right known-child})
          result (first (sut/propagate g mem))]
      (is (= expected-missing-child
             (:data (sut/value-at result :left)))))))

(deftest propagates-unary-inversion
  (let [g (-> (graph/empty-graph)
              (graph/add-value :out nil)
              (graph/add-value :x nil)
              (graph/add-operator :negate-out op/negate :out [:x]))
        mem (sut/memory {:out -3})
        result (first (sut/propagate g mem))]
    (is (= 3 (:data (sut/value-at result :x))))))
