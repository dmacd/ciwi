(ns ciwi.spec
  (:require [ciwi.operator :as op]
            [ciwi.value :as value]))

(defn infer-spec
  "Infer the small CIWI spec key used by the Wunderbaum parity path."
  [x]
  (let [x (value/datum x)]
    (cond
      (nil? x) :unknown
      (integer? x) :int
      (float? x) :float
      (number? x) :number
      (string? x) :string
      (keyword? x) :keyword
      (and (vector? x) (every? #(or (true? %) (false? %)) x)) :array-bool
      (and (vector? x) (every? integer? x)) :array-int
      (and (vector? x) (every? float? x)) :array-float
      (and (vector? x) (every? number? x)) :array-number
      (vector? x) :array
      (op/operator? x) :operator
      :else (class x))))

(defn value-spec
  [x]
  (or (:spec (value/value x))
      (infer-spec x)))

(defn conforms?
  "Return true when an inferred value spec can inhabit an expected CIWI spec."
  [expected actual]
  (or (= expected actual)
      (= :unknown expected)
      (and (= :number expected)
           (contains? #{:int :float :number} actual))
      (and (= :array expected)
           (contains? #{:array :array-bool :array-int :array-float :array-number}
                      actual))
      (and (= :array-number expected)
           (contains? #{:array-int :array-float :array-number} actual))))
