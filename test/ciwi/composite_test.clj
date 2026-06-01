(ns ciwi.composite-test
  (:require [ciwi.composite :as sut]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(defn- data-results
  [results]
  (mapv #(mapv value/datum %) results))

(deftest composite-operator-calls-through-graph-propagation
  (let [cop (sut/operator :mul-plus [:add [:mult 0 1] 2])]
    (is (= [[0 1] [0 2] [1 2]]
           (:conditions cop)))
    (is (= 37
           (value/datum (op/apply-op cop [(value/value 5)
                                          (value/value 7)
                                          (value/value 2)]))))
    (is (= [[2]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 5) (value/value 7)]
                                       [0 1]))))))

(deftest composite-operator-captures-constant-leaves
  (let [cop (sut/operator :mul-plus-two
                          [:add [:mult 0 1] 2]
                          {:constant-indices #{2}})]
    (is (= [[0] [1]]
           (:conditions cop)))
    (is (= 37
           (value/datum (op/apply-op cop [(value/value 5)
                                          (value/value 7)]))))
    (is (= [[7]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 5)]
                                       [0]))))
    (is (= [[5]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 7)]
                                       [1]))))))
