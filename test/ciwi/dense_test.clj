(ns ciwi.dense-test
  (:require [ciwi.dense.core :as dense]
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
    (is (= [true false false] (dense/tolist (dense/equal x 1)))))
  (is (= [[9 18] [29 38]]
         (dense/tolist
          (dense/subtract-broadcast (dense/array [[10 20] [30 40]])
                                    (dense/array [1 2]))))))

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

(deftest dense-constructors-preserve-backend-and-shape-when-requested
  (let [x (dense/array [[1 2] [3 4]])
        same-shape (dense/with-flat x [5 6 7 8])
        one-d (dense/from-flat [9 10 11] [3])
        from-template (dense/array-like x [1.5 2.5 3.5 4.5])]
    (is (= :ciwi.vector (dense/backend same-shape)))
    (is (= [2 2] (dense/shape same-shape)))
    (is (= [[5 6] [7 8]] (dense/tolist same-shape)))
    (is (= [3] (dense/shape one-d)))
    (is (= [9 10 11] (dense/tolist one-d)))
    (is (= :float64 (dense/dtype from-template)))
    (is (= [[1.5 2.5] [3.5 4.5]] (dense/tolist from-template)))))

(deftest dense-sequence-ops-stay-inside-the-dense-boundary
  (let [x (dense/array [1 3 6])]
    (is (= [0 1 2 3] (dense/tolist (dense/arange 4))))
    (is (= [2 4 6] (dense/tolist (dense/arange 2 8 2))))
    (is (= [1 3 6 1 3 6] (dense/tolist (dense/tile 2 x))))
    (is (= [1 3 6 8 10] (dense/tolist (dense/concatenate [x [8 10]]))))
    (is (= [6 1] (dense/tolist (dense/take-indices x [2 0]))))
    (is (= [1 9 6] (dense/tolist (dense/put x [1] [9]))))
    (is (= [1 4 10] (dense/tolist (dense/cumsum x))))
    (is (= [1 2 3] (dense/tolist (dense/diff x)))))
  (let [x (dense/array [[1 2] [3 4] [5 6]])]
    (is (= [[5 6] [1 2]]
           (dense/tolist (dense/take-indices x [2 0]))))
    (is (= [[1 2] [3 4] [5 6] [7 8]]
           (dense/tolist
            (dense/concatenate-axis0 [x (dense/array [[7 8]])]))))))

(deftest dense-content-fingerprint-is-fast-key-with-exact-fallback
  (let [x (dense/array [1 nil 3])
        y (dense/array [1 ##NaN 3])
        z (dense/array [1 0 3])]
    (is (= (dense/content-fingerprint x)
           (dense/content-fingerprint y)))
    (is (dense/same-content? x y))
    (is (hashing/content-equal? x y))
    (is (= (hashing/unique-hash x)
           (hashing/unique-hash y)))
    (is (not= (dense/content-fingerprint x)
              (dense/content-fingerprint z)))
    (is (not (hashing/content-equal? x z)))))

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
