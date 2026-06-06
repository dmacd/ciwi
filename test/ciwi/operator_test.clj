(ns ciwi.operator-test
  (:require [ciwi.operator :as sut]
            [ciwi.test-helpers :as h]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(defn- data
  [v]
  (h/plain-missing (:data v)))

(defn raw-inversions
  [operator output cond-inputs cond]
  (->> (sut/invert-op operator
                      (value/value output)
                      (mapv value/value cond-inputs)
                      cond)
       (mapv (fn [values]
               (mapv (comp h/plain-missing :data) values)))))

(deftest numeric-operators-run-forward-and-backward
  (is (= 12 (data (sut/apply-op sut/add [(value/value 5) (value/value 7)]))))
  (is (= [[7]] (raw-inversions sut/add 12 [5] [0])))
  (is (= [[5]] (raw-inversions sut/add 12 [7] [1])))
  (is (empty? (raw-inversions sut/add [1 2 3] [[1 2]] [0])))
  (is (= [[7]] (raw-inversions sut/sub 5 [12] [0])))
  (is (= [[12]] (raw-inversions sut/sub 5 [7] [1])))
  (is (empty? (raw-inversions sut/sub [1 2 3] [[1 2]] [0])))
  (is (= [[4]] (raw-inversions sut/mult 12 [3] [0])))
  (is (empty? (raw-inversions sut/mult [1 2 3] [[1 2]] [0])))
  (is (= [[3]] (raw-inversions sut/negate -3 [] []))))

(deftest sequence-operators-run-forward-and-backward
  (is (= [3 4 5] (data (sut/apply-op sut/brange [(value/value 3) (value/value 6)]))))
  (is (= [[3 6]] (raw-inversions sut/brange [3 4 5] [] [])))
  (is (= [:x :x :x] (data (sut/apply-op sut/repeat [(value/value 3) (value/value [:x])]))))
  (is (= [1 5 1 5 1 5]
         (data (sut/apply-op sut/repeat [(value/value 3) (value/value [1 5])]))))
  (is (= [[3 [:x]]] (raw-inversions sut/repeat [:x :x :x] [] [])))
  (is (= [[[1 5]]] (raw-inversions sut/repeat [1 5 1 5 1 5] [3] [0])))
  (is (= [[3]] (raw-inversions sut/repeat [1 5 1 5 1 5] [[1 5]] [1])))
  (is (= [0 -1 -2] (data (sut/apply-op sut/map-op [(value/value :negate)
                                                    (value/value [0 1 2])]))))
  (is (= [[[0 1 2]]] (raw-inversions sut/map-op [0 -1 -2] [:negate] [0])))
  (is (= [0 1 3 6 10] (data (sut/apply-op sut/cumsum [(value/value [0 1 2 3 4])]))))
  (is (= [[[0 1 2 3 4]]] (raw-inversions sut/cumsum [0 1 3 6 10] [] [])))
  (is (= [4 2 13 6 13 13]
         (data (sut/apply-op sut/insert [(value/value [2 4 5])
                                         (value/value 13)
                                         (value/value [4 2 6])]))))
  (is (= [[["b" "e"] ["a" "c" "d"]]]
         (raw-inversions sut/insert ["a" "b" "c" "d" "e"] [[1 4]] [0])))
  (is (= [[[1 4] ["a" "c" "d"]]]
         (raw-inversions sut/insert ["a" "b" "c" "d" "b"] ["b"] [1])))
  (is (= 4 (data (sut/apply-op sut/len [(value/value [:a :b :c :d])]))))
  (is (= 6 (data (sut/apply-op sut/len [(value/value "abcdef")]))))
  (is (empty? (raw-inversions sut/len 4 [[:a :b :c :d]] [0])))
  (is (= [[[3 4]]] (raw-inversions sut/concat [1 2 3 4] [[1 2]] [0])))
  (is (= [[[1 2]]] (raw-inversions sut/concat [1 2 3 4] [[3 4]] [1]))))


(deftest item-operators-run-forward-and-backward
  (is (= 5 (data (sut/apply-op sut/getitem [(value/value [3 5 2])
                                            (value/value 1)]))))
  (is (= [3 2] (data (sut/apply-op sut/getitem [(value/value [3 5 2])
                                                (value/value [true false true])]))))
  (is (empty? (raw-inversions sut/getitem 7 [] [])))
  (is (= [[[2.0 nil nil 3.0]]]
         (raw-inversions sut/getitem [2.0 3.0] [[true false false true]] [1])))
  (is (= [342 6 8 78]
         (data (sut/apply-op sut/setitem [(value/value [342 6 8 252])
                                          (value/value 3)
                                          (value/value 78)]))))
  (is (= [342 78 34 252]
         (data (sut/apply-op sut/setitem [(value/value [342 6 8 252])
                                          (value/value [false true true false])
                                          (value/value [78 34])]))))
  (is (= [[[3] [78]]]
         (raw-inversions sut/setitem [342 6 8 78] [[342 6 8 252]] [0])))
  (is (= [[[1 2] [87 87]]]
         (raw-inversions sut/setitem [45 87 87] [[45 45 45]] [0])))
  (is (= [[4 "x"]]
         (raw-inversions sut/setitem ["-" "-" "-" "-" "x"]
                         [["-" "-" "-" "-" "-"]]
                         [0])))
  (is (= [[[342.0 nil nil 252.0] [78 34]]]
         (raw-inversions sut/setitem [342 78 34 252] [[false true true false]] [1])))
  (is (= [[["-" "-" "-" "-" ""] "x"]]
         (raw-inversions sut/setitem ["-" "-" "-" "-" "x"] [4] [1]))))


(deftest boolean-and-comparison-operators-run-forward-and-backward
  (is (= false (data (sut/apply-op sut/lessthan [(value/value 5)
                                                 (value/value 2)]))))
  (is (= true (data (sut/apply-op sut/lessthan [(value/value 5.4)
                                                (value/value 9.1)]))))
  (is (= [true false]
         (data (sut/apply-op sut/lessthan [(value/value [5.6 1.3])
                                           (value/value [13.4 0.9])]))))
  (is (= [false true]
         (data (sut/apply-op sut/lessthan [(value/value [5.6 1.3])
                                           (value/value 1.6)]))))
  (is (= [[]]
         (raw-inversions sut/lessthan [false true] [[5.6 1.3] [1.6 2.0]] [0 1])))
  (is (empty? (raw-inversions sut/lessthan [true true] [[5.6 1.3] [1.6 2.0]] [0 1])))

  (is (= true (data (sut/apply-op sut/equal [(value/value 5.6)
                                             (value/value 5.6)]))))
  (is (= [true false]
         (data (sut/apply-op sut/equal [(value/value [5.6 6.7])
                                        (value/value [5.6 8.7])]))))
  (is (= [[[1 3]]]
         (raw-inversions sut/equal true [[1 3]] [0])))
  (is (empty? (raw-inversions sut/equal [true false] [[5.6 6.7]] [1])))
  (is (= [[[5.6 6.7]]]
         (raw-inversions sut/equal [true true] [[5.6 6.7]] [1])))

  (is (= false (data (sut/apply-op sut/logical-not [(value/value true)]))))
  (is (= [false true]
         (data (sut/apply-op sut/logical-not [(value/value [true false])]))))
  (is (= [[[true false]]]
         (raw-inversions sut/logical-not [false true] [] [])))

  (is (= true (data (sut/apply-op sut/logical-and [(value/value true)
                                                   (value/value true)]))))
  (is (= false (data (sut/apply-op sut/logical-and [(value/value true)
                                                    (value/value false)]))))
  (is (= [true false false]
         (data (sut/apply-op sut/logical-and [(value/value [true false true])
                                              (value/value [true true false])]))))
  (is (= [[true]]
         (raw-inversions sut/logical-and true [true] [0])))
  (is (= [[true] [false]]
         (raw-inversions sut/logical-and false [false] [0])))

  (is (= true (data (sut/apply-op sut/logical-or [(value/value false)
                                                  (value/value true)]))))
  (is (= false (data (sut/apply-op sut/logical-or [(value/value false)
                                                   (value/value false)]))))
  (is (= [true true false]
         (data (sut/apply-op sut/logical-or [(value/value [true false false])
                                             (value/value [false true false])]))))
  (is (= [[true] [false]]
         (raw-inversions sut/logical-or true [true] [0])))
  (is (= [[false]]
         (raw-inversions sut/logical-or false [false] [0]))))
