(ns ciwi.dense-test
  (:require [ciwi.dense :as dense]
            [ciwi.hashing :as hashing]
            [ciwi.spec :as spec]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is testing]]))

(defn- approx=
  ([expected actual]
   (approx= expected actual 1.0e-9))
  ([expected actual epsilon]
   (< (Math/abs (- (double expected) (double actual))) epsilon)))

(deftest vector-backend-preserves-numpy-like-array-metadata
  (let [x (dense/array [[1 2 3] [4 5 6]])]
    (is (dense/ndarray? x))
    (is (= :ciwi.vector (dense/backend x)))
    (is (= :int64 (dense/dtype x)))
    (is (= [2 3] (dense/shape x)))
    (is (= 2 (dense/ndim x)))
    (is (= 6 (dense/size x)))
    (is (= [1 2 3 4 5 6] (dense/ravel x)))
    (is (= [[1 2 3] [4 5 6]] (dense/tolist x)))))

(deftest nil-numeric-slots-become-nan
  (let [x (dense/array [1 nil 3])]
    (is (= :float64 (dense/dtype x)))
    (is (= 1.0 (first (dense/ravel x))))
    (is (= 3.0 (nth (dense/ravel x) 2)))
    (is (Double/isNaN (double (second (dense/ravel x)))))
    (is (= [false true false]
           (dense/tolist (dense/isnan x))))))

(deftest dense-elementwise-ops-follow-basic-numpy-broadcasting
  (let [x (dense/array [1 2 3])
        y (dense/array [10 20 30])]
    (is (= [11 22 33] (dense/tolist (dense/add x y))))
    (is (= [2 4 6] (dense/tolist (dense/multiply x 2))))
    (is (= [-1 -2 -3] (dense/tolist (dense/negative x))))
    (is (= [true true false] (dense/tolist (dense/less x 3))))
    (is (= [true false false] (dense/tolist (dense/equal x 1))))))

(deftest dense-dot-covers-vector-and-matrix-regression-shapes
  (is (approx= 32.0
               (dense/dot (dense/array [1 2 3])
                          (dense/array [4 5 6]))))
  (is (= [17.0 39.0]
         (dense/tolist
          (dense/dot (dense/array [[1 2] [3 4]])
                     (dense/array [5 6])))))
  (is (= [6.0 15.0]
         (dense/tolist (dense/sum (dense/array [[1 2 3] [4 5 6]]) 1)))))

(deftest dense-values-integrate-with-spec-value-dl-and-hashing
  (let [x (dense/array [12 657 831])
        y (dense/array [12 657 831])]
    (is (= :array-int (spec/infer-spec x)))
    (is (approx= (value/desc-len-data [12 657 831])
                 (value/desc-len-data x)))
    (is (= (hashing/stable-key x)
           (hashing/stable-key y)))))

(deftest dense-missing-values-match-existing-nan-dl-semantics
  (let [x (dense/array [nil 1.0 nil])]
    (is (= :array-float (spec/infer-spec x)))
    (is (approx= (value/desc-len-data [##NaN 1.0 ##NaN])
                 (value/desc-len-data x)))))

(deftest dense-arrays-reject-non-rectangular-and-non-numeric-data
  (testing "ragged arrays"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"rectangular"
         (dense/array [[1] [2 3]]))))
  (testing "object arrays are outside the dense numeric boundary"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"numeric and boolean"
         (dense/array ["a" "b"])))))
