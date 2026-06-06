(ns ciwi.dense-djl-test
  (:require [ciwi.dense.core :as dense]
            [ciwi.dense.djl :as djl]
            [ciwi.hashing :as hashing]
            [ciwi.spec :as spec]
            [ciwi.value :as value]
            [clojure.test :as test :refer [deftest is testing]]))

(dense/register-backend! djl/backend)

(defn- array
  [data]
  (dense/array data {:backend :ciwi.djl}))

(defn- approx=
  ([expected actual]
   (approx= expected actual 1.0e-9))
  ([expected actual epsilon]
   (< (Math/abs (- (double expected) (double actual))) epsilon)))

(deftest djl-backend-preserves-dense-metadata
  (let [x (array [[1 2 3] [4 5 6]])]
    (is (dense/ndarray? x))
    (is (= :ciwi.djl (dense/backend x)))
    (is (= :int64 (dense/dtype x)))
    (is (= [2 3] (dense/shape x)))
    (is (= 2 (dense/ndim x)))
    (is (= 6 (dense/size x)))
    (is (= [1 2 3 4 5 6] (dense/ravel x)))
    (is (= [[1 2 3] [4 5 6]] (dense/tolist x)))))

(deftest djl-backend-normalizes-missing-float-slots
  (let [x (array [1 nil 3])]
    (is (= :float64 (dense/dtype x)))
    (is (= 1.0 (first (dense/ravel x))))
    (is (= 3.0 (nth (dense/ravel x) 2)))
    (is (Double/isNaN (double (second (dense/ravel x)))))
    (is (= [false true false]
           (dense/tolist (dense/isnan x))))))

(deftest djl-backend-runs-core-array-ops
  (let [x (array [1 2 3])
        y (array [10 20 30])]
    (is (= [11 22 33] (dense/tolist (dense/add x y))))
    (is (= [2 4 6] (dense/tolist (dense/multiply x 2))))
    (is (= [-1 -2 -3] (dense/tolist (dense/negative x))))
    (is (= [true true false] (dense/tolist (dense/less x 3))))
    (is (= [true false false] (dense/tolist (dense/equal x 1))))))

(deftest djl-backend-covers-regression-matrix-ops
  (is (approx= 32.0
               (dense/dot (array [1 2 3])
                          (array [4 5 6]))))
  (is (= [17.0 39.0]
         (dense/tolist
          (dense/dot (array [[1 2] [3 4]])
                     (array [5 6])))))
  (is (= [6.0 15.0]
         (dense/tolist (dense/sum (array [[1 2 3] [4 5 6]]) 1)))))

(deftest djl-backend-sequence-edit-helpers-match-vector-backend-semantics
  (let [x (array [1 3 6])]
    (is (= [0 1 2 3] (dense/tolist (dense/arange 0 4 1 {:backend :ciwi.djl}))))
    (is (= [2 4 6] (dense/tolist (dense/arange 2 8 2 {:backend :ciwi.djl}))))
    (is (= [1 3 6 1 3 6] (dense/tolist (dense/tile 2 x))))
    (is (= [1 3 6 8 10] (dense/tolist (dense/concatenate [x [8 10]]))))
    (is (= [6 1] (dense/tolist (dense/take-indices x [2 0]))))
    (is (= [1 9 6] (dense/tolist (dense/put x [1] [9]))))
    (is (= [1 4 10] (dense/tolist (dense/cumsum x))))
    (is (= [1 2 3] (dense/tolist (dense/diff x))))))

(deftest djl-content-fingerprint-remains-an-exact-bucket-key
  (let [x (array [1 nil 3])
        y (array [1 ##NaN 3])
        z (array [1 0 3])]
    (is (= (dense/content-fingerprint x)
           (dense/content-fingerprint y)))
    (is (dense/same-content? x y))
    (is (hashing/content-equal? x y))
    (is (= (hashing/unique-hash x)
           (hashing/unique-hash y)))
    (is (not= (dense/content-fingerprint x)
              (dense/content-fingerprint z)))
    (is (not (hashing/content-equal? x z)))))

(deftest djl-values-integrate-with-spec-dl-and-stable-hashing
  (let [x (array [12 657 831])
        y (array [12 657 831])]
    (is (= :array-int (spec/infer-spec x)))
    (is (approx= (value/desc-len-data [12 657 831])
                 (value/desc-len-data x)))
    (is (= (hashing/stable-key x)
           (hashing/stable-key y)))))

(deftest djl-backend-can-be-installed-as-the-default-backend
  (let [old-backend (dense/default-backend)]
    (try
      (dense/set-default-backend! :ciwi.djl)
      (let [x (dense/array [1 2 3])]
        (is (= :ciwi.djl (dense/backend x)))
        (is (= [2 3 4] (dense/tolist (dense/add x 1)))))
      (finally
        (dense/set-default-backend! old-backend)))))

(defn -main
  [& _args]
  (try
    (let [{:keys [fail error]} (test/run-tests 'ciwi.dense-djl-test)]
      (when (pos? (+ fail error))
        (System/exit 1)))
    (finally
      (djl/shutdown!))))
