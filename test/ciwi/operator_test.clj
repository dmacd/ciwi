(ns ciwi.operator-test
  (:require [ciwi.operator :as sut]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(defn raw-inversions
  [operator output cond-inputs cond]
  (->> (sut/invert-op operator
                      (value/value output)
                      (mapv value/value cond-inputs)
                      cond)
       (mapv (fn [values]
               (mapv :data values)))))

(deftest numeric-operators-run-forward-and-backward
  (is (= 12 (:data (sut/apply-op sut/add [(value/value 5) (value/value 7)]))))
  (is (= [[7]] (raw-inversions sut/add 12 [5] [0])))
  (is (= [[5]] (raw-inversions sut/add 12 [7] [1])))
  (is (= [[7]] (raw-inversions sut/sub 5 [12] [0])))
  (is (= [[12]] (raw-inversions sut/sub 5 [7] [1])))
  (is (= [[4]] (raw-inversions sut/mult 12 [3] [0])))
  (is (= [[3]] (raw-inversions sut/negate -3 [] []))))

(deftest sequence-operators-run-forward-and-backward
  (is (= [3 4 5] (:data (sut/apply-op sut/brange [(value/value 3) (value/value 3)]))))
  (is (= [[3 3]] (raw-inversions sut/brange [3 4 5] [] [])))
  (is (= [:x :x :x] (:data (sut/apply-op sut/repeat [(value/value :x) (value/value 3)]))))
  (is (= [[:x 3]] (raw-inversions sut/repeat [:x :x :x] [] [])))
  (is (= [[[3 4]]] (raw-inversions sut/concat [1 2 3 4] [[1 2]] [0])))
  (is (= [[[1 2]]] (raw-inversions sut/concat [1 2 3 4] [[3 4]] [1]))))
