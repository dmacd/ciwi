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

(defn- index-vector?
  [x]
  (and (vector? x) (every? integer? x)))

(defn- bool-mask?
  [x]
  (and (vector? x) (every? #(or (true? %) (false? %)) x)))

(defn- valid-index?
  [xs idx]
  (and (integer? idx) (<= 0 idx) (< idx (count xs))))

(defn- valid-indices?
  [xs idxs]
  (every? #(valid-index? xs %) idxs))

(defn- mask-indices
  [mask]
  (keep-indexed (fn [idx selected?]
                  (when selected? idx))
                mask))

(defn- selected-count
  [mask]
  (count (filter true? mask)))

(defn- getitem-call
  [xs idx]
  (let [xs (vec xs)]
    (cond
      (integer? idx)
      (when (valid-index? xs idx)
        (nth xs idx))

      (bool-mask? idx)
      (when (= (count xs) (count idx))
        (mapv #(nth xs %) (mask-indices idx)))

      (index-vector? idx)
      (when (valid-indices? xs idx)
        (mapv #(nth xs %) idx))

      :else nil)))

(defn- set-many
  [xs indices values]
  (when (and (valid-indices? xs indices)
             (= (count indices) (count values)))
    (reduce (fn [acc [idx value]]
              (assoc acc idx value))
            (vec xs)
            (map vector indices values))))

(defn- setitem-call
  [xs idx item]
  (let [xs (vec xs)]
    (cond
      (integer? idx)
      (when (valid-index? xs idx)
        (assoc xs idx item))

      (bool-mask? idx)
      (when (= (count xs) (count idx))
        (set-many xs (vec (mask-indices idx)) (vec item)))

      (index-vector? idx)
      (set-many xs idx (vec item))

      :else nil)))

(defn- missing-sentinel
  [x]
  (cond
    (string? x) ""
    (boolean? x) false
    :else nil))

(defn- source-template-after-set
  [output indices]
  (when (valid-indices? output indices)
    (let [written? (set indices)]
      (mapv (fn [idx value]
              (if (written? idx)
                (missing-sentinel value)
                value))
            (range (count output))
            output))))

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

(def getitem
  (operator
   {:id :getitem
    :conditions [[] [1]]
    :call (fn [[xs idx]]
            (or (getitem-call xs idx)
                (throw (ex-info "getitem index out of bounds or unsupported"
                                {:xs xs :idx idx}))))
    :inverse (fn [output cond-inputs condition]
               (case (vec condition)
                 [] (let [values (vec (distinct output))
                          indices (mapv (fn [x]
                                          (.indexOf values x))
                                        output)]
                      [[values indices]])
                 [1] (let [idx (first cond-inputs)]
                       (cond
                         (and (bool-mask? idx)
                              (= (selected-count idx) (count output)))
                         [[(reduce (fn [acc [selected-idx value]]
                                      (assoc acc selected-idx value))
                                    (vec (clojure.core/repeat (count idx) nil))
                                    (map vector (mask-indices idx) output))]]

                         (and (index-vector? idx)
                              (= (count idx) (count output)))
                         (let [n (inc (reduce max -1 idx))]
                           [[(reduce (fn [acc [selected-idx value]]
                                        (assoc acc selected-idx value))
                                      (vec (clojure.core/repeat n nil))
                                      (map vector idx output))]])

                         :else nil))
                 nil))}))

(def setitem
  (operator
   {:id :setitem
    :conditions [[0] [1]]
    :call (fn [[xs idx item]]
            (or (setitem-call xs idx item)
                (throw (ex-info "setitem index out of bounds or unsupported"
                                {:xs xs :idx idx :item item}))))
    :inverse (fn [output cond-inputs condition]
               (case (vec condition)
                 [0] (let [xs (vec (first cond-inputs))]
                       (when (= (count xs) (count output))
                         (let [diffs (keep-indexed (fn [idx old]
                                                     (when (not= old (nth output idx))
                                                       idx))
                                                   xs)]
                           (when (= 1 (count diffs))
                             (let [idx (first diffs)]
                               [[idx (nth output idx)]])))))
                 [1] (let [idx (first cond-inputs)]
                       (cond
                         (integer? idx)
                         (when (valid-index? output idx)
                           [[(assoc (vec output) idx (missing-sentinel (nth output idx)))
                             (nth output idx)]])

                         (bool-mask? idx)
                         (let [indices (vec (mask-indices idx))]
                           (when (and (= (count idx) (count output))
                                      (seq indices))
                             [[(source-template-after-set output indices)
                               (mapv #(nth output %) indices)]]))

                         (index-vector? idx)
                         (when (seq idx)
                           [[(source-template-after-set output idx)
                             (mapv #(nth output %) idx)]])

                         :else nil))
                 nil))}))

(def registry
  {:add add
   :sub sub
   :mult mult
   :negate negate
   :brange brange
   :repeat repeat
   :concat concat
   :getitem getitem
   :setitem setitem})
