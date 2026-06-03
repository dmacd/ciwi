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


(defn affine-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :scaled nil)
      (graph/add-value :range nil)
      (graph/add-value :range-start nil)
      (graph/add-value :n nil)
      (graph/add-value :step nil)
      (graph/add-value :start nil)
      (graph/add-operator :make-range op/brange :range [:range-start :n])
      (graph/add-operator :scale op/mult :scaled [:range :step])
      (graph/add-operator :shift op/add :out [:scaled :start])))

(deftest nil-memory-values-are-treated-as-unknowns
  (let [g (add-graph)
        missing-left (first (sut/propagate g
                                           (sut/memory {:left nil
                                                        :right 5})
                                           {:partial? true}))
        nil-output (first (sut/propagate g
                                         (sut/memory {:out nil
                                                      :right 5})
                                         {:partial? true}))]
    (is (nil? (sut/value-at missing-left :out)))
    (is (nil? (sut/value-at nil-output :left)))
    (is (nil? (:data (sut/value-at nil-output :out))))))

(deftest propagates-nested-affine-graph-upward
  (let [g (affine-graph)
        mem (sut/memory {:range-start 0
                         :n 4
                         :step 3
                         :start 2})
        result (first (sut/propagate g mem))]
    (is (= [0 1 2 3]
           (:data (sut/value-at result :range))))
    (is (= [0 3 6 9]
           (:data (sut/value-at result :scaled))))
    (is (= [2 5 8 11]
           (:data (sut/value-at result :out))))))

(deftest propagates-nested-affine-graph-downward
  (let [g (affine-graph)
        mem (sut/memory {:out [2 5 8 11]
                         :start 2
                         :step 3})
        result (first (sut/propagate g mem))]
    (is (= [0 3 6 9]
           (:data (sut/value-at result :scaled))))
    (is (= [0 1 2 3]
           (:data (sut/value-at result :range))))
    (is (= 0
           (:data (sut/value-at result :range-start))))
    (is (= 4
           (:data (sut/value-at result :n))))))
