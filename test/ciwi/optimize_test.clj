(ns ciwi.optimize-test
  (:require [ciwi.dense.core :as dense]
            [ciwi.optimize :as sut]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]])
  (:import [java.util Random]))

(defn- round3
  [x]
  (/ (Math/round (* 1000.0 (double x))) 1000.0))

(defn- gaussian-vector
  [^Random rng n scale]
  (mapv (fn [_]
          (round3 (* scale (.nextGaussian rng))))
        (range n)))

(defn- linear-fixture
  [{:keys [seed n true-d true-b x-scale noise-scale]}]
  (let [rng (Random. seed)
        x (gaussian-vector rng n x-scale)
        y (mapv (fn [x noise]
                  (round3 (+ (* x true-d) true-b noise)))
                x
                (gaussian-vector rng n noise-scale))]
    {:x x
     :y y
     :true-d true-d
     :true-b true-b}))

(defn- gaussian-matrix
  [^Random rng rows cols scale]
  (mapv (fn [_]
          (gaussian-vector rng cols scale))
        (range rows)))

(defn- l2-distance
  [left right]
  (Math/sqrt (reduce + 0.0
                     (map (fn [x y]
                            (let [d (- (double x) (double y))]
                              (* d d)))
                          left
                          right))))

(defn- all-close?
  [left right epsilon]
  (every? (fn [[x y]]
            (< (Math/abs (- (double x) (double y))) epsilon))
          (map vector left right)))

(defn- round-vector
  [xs]
  (mapv round3 xs))

(defn- matrix-regression-fixture
  [{:keys [seed n n-features]}]
  (let [rng (Random. seed)
        x-mat (dense/array (gaussian-matrix rng n n-features 10.0))
        w-init (gaussian-vector rng n-features 1.0)
        w-true (gaussian-vector rng n-features 2.0)
        noise (gaussian-vector rng n 0.2)
        linear (dense/ravel (dense/dot x-mat (dense/array w-true)))
        y (dense/array
           (mapv (fn [pred eps]
                   (round3 (+ pred 0.85 eps)))
                 linear
                 noise))]
    {:x-mat x-mat
     :w-init w-init
     :w-true w-true
     :y y}))

(defn- residual-elias-dl
  [residual]
  (first (value/desc-len-array-elias-signal residual 3)))

(defn- slope-objective
  [{:keys [x y]}]
  (fn [[d]]
    {:score (residual-elias-dl
             (mapv (fn [y x]
                     (- y (* x d)))
                   y
                   x))
     :params nil}))

(defn- slope-bias-objective
  [{:keys [x y]}]
  (fn [[d b]]
    {:score (residual-elias-dl
             (mapv (fn [y x]
                     (- y (+ (* x d) b)))
                   y
                   x))
     :params nil}))

(defn- matrix-regression-score
  [{:keys [x-mat y]} w]
  (let [pred (dense/dot x-mat (dense/array w))
        pred-rounded (dense/array-like pred (mapv round3 (dense/ravel pred)))
        residual (dense/subtract y pred-rounded)
        residual-dl (residual-elias-dl residual)
        w-dl (value/desc-len (value/value (round-vector w)))]
    (+ residual-dl w-dl)))

(defn- matrix-regression-objective
  [fixture]
  (fn [w]
    {:score (matrix-regression-score fixture w)
     :params nil}))

(deftest newton-optimizer-finds-integer-bowl-minimum
  (let [objective (fn [[a b]]
                    (let [a (long (Math/rint a))
                          b (long (Math/rint b))]
                      (if (and (<= -100 a 100)
                               (<= -100 b 100)
                               (not= 0 (mod (+ a b) 13)))
                        {:score (+ (Math/pow (- a 20) 2)
                                   (Math/pow (+ b 15) 2)
                                   3.0)
                         :params (+ a b)}
                        {:score nil :params nil})))
        opt (sut/newton-search {:int-mask [true true]
                                :max-iters 20})
        f0 (sut/objective-value objective [0 1])
        result (sut/optimize opt objective [0 1] (:score f0) nil)]
    (is (= [20.0 -15.0] (:x result)))
    (is (= 3.0 (:score result)))
    (is (= 5 (:params result)))))

(deftest newton-optimizer-handles-mixed-int-float-dimensions
  (let [objective (fn [[a-raw b]]
                    (let [a (long (Math/rint a-raw))]
                      (if (and (<= -100 a 100)
                               (<= -100.0 b 100.0))
                        {:score (double (/ (Math/round
                                            (* 1000.0
                                               (+ (Math/pow (- a 3) 2)
                                                  (Math/pow (- b 1.5) 2))))
                                           1000.0))
                         :params [a b]}
                        {:score nil :params nil})))
        opt (sut/newton-search {:int-mask [true false]
                                :max-iters 200})
        f0 (sut/objective-value objective [0.0 0.0])
        result (sut/optimize opt objective [0.0 0.0] (:score f0) nil)]
    (is (= 3 (long (Math/rint (first (:x result))))))
    (is (< (Math/abs (- 1.5 (second (:x result)))) 1.0e-2))
    (is (< (Math/abs (:score result)) 1.0e-3))))

(deftest optimizer-composes-through-search-protocol
  (let [objective (fn [[x]]
                    {:score (Math/pow (- x 3.0) 2)
                     :params x})
        opt (sut/adaptive-grid-search {:int-mask [false]
                                       :n-points 5
                                       :max-iters 20})
        f0 (sut/objective-value objective [4.0])
        result (sut/optimize opt objective [4.0] (:score f0) (:params f0))]
    (is (satisfies? sut/SearchOperator opt))
    (is (< (Math/abs (- 3.0 (first (:x result)))) 1.0))
    (is (< (:score result) (:score f0)))))


(deftest adaptive-grid-joint-sampling-finds-non-coordinate-improvement
  (let [objective (fn [[a b]]
                    (if (= (double a) (double b))
                      {:score (+ (Math/pow (- a 1.0) 2)
                                 (Math/pow (- b 1.0) 2))
                       :params [a b]}
                      {:score (+ 100.0
                                 (Math/pow (- a 1.0) 2)
                                 (Math/pow (- b 1.0) 2))
                       :params [a b]}))
        opt (sut/adaptive-grid-search {:int-mask [false false]
                                       :n-points 3
                                       :max-iters 5
                                       :jointly-optimize? true})
        f0 (sut/objective-value objective [0.0 0.0])
        result (sut/optimize opt objective [0.0 0.0] (:score f0) (:params f0))]
    (is (< (Math/abs (- 1.0 (first (:x result)))) 1.0e-6))
    (is (< (Math/abs (- 1.0 (second (:x result)))) 1.0e-6))
    (is (< (Math/abs (:score result)) 1.0e-6))))

(deftest adaptive-grid-optimizes-python-scale-residual-dl-slope
  (let [fixture (linear-fixture {:seed 2
                                 :n 1000
                                 :true-d 6.5
                                 :true-b -306.2
                                 :x-scale 100.0
                                 :noise-scale 100.0})
        objective (slope-objective fixture)
        opt (sut/adaptive-grid-search {:int-mask [false]
                                       :n-points 5})
        f0 (sut/objective-value objective [12.0])
        result (sut/optimize opt objective [12.0] (:score f0) (:params f0))
        expected-best (first
                       (apply min-key
                              second
                              (for [i (range -300 1600)
                                    :let [d (/ i 100.0)
                                          score (:score (objective [d]))]]
                                [d score])))]
    (is (< (:score result) (:score f0)))
    (is (< (Math/abs (- expected-best (first (:x result)))) 0.5))))

(deftest adaptive-grid-optimizes-python-scale-mixed-residual-dl
  (let [fixture (linear-fixture {:seed 42
                                 :n 1000
                                 :true-d 6.5
                                 :true-b -306.0
                                 :x-scale 100.0
                                 :noise-scale 100.0})
        objective (slope-bias-objective fixture)
        opt (sut/adaptive-grid-search {:int-mask [false true]
                                       :n-points 5
                                       :jointly-optimize? true})
        f0 (sut/objective-value objective [0.0 0.0])
        result (sut/optimize opt objective [0.0 0.0] (:score f0) (:params f0))
        [d-best b-best] (:x result)]
    (is (< (:score result) (:score f0)))
    (is (< (Math/abs (- 6.5 d-best)) 1.0)
        (str "Bad slope: " d-best))
    (is (< (Math/abs (- -306.0 b-best)) 60.0)
        (str "Bad bias: " b-best))))

(deftest adaptive-grid-optimizes-python-scale-matrix-regression
  (let [fixture (matrix-regression-fixture {:seed 123
                                            :n 1000
                                            :n-features 10})
        objective (matrix-regression-objective fixture)
        opt (sut/adaptive-grid-search {:int-mask (vec (repeat 10 false))
                                       :n-points 5
                                       :max-iters 20})
        f0 (sut/objective-value objective (:w-init fixture))
        result (sut/optimize opt
                             objective
                             (:w-init fixture)
                             (:score f0)
                             (:params f0))
        w-best (:x result)]
    (is (< (:score result) (:score f0)))
    (is (not (all-close? w-best (:w-init fixture) 1.0e-9)))
    (is (< (l2-distance w-best (:w-true fixture))
           (l2-distance (:w-init fixture) (:w-true fixture))))))
