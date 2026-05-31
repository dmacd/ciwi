(ns ciwi.operator
  (:refer-clojure :exclude [concat name repeat])
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

(defn- seqish?
  [x]
  (and (sequential? x) (not (string? x))))

(defn- elementwise2
  [f x y]
  (cond
    (and (seqish? x) (seqish? y))
    (when (= (count x) (count y))
      (mapv f x y))

    (seqish? x)
    (mapv #(f % y) x)

    (seqish? y)
    (mapv #(f x %) y)

    :else
    (f x y)))

(def add
  (operator
   {:id :add
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (elementwise2 + x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   [[(elementwise2 - output known)]])))}))

(def sub
  (operator
   {:id :sub
    :conditions [[0] [1]]
    :call (fn [[x y]]
            (elementwise2 - x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (case (first cond)
                     0 [[(elementwise2 - known output)]]
                     1 [[(elementwise2 + output known)]]
                     ()))))}))

(def mult
  (operator
   {:id :mult
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (elementwise2 * x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (when-not (or (and (number? known) (zero? known))
                                 (and (seqish? known) (some zero? known)))
                     [[(elementwise2 / output known)]]))))}))

(def negate
  (operator
   {:id :negate
    :conditions [[]]
    :commutative? true
    :call (fn [[x]]
            (if (seqish? x)
              (mapv - x)
              (- x)))
    :inverse (fn [output _cond-inputs cond]
               (when (empty? cond)
                 [[(if (seqish? output)
                     (mapv - output)
                     (- output))]]))}))

(def brange
  (operator
   {:id :brange
    :conditions [[]]
    :call (fn [[start n]]
            (vec (range start (+ start n))))
    :inverse (fn [output _cond-inputs cond]
               (when (and (empty? cond)
                          (vector? output)
                          (seq output)
                          (every? integer? output)
                          (= output (vec (range (first output)
                                                (+ (first output) (count output))))))
                 [[(first output) (count output)]]))}))

(def repeat
  (operator
   {:id :repeat
    :conditions [[]]
    :call (fn [[x n]]
            (vec (clojure.core/repeat n x)))
    :inverse (fn [output _cond-inputs cond]
               (when (and (empty? cond)
                          (vector? output)
                          (seq output)
                          (apply = output))
                 [[(first output) (count output)]]))}))

(def concat
  (operator
   {:id :concat
    :conditions [[0] [1]]
    :call (fn [[left right]]
            (vec (clojure.core/concat left right)))
    :inverse (fn [output cond-inputs cond]
               (when (and (= 1 (count cond))
                          (vector? output))
                 (let [known (first cond-inputs)]
                   (case (first cond)
                     0 (when (and (vector? known)
                                  (= known (subvec output 0 (count known))))
                         [[(subvec output (count known))]])
                     1 (let [split (- (count output) (count known))]
                         (when (and (vector? known)
                                    (<= 0 split)
                                    (= known (subvec output split)))
                           [[(subvec output 0 split)]]))
                     ()))))}))

(def registry
  {:add add
   :sub sub
   :mult mult
   :negate negate
   :brange brange
   :repeat repeat
   :concat concat})
