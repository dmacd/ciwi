(ns ciwi.enumerator-test
  (:require [ciwi.dsl :as dsl]
            [ciwi.enumerator :as sut]
            [ciwi.graph :as graph]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(defn- close?
  [expected actual]
  (< (Math/abs (- expected actual)) 1.0e-9))

(deftest input-enumerator-matches-python-core-order
  (let [free {:int [(value/value 1000 {:dummy? true})
                    (value/value 1001 {:dummy? true})]
              :float [(value/value 3.14 {:dummy? true})]}
        expected [[1000 3.14]
                  [1001 3.14]
                  [1000 0.0]
                  [0 3.14]
                  [1001 0.0]
                  [0 0.0]
                  [1 3.14]
                  [2 3.14]
                  [1 0.0]
                  [4 3.14]
                  [2 0.0]
                  [8 3.14]
                  [4 0.0]
                  [16 3.14]
                  [8 0.0]
                  [16 0.0]]
        actual (->> (sut/input-tuples [:int :float]
                                      {:free-values free
                                       :max-results 16})
                    (mapv (fn [item]
                            (mapv :data (:values item)))))]
    (is (= expected actual))))


(deftest node-tuple-enumerator-uses-breadth-first-value-order
  (let [{:keys [graph root]} (dsl/from-expr [:concat [:brange 0 3] [:repeat 2 [:x]]])
        tuples (sut/node-tuples graph root {:max-tuple-len 2
                                           :max-results 6})
        values (mapv (fn [item]
                       (mapv #(graph/value-data graph %) (:nodes item)))
                     tuples)]
    (is (= [[ [0 1 2 :x :x] ]
            [ [0 1 2 :x :x] [0 1 2 :x :x] ]
            [ [0 1 2] ]
            [ [:x :x] ]
            [ 0 ]
            [ 3 ]]
           values))))


(deftest count-trees-matches-python-wunderbaum-fixture
  (let [spec-dict {:int [[:int :float] [:int] [:array-int]]
                   :float [[:float :float] [:float]]
                   :array-int [[:array-int :int] [:array-int]]}]
    (is (= 85
           (sut/count-trees [:int] 3 spec-dict)))))


(deftest effective-dl-matches-python-dag-enumerator-formula
  (let [base-dl 8.0
        count 3.0
        total-count 5.0
        concentration 32.0
        expected (- (value/log2 (+ total-count concentration))
                    (value/log2 (+ count
                                   (* concentration
                                      (Math/pow 2.0 (- base-dl))))))]
    (is (close? base-dl
                (sut/effective-dl base-dl 0 0 concentration)))
    (is (close? 8.000000471096206
                (sut/effective-dl base-dl
                                  0
                                  0
                                  concentration
                                  0.000000471096206)))
    (is (close? expected
                (sut/effective-dl base-dl count total-count concentration)))))


(deftest usage-biased-items-rank-reused-structure-before-prior-only-structure
  (let [ranked (sut/rank-usage-biased-items [{:id :short-unused
                                              :dl 5.0
                                              :count 0}
                                             {:id :long-reused
                                              :dl 8.0
                                              :count 10}
                                             {:id :long-unused
                                              :dl 12.0
                                              :count 0}]
                                            {:concentration 32.0})]
    (is (= [:long-reused :short-unused :long-unused]
           (mapv :id ranked)))
    (is (< (:effective-dl (first ranked))
           (:effective-dl (second ranked))))))
