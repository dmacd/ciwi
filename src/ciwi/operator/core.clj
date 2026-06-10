(ns ciwi.operator.core
  (:require [ciwi.value :as value]))

(defrecord Operator [id conditions commutative? call inverse dl])

(defn operator
  [{:keys [id conditions commutative? call inverse dl]
    :or {conditions []
         commutative? false
         inverse (constantly ())
         dl 1.0}}]
  (->Operator id conditions commutative? call inverse dl))

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
