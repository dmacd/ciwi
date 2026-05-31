(ns ciwi.enumerator-test
  (:require [ciwi.dsl :as dsl]
            [ciwi.enumerator :as sut]
            [ciwi.graph :as graph]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

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
  (let [{:keys [graph root]} (dsl/from-expr [:concat [:brange 0 3] [:repeat :x 2]])
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
