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

(defn- elementwise1
  [f x]
  (if (seqish? x)
    (mapv f x)
    (f x)))

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

(defn- all-true?
  [x]
  (if (seqish? x)
    (every? true? x)
    (true? x)))

(defn- boolean-scalar?
  [x]
  (or (true? x) (false? x)))

(defn- logical-and-call
  [x y]
  (elementwise2 #(boolean (clojure.core/and %1 %2)) x y))

(defn- logical-or-call
  [x y]
  (elementwise2 #(boolean (clojure.core/or %1 %2)) x y))

(defn- logical-scalar-inverses
  [f output known]
  (when (and (boolean-scalar? output)
             (boolean-scalar? known))
    (for [candidate [true false]
          :when (= output (f known candidate))]
      [candidate])))

(defn- index-vector?
  [x]
  (and (vector? x) (every? integer? x)))

(defn- bool-mask?
  [x]
  (and (vector? x) (every? #(or (true? %) (false? %)) x)))

(defn- seq-literal?
  [x]
  (or (vector? x) (string? x)))

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

(defn- prefix-motif
  [xs motif-len]
  (if (string? xs)
    (subs xs 0 motif-len)
    (subvec (vec xs) 0 motif-len)))

(defn- same-seqish?
  [left right]
  (if (and (string? left) (string? right))
    (= left right)
    (= (vec left) (vec right))))

(defn- repeat-call
  [n motif]
  (when (and (integer? n)
             (not (neg? n))
             (seq-literal? motif))
    (if (string? motif)
      (apply str (clojure.core/repeat n motif))
      (vec (apply clojure.core/concat (clojure.core/repeat n (vec motif)))))))

(defn repeated-motif
  "Return `[repetitions motif]` for the shortest motif that exactly tiles output."
  [output]
  (when (seq-literal? output)
    (let [n (count output)]
      (cond
        (zero? n) [1 output]
        (= 1 n) [1 output]
        :else
        (first
         (for [motif-len (range 1 (inc n))
               :when (zero? (mod n motif-len))
               :let [reps (/ n motif-len)
                     motif (prefix-motif output motif-len)]
               :when (= output (repeat-call reps motif))]
           [reps motif]))))))

(defn- repeat-inversions
  [output cond-inputs cond]
  (let [lgh (count output)]
    (case (vec cond)
      [] (when-let [[reps motif] (repeated-motif output)]
           [[reps motif]])
      [0] (let [rep-num (first cond-inputs)]
            (when (and (integer? rep-num)
                       (pos? rep-num)
                       (zero? (mod lgh rep-num)))
              (let [motif (prefix-motif output (/ lgh rep-num))]
                (when (same-seqish? output (repeat-call rep-num motif))
                  [[motif]]))))
      [1] (let [motif (first cond-inputs)]
            (when (and (seq-literal? motif)
                       (pos? (count motif))
                       (zero? (mod lgh (count motif))))
              (let [reps (/ lgh (count motif))]
                (when (same-seqish? output (repeat-call reps motif))
                  [[reps]]))))
      ())))

(defn- cumsum-call
  [xs]
  (when (and (vector? xs) (every? number? xs))
    (vec (rest (reductions + 0 xs)))))

(defn- diff-call
  [xs]
  (when (and (vector? xs) (every? number? xs))
    (vec (map - xs (cons 0 xs)))))

(defn- unique-indices
  [indices]
  (loop [remaining indices
         seen #{}
         result []]
    (if-let [idx (first remaining)]
      (do
        (when-not (integer? idx)
          (throw (ex-info "insert indices must be integers" {:indices indices})))
        (when (neg? idx)
          (throw (ex-info "negative insert indices are not supported" {:indices indices})))
        (if (seen idx)
          (recur (rest remaining) seen result)
          (recur (rest remaining) (conj seen idx) (conj result idx))))
      result)))

(defn- content-values
  [content n]
  (cond
    (and (vector? content) (= (count content) n)) content
    (and (string? content) (= (count content) n)) (vec content)
    (vector? content) nil
    (string? content) (vec (clojure.core/repeat n content))
    :else (vec (clojure.core/repeat n content))))

(defn- insert-call
  [indices content rest]
  (when (and (vector? indices) (seq-literal? rest))
    (let [indices (unique-indices indices)
          content (content-values content (count indices))
          n (+ (count content) (count rest))]
      (when (and content
                 (every? #(< % n) indices))
        (let [by-index (zipmap indices content)
              rest-values (seq (if (string? rest) (vec rest) (vec rest)))
              result (loop [idx 0
                            rest-values rest-values
                            result []]
                       (if (= idx n)
                         result
                         (if (contains? by-index idx)
                           (recur (inc idx) rest-values (conj result (get by-index idx)))
                           (recur (inc idx) (next rest-values) (conj result (first rest-values))))))]
          (if (string? rest)
            (apply str result)
            result))))))

(defn- partition-given-indices
  [output indices]
  (when (and (seq-literal? output) (vector? indices))
    (let [indices (unique-indices indices)
          n (count output)]
      (when (every? #(< % n) indices)
        (let [index-set (set indices)
              output-values (if (string? output) (vec output) (vec output))
              content (mapv output-values indices)
              rest (keep-indexed (fn [idx x]
                                   (when-not (index-set idx)
                                     x))
                                 output-values)
              content (if (string? output) (apply str content) content)
              rest (if (string? output) (apply str rest) (vec rest))
              scalar-content (when (and (seq content)
                                        (not (string? content))
                                        (apply = content))
                               (first content))]
          (cond-> [[content rest]]
            scalar-content (conj [scalar-content rest])))))))

(defn- common-prefix-len
  [xs ys]
  (count (take-while true? (map = xs ys))))

(defn- best-content-block
  [output used content]
  (let [n (count output)]
    (reduce (fn [best idx]
              (let [available (->> (range idx n)
                                   (take-while #(not (used %)))
                                   (mapv output))
                    prefix-len (common-prefix-len available content)]
                (if (> prefix-len (:len best))
                  {:idx idx :len prefix-len}
                  best)))
            {:idx 0 :len 0}
            (range n))))

(defn- partition-given-content
  [output content]
  (when (seq-literal? output)
    (let [output-values (if (string? output) (vec output) (vec output))]
      (cond
        (vector? content)
        (loop [remaining content
               used #{}
               indices []]
          (if (empty? remaining)
            (let [index-set (set indices)
                  rest (keep-indexed (fn [idx x]
                                       (when-not (index-set idx)
                                         x))
                                     output-values)]
              [[indices (if (string? output) (apply str rest) (vec rest))]])
            (let [{:keys [idx len]} (best-content-block output-values used remaining)]
              (when (pos? len)
                (recur (subvec remaining len)
                       (into used (range idx (+ idx len)))
                       (into indices (range idx (+ idx len))))))))

        :else
        (let [indices (keep-indexed (fn [idx x]
                                      (when (= x content) idx))
                                    output-values)]
          (when (seq indices)
            (let [index-set (set indices)
                  rest (keep-indexed (fn [idx x]
                                       (when-not (index-set idx)
                                         x))
                                     output-values)]
              [[(vec indices) (if (string? output) (apply str rest) (vec rest))]])))))))

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

(declare registry)

(defn- callable-op
  [f]
  (cond
    (operator? f) f
    (keyword? f) (get registry f)
    :else nil))

(defn- map-call
  [f xs]
  (when-let [f (callable-op f)]
    (when (seqish? xs)
      (let [shortcut (try
                       (value/datum (apply-op f [(value/value xs)]))
                       (catch Throwable _ ::failed))]
        (if (and (not= ::failed shortcut)
                 (seqish? shortcut)
                 (= (count shortcut) (count xs)))
          (vec shortcut)
          (mapv (fn [x]
                  (value/datum (apply-op f [(value/value x)])))
                xs))))))

(defn- first-elementwise-inversions
  [f output]
  (when (seqish? output)
    (loop [remaining (vec output)
           result []]
      (if (seq remaining)
        (let [x (first remaining)
              inversions (invert-op f (value/value x) [] [])]
          (when-let [input (-> inversions first first value/datum)]
            (recur (rest remaining) (conj result input))))
        [[result]]))))

(defn- map-inversions
  [output cond-inputs cond]
  (when (= [0] (vec cond))
    (when-let [f (callable-op (first cond-inputs))]
      (or (seq (mapv (fn [values]
                       [(value/datum (first values))])
                     (invert-op f (value/value output) [] [])))
          (first-elementwise-inversions f output)))))

(defn partition-by-frequency
  "Partition output as `[indices content rest]` using the most common value as rest."
  [output]
  (when (seq-literal? output)
    (let [values (if (string? output) (vec output) (vec output))
          value-counts (frequencies values)]
      (when (seq value-counts)
        (let [rest-value (->> value-counts
                              (sort-by (fn [[x n]]
                                         [(- n) (pr-str x)]))
                              ffirst)
              selected? #(not= % rest-value)
              indices (keep-indexed (fn [idx x]
                                      (when (selected? x) idx))
                                    values)
              content (filterv selected? values)
              rest (filterv (complement selected?) values)
              content (if (and (seq content) (apply = content))
                        (first content)
                        content)
              rest (if (string? output) (apply str rest) rest)]
          (when (seq indices)
            [[(vec indices) content rest]]))))))

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

(def lessthan
  (operator
   {:id :lessthan
    :conditions [[0 1]]
    :call (fn [[x y]]
            (elementwise2 < x y))
    :inverse (fn [output cond-inputs condition]
               (when (= [0 1] (vec condition))
                 (let [[x y] cond-inputs]
                   (when (= output (elementwise2 < x y))
                     [[]]))))}))

(def equal
  (operator
   {:id :equal
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (elementwise2 = x y))
    :inverse (fn [output cond-inputs condition]
               (when (and (= 1 (count condition))
                          (all-true? output))
                 [[(first cond-inputs)]]))}))

(def logical-not
  (operator
   {:id :not
    :conditions [[]]
    :call (fn [[x]]
            (elementwise1 clojure.core/not x))
    :inverse (fn [output _cond-inputs condition]
               (when (empty? condition)
                 [[(elementwise1 clojure.core/not output)]]))}))

(def logical-and
  (operator
   {:id :and
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (logical-and-call x y))
    :inverse (fn [output cond-inputs condition]
               (when (= 1 (count condition))
                 (logical-scalar-inverses logical-and-call output (first cond-inputs))))}))

(def logical-or
  (operator
   {:id :or
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (logical-or-call x y))
    :inverse (fn [output cond-inputs condition]
               (when (= 1 (count condition))
                 (logical-scalar-inverses logical-or-call output (first cond-inputs))))}))

(def len
  (operator
   {:id :len
    :conditions [[0]]
    :call (fn [[x]]
            (count x))}))

(def brange
  (operator
   {:id :brange
    :conditions [[]]
    :call (fn [[start n]]
            (vec (range start n)))
    :inverse (fn [output _cond-inputs cond]
               (when (and (empty? cond)
                          (vector? output)
                          (seq output)
                          (every? integer? output)
                          (= output (vec (range (first output)
                                                (+ (first output) (count output))))))
                 [[(first output) (inc (last output))]]))}))

(def repeat
  (operator
   {:id :repeat
    :conditions [[] [0] [1]]
    :call (fn [[n motif]]
            (or (repeat-call n motif)
                (throw (ex-info "repeat expects a non-negative integer and a vector/string motif"
                                {:n n :motif motif}))))
    :inverse (fn [output _cond-inputs cond]
               (repeat-inversions output _cond-inputs cond))}))

(def map-op
  (operator
   {:id :map
    :conditions [[0]]
    :call (fn [[f xs]]
            (or (map-call f xs)
                (throw (ex-info "map expects an operator keyword/record and a sequence"
                                {:f f :xs xs}))))
    :inverse (fn [output cond-inputs cond]
               (map-inversions output cond-inputs cond))}))

(def cumsum
  (operator
   {:id :cumsum
    :conditions [[]]
    :call (fn [[xs]]
            (or (cumsum-call xs)
                (throw (ex-info "cumsum expects a numeric vector" {:xs xs}))))
    :inverse (fn [output _cond-inputs cond]
               (when (empty? cond)
                 (when-let [diffs (diff-call output)]
                   [[diffs]])))}))

(def insert
  (operator
   {:id :insert
    :conditions [[] [0] [1]]
    :call (fn [[indices content rest]]
            (or (insert-call indices content rest)
                (throw (ex-info "insert expects indices, content, and rest"
                                {:indices indices
                                 :content content
                                 :rest rest}))))
    :inverse (fn [output cond-inputs cond]
               (case (vec cond)
                 [] (partition-by-frequency output)
                 [0] (partition-given-indices output (first cond-inputs))
                 [1] (partition-given-content output (first cond-inputs))
                 nil))}))

(def concat
  (operator
   {:id :concat
    :conditions [[0] [1]]
    :call (fn [[left right]]
            (if (and (string? left) (string? right))
              (str left right)
              (vec (clojure.core/concat left right))))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (case (first cond)
                     0 (when (and (seq-literal? known)
                                  (seq-literal? output)
                                  (same-seqish? known (prefix-motif output (count known))))
                         [[(if (string? output)
                             (subs output (count known))
                             (subvec (vec output) (count known)))]])
                     1 (let [split (- (count output) (count known))]
                         (when (and (seq-literal? known)
                                    (seq-literal? output)
                                    (<= 0 split)
                                    (same-seqish? known (if (string? output)
                                                          (subs output split)
                                                          (subvec (vec output) split))))
                           [[(if (string? output)
                               (subs output 0 split)
                               (subvec (vec output) 0 split))]]))
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
   :lessthan lessthan
   :equal equal
   :not logical-not
   :and logical-and
   :or logical-or
   :len len
   :brange brange
   :repeat repeat
   :map map-op
   :insert insert
   :cumsum cumsum
   :concat concat
   :getitem getitem
   :setitem setitem})
