(ns ciwi.operator.numeric
  (:require [ciwi.dense.core :as dense]
            [ciwi.operator.core :as core]
            [ciwi.operator.util :as u]
            [ciwi.value :as value]))

(defn- numeric-precision
  [x]
  (cond
    (dense/ndarray? x) (value/precision-array (dense/ravel x))
    (u/seqish? x) (value/precision-array (u/seq-values x))
    (number? x) (value/precision-scalar x)
    :else 0))

(defn- round-numeric
  [x decimals]
  (cond
    (integer? x)
    x

    (and (dense/ndarray? x)
         (= :int64 (dense/dtype x)))
    x

    (dense/ndarray? x)
    (dense/array-like x (mapv #(value/round-to-precision % decimals)
                              (dense/ravel x)))

    (u/seqish? x)
    (dense/array (mapv #(value/round-to-precision % decimals)
                       (u/seq-values x)))

    (number? x)
    (value/round-to-precision x decimals)

    :else x))

(defn- round-to-input-precision
  [result inputs]
  (round-numeric result (apply max (map numeric-precision inputs))))

(defn- cumsum-call
  [xs]
  (when (and (u/seqish? xs)
             (every? number? (u/seq-values xs)))
    (dense/cumsum xs)))

(defn- diff-call
  [xs]
  (when (and (u/seqish? xs)
             (every? number? (u/seq-values xs)))
    (dense/diff xs)))

(defn- trange-call
  [start stop step]
  (dense/arange start stop step))

(defn- trange-inversions
  [output cond]
  (when (and (empty? cond)
             (u/seqish? output)
             (seq (u/seq-values output))
             (every? integer? (u/seq-values output)))
    (let [values (u/seq-values output)
          step (if (> (count values) 1)
                 (- (second values) (first values))
                 1)
          stop (+ (peek values) step)]
      (when (and (not (zero? step))
                 (u/same-seqish? output (trange-call (first values) stop step)))
        [[(first values) stop step]]))))

(defn- mean-call
  [xs]
  (when (and (u/seqish? xs)
             (seq (u/seq-values xs))
             (every? number? (u/seq-values xs)))
    (/ (double (reduce + (u/seq-values xs)))
       (u/seq-count xs))))

(def add
  (core/operator
   {:id :add
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (dense/add x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (when-let [result (u/maybe-call dense/subtract output known)]
                     [[(round-numeric result (numeric-precision output))]]))))}))

(def sub
  (core/operator
   {:id :sub
    :conditions [[0] [1]]
    :call (fn [[x y]]
            (dense/subtract x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (when-let [result (case (first cond)
                                       0 (u/maybe-call dense/subtract known output)
                                       1 (u/maybe-call dense/add output known)
                                       nil)]
                     [[result]]))))}))

(def mult
  (core/operator
   {:id :mult
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (dense/multiply x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (when-not (or (and (number? known) (zero? known))
                                 (and (u/seqish? known)
                                      (some zero? (u/seq-values known))))
                     (when-let [result (u/maybe-call dense/divide output known)]
                     (when-let [result (u/maybe-integral-quotient output known result)]
                       [[result]]))))))}))

(def dot
  (core/operator
   {:id :dot
    :call (fn [[x y]]
            (round-to-input-precision (dense/dot x y) [x y]))}))

(def negate
  (core/operator
   {:id :negate
    :conditions [[]]
    :commutative? true
    :call (fn [[x]]
            (dense/negative x))
    :inverse (fn [output _cond-inputs cond]
               (when (empty? cond)
                 [[(dense/negative output)]]))}))

(def lessthan
  (core/operator
   {:id :lessthan
    :conditions [[0 1]]
    :call (fn [[x y]]
            (dense/less x y))
    :inverse (fn [output cond-inputs condition]
               (when (= [0 1] (vec condition))
                 (let [[x y] cond-inputs]
                   (when (= output (u/maybe-call dense/less x y))
                     [[]]))))}))

(def equal
  (core/operator
   {:id :equal
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (dense/equal x y))
    :inverse (fn [output cond-inputs condition]
               (when (and (= 1 (count condition))
                          (u/all-true? output))
                 [[(first cond-inputs)]]))}))

(def logical-not
  (core/operator
   {:id :not
    :conditions [[]]
    :call (fn [[x]]
            (u/elementwise1 clojure.core/not x))
    :inverse (fn [output _cond-inputs condition]
               (when (empty? condition)
                 [[(u/elementwise1 clojure.core/not output)]]))}))

(def logical-and
  (core/operator
   {:id :and
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (u/logical-and-call x y))
    :inverse (fn [output cond-inputs condition]
               (when (= 1 (count condition))
                 (u/logical-scalar-inverses u/logical-and-call
                                            output
                                            (first cond-inputs))))}))

(def logical-or
  (core/operator
   {:id :or
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (u/logical-or-call x y))
    :inverse (fn [output cond-inputs condition]
               (when (= 1 (count condition))
                 (u/logical-scalar-inverses u/logical-or-call
                                            output
                                            (first cond-inputs))))}))

(def len
  (core/operator
   {:id :len
    :conditions [[0]]
    :call (fn [[x]]
            (u/seq-count x))}))

(def brange
  (core/operator
   {:id :brange
    :conditions [[]]
    :call (fn [[start n]]
            (dense/arange start n))
    :inverse (fn [output _cond-inputs cond]
               (when (and (empty? cond)
                          (u/seqish? output)
                          (seq (u/seq-values output))
                          (every? integer? (u/seq-values output)))
                 (let [values (u/seq-values output)]
                   (when (= values (vec (range (first values)
                                               (+ (first values) (count values)))))
                     [[(first values) (inc (last values))]]))))}))

(def trange
  (core/operator
   {:id :trange
    :conditions [[]]
    :call (fn [[start stop step]]
            (or (trange-call start stop step)
                (throw (ex-info "trange expects a non-zero step"
                                {:start start :stop stop :step step}))))
    :inverse (fn [output _cond-inputs cond]
               (trange-inversions output cond))}))

(def mean
  (core/operator
   {:id :mean
    :call (fn [[xs]]
            (or (mean-call xs)
                (throw (ex-info "mean expects a non-empty numeric sequence"
                                {:xs xs}))))}))

(def cumsum
  (core/operator
   {:id :cumsum
    :conditions [[]]
    :call (fn [[xs]]
            (or (cumsum-call xs)
                (throw (ex-info "cumsum expects a numeric vector" {:xs xs}))))
    :inverse (fn [output _cond-inputs cond]
               (when (empty? cond)
                 (when-let [diffs (diff-call output)]
                   [[diffs]])))}))
