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
  (is (= 4 (:data (sut/apply-op sut/len [(value/value [:a :b :c :d])]))))
  (is (= 6 (:data (sut/apply-op sut/len [(value/value "abcdef")]))))
  (is (empty? (raw-inversions sut/len 4 [[:a :b :c :d]] [0])))
  (is (= [[[3 4]]] (raw-inversions sut/concat [1 2 3 4] [[1 2]] [0])))
  (is (= [[[1 2]]] (raw-inversions sut/concat [1 2 3 4] [[3 4]] [1]))))


(deftest item-operators-run-forward-and-backward
  (is (= 5 (:data (sut/apply-op sut/getitem [(value/value [3 5 2])
                                             (value/value 1)]))))
  (is (= [3 2] (:data (sut/apply-op sut/getitem [(value/value [3 5 2])
                                                 (value/value [true false true])]))))
  (is (= [[[2.0 nil nil 3.0]]]
         (raw-inversions sut/getitem [2.0 3.0] [[true false false true]] [1])))
  (is (= [342 6 8 78]
         (:data (sut/apply-op sut/setitem [(value/value [342 6 8 252])
                                           (value/value 3)
                                           (value/value 78)]))))
  (is (= [342 78 34 252]
         (:data (sut/apply-op sut/setitem [(value/value [342 6 8 252])
                                           (value/value [false true true false])
                                           (value/value [78 34])]))))
  (is (= [[3 78]]
         (raw-inversions sut/setitem [342 6 8 78] [[342 6 8 252]] [0])))
  (is (= [[[342 nil nil 252] [78 34]]]
         (raw-inversions sut/setitem [342 78 34 252] [[false true true false]] [1])))
  (is (= [[["-" "-" "-" "-" ""] "x"]]
         (raw-inversions sut/setitem ["-" "-" "-" "-" "x"] [4] [1]))))


(deftest boolean-and-comparison-operators-run-forward-and-backward
  (is (= false (:data (sut/apply-op sut/lessthan [(value/value 5)
                                                  (value/value 2)]))))
  (is (= true (:data (sut/apply-op sut/lessthan [(value/value 5.4)
                                                 (value/value 9.1)]))))
  (is (= [true false]
         (:data (sut/apply-op sut/lessthan [(value/value [5.6 1.3])
                                            (value/value [13.4 0.9])]))))
  (is (= [false true]
         (:data (sut/apply-op sut/lessthan [(value/value [5.6 1.3])
                                            (value/value 1.6)]))))
  (is (= [[]]
         (raw-inversions sut/lessthan [false true] [[5.6 1.3] [1.6 2.0]] [0 1])))
  (is (empty? (raw-inversions sut/lessthan [true true] [[5.6 1.3] [1.6 2.0]] [0 1])))

  (is (= true (:data (sut/apply-op sut/equal [(value/value 5.6)
                                              (value/value 5.6)]))))
  (is (= [true false]
         (:data (sut/apply-op sut/equal [(value/value [5.6 6.7])
                                         (value/value [5.6 8.7])]))))
  (is (= [[[1 3]]]
         (raw-inversions sut/equal true [[1 3]] [0])))
  (is (empty? (raw-inversions sut/equal [true false] [[5.6 6.7]] [1])))
  (is (= [[[5.6 6.7]]]
         (raw-inversions sut/equal [true true] [[5.6 6.7]] [1])))

  (is (= false (:data (sut/apply-op sut/logical-not [(value/value true)]))))
  (is (= [false true]
         (:data (sut/apply-op sut/logical-not [(value/value [true false])]))))
  (is (= [[[true false]]]
         (raw-inversions sut/logical-not [false true] [] [])))

  (is (= true (:data (sut/apply-op sut/logical-and [(value/value true)
                                                    (value/value true)]))))
  (is (= false (:data (sut/apply-op sut/logical-and [(value/value true)
                                                     (value/value false)]))))
  (is (= [true false false]
         (:data (sut/apply-op sut/logical-and [(value/value [true false true])
                                               (value/value [true true false])]))))
  (is (= [[true]]
         (raw-inversions sut/logical-and true [true] [0])))
  (is (= [[true] [false]]
         (raw-inversions sut/logical-and false [false] [0])))

  (is (= true (:data (sut/apply-op sut/logical-or [(value/value false)
                                                   (value/value true)]))))
  (is (= false (:data (sut/apply-op sut/logical-or [(value/value false)
                                                    (value/value false)]))))
  (is (= [true true false]
         (:data (sut/apply-op sut/logical-or [(value/value [true false false])
                                              (value/value [false true false])]))))
  (is (= [[true] [false]]
         (raw-inversions sut/logical-or true [true] [0])))
  (is (= [[false]]
         (raw-inversions sut/logical-or false [false] [0]))))
