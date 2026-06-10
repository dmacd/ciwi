(ns ciwi.operator.mapping
  (:require [ciwi.operator.core :as core]
            [ciwi.operator.util :as u]
            [ciwi.value :as value]))

(defn- callable-op
  [registry f]
  (cond
    (core/operator? f) f
    (keyword? f) (get registry f)
    :else nil))

(defn map-call
  [registry f xs]
  (when-let [f (callable-op registry f)]
    (when (u/seqish? xs)
      (let [shortcut (try
                       (value/datum (core/apply-op f [(value/value xs)]))
                       (catch Throwable _ ::failed))]
        (if (and (not= ::failed shortcut)
                 (u/seqish? shortcut)
                 (= (u/seq-count shortcut) (u/seq-count xs)))
          shortcut
          (u/maybe-array
           (mapv (fn [x]
                   (value/datum (core/apply-op f [(value/value x)])))
                 (u/seq-values xs))
           xs))))))

(defn- cartesian-product
  [colls]
  (reduce (fn [prefixes coll]
            (for [prefix prefixes
                  x coll]
              (conj prefix x)))
          [[]]
          colls))

(defn- elementwise-inversions
  [f output]
  (when (u/seqish? output)
    (let [output-template output
          output (u/seq-values output)
          output-count (count output)
          empty-positions (vec (clojure.core/repeat output-count []))
          inversions-by-type
          (loop [idx 0
                 by-type {}]
            (if (= idx output-count)
              by-type
              (let [inversions (mapv (fn [values]
                                       (value/datum (first values)))
                                     (core/invert-op f
                                                     (value/value (nth output idx))
                                                     []
                                                     []))]
                (if (seq inversions)
                  (let [grouped (group-by type inversions)
                        by-type (reduce-kv
                                 (fn [acc typ inferred-values]
                                   (update acc typ
                                           (fn [positions]
                                             (assoc (or positions empty-positions)
                                                    idx
                                                    (vec inferred-values)))))
                                 by-type
                                 grouped)]
                    (recur (inc idx) by-type))
                  nil))))]
      (when (seq inversions-by-type)
        (->> inversions-by-type
             vals
             (keep (fn [position-inversions]
                     (when (and (every? seq position-inversions)
                                (<= (reduce * 1 (map count position-inversions))
                                    100))
                       position-inversions)))
             (mapcat cartesian-product)
             (mapv (fn [xs] [(u/maybe-array (vec xs) output-template)])))))))

(defn map-inversions
  [registry output cond-inputs cond]
  (when (= [0] (vec cond))
    (when-let [f (callable-op registry (first cond-inputs))]
      (or (seq (mapv (fn [values]
                       [(value/datum (first values))])
                     (core/invert-op f (value/value output) [] [])))
          (elementwise-inversions f output)))))
