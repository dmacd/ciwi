(ns ciwi.value-test
  (:require [ciwi.cache :as cache]
            [ciwi.dense.core :as dense]
            [ciwi.operator :as op]
            [ciwi.value :as sut]
            [clojure.test :refer [deftest is]]))

(defn- approx=
  ([expected actual]
   (approx= expected actual 1.0e-9))
  ([expected actual epsilon]
   (< (Math/abs (- (double expected) (double actual))) epsilon)))

(defn- contains-identical?
  [needle x]
  (boolean
   (some #(identical? needle %)
         (tree-seq #(and (coll? %)
                         (not (dense/ndarray? %)))
                   seq
                   x))))

(deftest value-wraps-plain-data
  (let [v (sut/value 42)]
    (is (= 42 (:data v)))
    (is (:permeable? v))
    (is (false? (:dummy? v)))
    (is (pos? (sut/desc-len v)))))

(deftest description-length-matches-python-scalar-cases
  (doseq [[x expected] [[nil 1.0]
                        [true 1.0]
                        [0 2.0]
                        [-2 7.785969786176601]
                        [130000 17.59043274135145]
                        [43.61873 42.00637948852322]
                        [##NaN 0.0]
                        ["asdf" 38.16992500144231]]]
    (is (approx= expected (sut/desc-len-data x))
        (pr-str x))))

(deftest description-length-matches-python-structural-list-case
  (is (approx= 28.101401246189294
               (sut/desc-len-data (list 1 2 3)))))

(deftest description-length-delegates-to-native-values-and-operators
  (is (approx= 0.0
               (sut/desc-len-data (sut/value [1 2 3] {:dummy? true}))))
  (is (approx= (:dl op/add)
               (sut/desc-len-data op/add))))

(deftest description-length-matches-python-array-cases
  (doseq [[x expected-default expected-value]
          [[[12 657 831]
            54.131652201813026
            54.131652201813026]
           [[12000 657000 831000]
            59.301577203255334
            59.301577203255334]
           [[12.4 0.231 123.4123]
            92.81669545674771
            92.81669545674771]
           [[##NaN ##NaN ##NaN]
            5.325249204613158
            5.325249204613158]
           [[##NaN 1.0 ##NaN]
            11.650498409226316
            11.650498409226316]
           [[false true false true true]
            11.7859697861766
            11.7859697861766]
           [[["asdf" "dfg"] ["fdasg" "jztrertg"]]
            194.28114399223207
            194.28114399223207]
           [(vec (repeat 500 45))
            6679.886259921756
            30.92432399528238]]]
    (is (approx= expected-default
                 (sut/desc-len-data x {:mode :default}))
        (str "default " (pr-str x)))
    (is (approx= expected-value
                 (sut/desc-len-data x))
        (str "use_gaussian " (pr-str x)))))

(deftest description-length-matches-python-multidimensional-gaussian-cases
  (doseq [[x expected-default expected-value]
          [[[[0.0 0.0] [1.0 2.0] [2.0 1.0] [3.0 3.0] [4.0 5.0]]
            70.40139304519218
            69.01425963562734]
           [[[0.0 0.0] [1.0 ##NaN] [2.0 1.0] [3.0 3.0] [4.0 5.0]]
            63.61542325901558
            63.61542325901558]
           [[[[255.0 100.0 100.0] [245.0 110.0 95.0]]
             [[250.0 90.0 105.0] [260.0 105.0 100.0]]]
            199.89395346642718
            140.9993730208648]]]
    (is (approx= expected-default
                 (sut/desc-len-data x {:mode :default})))
    (is (approx= expected-value
                 (sut/desc-len-data x)))))

(deftest desc-len-cache-fingerprints-dense-values-without-retaining-raw-arrays
  (let [arr1 (dense/from-flat [1.0 2.0 3.0 4.0] [2 2] {:dtype :float64})
        v1 (sut/value arr1)
        store (cache/cache-store)
        dl1 (sut/desc-len-cached store v1)
        dl2 (sut/desc-len-cached store v1)
        keys (vec (.keySet store))]
    (is (approx= dl1 dl2))
    (is (= 1 (cache/size store)))
    (is (not-any? #(contains-identical? arr1 %) keys))))

(deftest python-description-helper-parity
  (is (approx= 46.618602316031144
               (sut/log-binomial 50 23)))
  (is (approx= 11.29920801838728
               (sut/log-multinomial 10 [2 5 3])))
  (is (approx= 27.11381997364844
               (sut/describe-by-permutation-index
                [false true false true true true true
                 true true true true true true]))))
