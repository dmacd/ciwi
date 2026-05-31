(ns ciwi.enumerator-test
  (:require [ciwi.enumerator :as sut]
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
