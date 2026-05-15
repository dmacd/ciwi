(ns ciwi.operator
  (:refer-clojure :exclude [name])
  (:require [ciwi.value :as value]))

(defrecord Operator [id conditions commutative? call inverse])

(defn operator?
  [x]
  (instance? Operator x))

(defn apply-op
  [operator inputs]
  (value/value ((:call operator) (mapv value/datum inputs))))

(defn invert-op
  [operator output cond-inputs cond]
  (map (fn [values]
         (mapv value/value values))
       ((:inverse operator)
        (value/datum output)
        (mapv value/datum cond-inputs)
        (vec cond))))

(def add
  (->Operator
   :add
   [[0] [1]]
   true
   (fn [[x y]]
     (+ x y))
   (fn [output cond-inputs cond]
     (when (= 1 (count cond))
       (let [known (first cond-inputs)]
         [[(- output known)]])))))

(def negate
  (->Operator
   :negate
   [[]]
   true
   (fn [[x]]
     (- x))
   (fn [output _cond-inputs cond]
     (when (empty? cond)
       [[(- output)]]))))
