(ns ciwi.test-helpers
  (:require [ciwi.dense :as dense]
            [ciwi.value :as value]))

(defn plain
  [x]
  (let [x (value/datum x)]
    (if (dense/ndarray? x)
      (dense/tolist x)
      x)))

(defn- nan->nil
  [x]
  (cond
    (dense/nan? x) nil
    (vector? x) (mapv nan->nil x)
    :else x))

(defn plain-missing
  [x]
  (nan->nil (plain x)))

(defn nested-plain
  [x]
  (cond
    (dense/ndarray? x) (dense/tolist x)
    (value/value? x) (nested-plain (value/datum x))
    (vector? x) (mapv nested-plain x)
    (seq? x) (map nested-plain x)
    (map? x) (into {} (map (fn [[k v]] [k (nested-plain v)])) x)
    :else x))

(defn nested-plain-missing
  [x]
  (nan->nil (nested-plain x)))
