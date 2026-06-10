(ns ciwi.operator.util
  (:refer-clojure :exclude [concat repeat])
  (:require [ciwi.dense.core :as dense]))

(defn seqish?
  [x]
  (or (dense/ndarray? x)
      (and (sequential? x) (not (string? x)))))

(defn seq-values
  [x]
  (if (dense/ndarray? x)
    (dense/ravel x)
    (vec x)))

(defn seq-count
  [x]
  (if (dense/ndarray? x)
    (dense/size x)
    (count x)))

(defn seq-nth
  [x idx]
  (if (dense/ndarray? x)
    (nth (dense/ravel x) idx)
    (nth x idx)))

(defn- dense-template
  [templates]
  (first (filter dense/ndarray? templates)))

(defn maybe-array
  [xs & templates]
  (if (dense/array-literal? xs)
    (if-let [template (dense-template templates)]
      (let [flat (vec xs)]
        (if (= (count flat) (dense/size template))
          (dense/with-flat template flat)
          (dense/from-flat flat [(count flat)] {:backend (dense/backend template)})))
      (dense/array xs))
    (vec xs)))

(defn maybe-call
  [f & args]
  (try
    (apply f args)
    (catch clojure.lang.ExceptionInfo _
      nil)))

(defn- integral-data?
  [x]
  (cond
    (integer? x) true
    (number? x) false
    (dense/ndarray? x) (= :int64 (dense/dtype x))
    (seqish? x) (every? integer? (seq-values x))
    :else false))

(defn- integral-result?
  [x]
  (cond
    (integer? x) true
    (number? x) (== (double x) (double (long x)))
    (dense/ndarray? x) (every? integral-result? (dense/ravel x))
    (seqish? x) (every? integral-result? (seq-values x))
    :else false))

(defn- coerce-integral-result
  [x]
  (cond
    (integer? x) (long x)
    (number? x) (long x)
    (dense/ndarray? x) (dense/with-flat x (mapv long (dense/ravel x)) {:dtype :int64})
    (seqish? x) (dense/from-flat (mapv long (seq-values x))
                                 [(seq-count x)]
                                 {:dtype :int64})
    :else x))

(defn maybe-integral-quotient
  [output known result]
  (if (and (integral-data? output)
           (integral-data? known))
    (when (integral-result? result)
      (coerce-integral-result result))
    result))

(defn strict-vec
  [x]
  (if (dense/ndarray? x)
    (dense/ravel x)
    (if (vector? x)
      x
      (vec x))))

(defn- mapv1-strict
  [f xs]
  (let [xs (strict-vec xs)
        n (count xs)]
    (loop [idx 0
           result (transient [])]
      (if (= idx n)
        (persistent! result)
        (recur (inc idx)
               (conj! result (f (nth xs idx))))))))

(defn- mapv2-strict
  [f xs ys]
  (let [xs (strict-vec xs)
        ys (strict-vec ys)
        n (count xs)]
    (when (= n (count ys))
      (loop [idx 0
             result (transient [])]
        (if (= idx n)
          (persistent! result)
          (recur (inc idx)
                 (conj! result (f (nth xs idx) (nth ys idx)))))))))

(def ^:private strict-map-threshold 4096)

(defn elementwise1
  [f x]
  (if (seqish? x)
    (let [xs (seq-values x)]
      (maybe-array
       (if (>= (count xs) strict-map-threshold)
         (mapv1-strict f xs)
         (mapv f xs))
       x))
    (f x)))

(defn elementwise2
  [f x y]
  (cond
    (and (seqish? x) (seqish? y))
    (let [xs (seq-values x)
          ys (seq-values y)]
      (when (= (count xs) (count ys))
        (maybe-array
         (if (>= (count xs) strict-map-threshold)
           (mapv2-strict f xs ys)
           (mapv f xs ys))
         x y)))

    (seqish? x)
    (let [xs (seq-values x)]
      (maybe-array
       (if (>= (count xs) strict-map-threshold)
         (mapv1-strict #(f % y) xs)
         (mapv #(f % y) xs))
       x))

    (seqish? y)
    (let [ys (seq-values y)]
      (maybe-array
       (if (>= (count ys) strict-map-threshold)
         (mapv1-strict #(f x %) ys)
         (mapv #(f x %) ys))
       y))

    :else
    (f x y)))

(defn all-true?
  [x]
  (if (seqish? x)
    (every? true? (seq-values x))
    (true? x)))

(defn boolean-scalar?
  [x]
  (or (true? x) (false? x)))

(defn logical-and-call
  [x y]
  (elementwise2 #(boolean (clojure.core/and %1 %2)) x y))

(defn logical-or-call
  [x y]
  (elementwise2 #(boolean (clojure.core/or %1 %2)) x y))

(defn logical-scalar-inverses
  [f output known]
  (when (and (boolean-scalar? output)
             (boolean-scalar? known))
    (for [candidate [true false]
          :when (= output (f known candidate))]
      [candidate])))

(defn index-vector?
  [x]
  (or (and (dense/ndarray? x)
           (= :int64 (dense/dtype x))
           (= 1 (dense/ndim x)))
      (and (vector? x) (every? integer? x))))

(defn bool-mask?
  [x]
  (or (and (dense/ndarray? x)
           (= :bool (dense/dtype x))
           (= 1 (dense/ndim x)))
      (and (vector? x) (every? #(or (true? %) (false? %)) x))))

(defn seq-literal?
  [x]
  (or (seqish? x) (string? x)))

(defn dense-concat-compatible?
  [x]
  (or (dense/ndarray? x)
      (and (vector? x) (dense/array-literal? x))))

(defn valid-index?
  [xs idx]
  (and (integer? idx) (<= 0 idx) (< idx (seq-count xs))))

(defn valid-indices?
  [xs idxs]
  (every? #(valid-index? xs %) idxs))

(defn mask-indices
  [mask]
  (keep-indexed (fn [idx selected?]
                  (when selected? idx))
                (seq-values mask)))

(defn selected-count
  [mask]
  (count (filter true? (seq-values mask))))

(defn prefix-motif
  [xs motif-len]
  (if (string? xs)
    (subs xs 0 motif-len)
    (maybe-array (subvec (seq-values xs) 0 motif-len) xs)))

(defn same-seqish?
  [left right]
  (if (and (string? left) (string? right))
    (= left right)
    (= (seq-values left) (seq-values right))))
