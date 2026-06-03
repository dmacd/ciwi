(ns ciwi.optimize-test
  (:require [ciwi.optimize :as sut]
            [clojure.test :refer [deftest is]]))

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
        f0 (sut/objective-value objective [12.0])
        result (sut/optimize opt objective [12.0] (:score f0) (:params f0))]
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
