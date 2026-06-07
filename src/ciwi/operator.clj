(ns ciwi.operator
  (:refer-clojure :exclude [concat name repeat])
  (:require [ciwi.dense.core :as dense]
            [ciwi.value :as value]))

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
  (or (dense/ndarray? x)
      (and (sequential? x) (not (string? x)))))

(defn- seq-values
  [x]
  (if (dense/ndarray? x)
    (dense/ravel x)
    (vec x)))

(defn- seq-count
  [x]
  (if (dense/ndarray? x)
    (dense/size x)
    (count x)))

(defn- seq-nth
  [x idx]
  (if (dense/ndarray? x)
    (nth (dense/ravel x) idx)
    (nth x idx)))

(defn- dense-template
  [templates]
  (first (filter dense/ndarray? templates)))

(defn- maybe-array
  [xs & templates]
  (if (dense/array-literal? xs)
    (if-let [template (dense-template templates)]
      (let [flat (vec xs)]
        (if (= (count flat) (dense/size template))
          (dense/with-flat template flat)
          (dense/from-flat flat [(count flat)] {:backend (dense/backend template)})))
      (dense/array xs))
    (vec xs)))

(defn- maybe-call
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

(defn- maybe-integral-quotient
  [output known result]
  (if (and (integral-data? output)
           (integral-data? known))
    (when (integral-result? result)
      (coerce-integral-result result))
    result))

(defn- strict-vec
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

(defn- elementwise1
  [f x]
  (if (seqish? x)
    (let [xs (seq-values x)]
      (maybe-array
       (if (>= (count xs) strict-map-threshold)
         (mapv1-strict f xs)
         (mapv f xs))
       x))
    (f x)))

(defn- elementwise2
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

(defn- all-true?
  [x]
  (if (seqish? x)
    (every? true? (seq-values x))
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
  (or (and (dense/ndarray? x)
           (= :int64 (dense/dtype x))
           (= 1 (dense/ndim x)))
      (and (vector? x) (every? integer? x))))

(defn- bool-mask?
  [x]
  (or (and (dense/ndarray? x)
           (= :bool (dense/dtype x))
           (= 1 (dense/ndim x)))
      (and (vector? x) (every? #(or (true? %) (false? %)) x))))

(defn- seq-literal?
  [x]
  (or (seqish? x) (string? x)))

(defn- dense-concat-compatible?
  [x]
  (or (dense/ndarray? x)
      (and (vector? x) (dense/array-literal? x))))

(defn- valid-index?
  [xs idx]
  (and (integer? idx) (<= 0 idx) (< idx (seq-count xs))))

(defn- valid-indices?
  [xs idxs]
  (every? #(valid-index? xs %) idxs))

(defn- mask-indices
  [mask]
  (keep-indexed (fn [idx selected?]
                  (when selected? idx))
                (seq-values mask)))

(defn- selected-count
  [mask]
  (count (filter true? (seq-values mask))))

(defn- prefix-motif
  [xs motif-len]
  (if (string? xs)
    (subs xs 0 motif-len)
    (maybe-array (subvec (seq-values xs) 0 motif-len) xs)))

(defn- same-seqish?
  [left right]
  (if (and (string? left) (string? right))
    (= left right)
    (= (seq-values left) (seq-values right))))

(defn- repeat-call
  [n motif]
  (when (and (integer? n)
             (not (neg? n))
             (seq-literal? motif))
    (if (string? motif)
      (apply str (clojure.core/repeat n motif))
      (if (dense/ndarray? motif)
        (dense/tile n motif)
        (maybe-array
         (vec (apply clojure.core/concat
                     (clojure.core/repeat n (seq-values motif))))
         motif)))))

(defn repeated-motif
  "Return `[repetitions motif]` for the shortest motif that exactly tiles output."
  [output]
  (when (seq-literal? output)
    (let [n (seq-count output)]
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
  (when (seq-literal? output)
    (let [lgh (seq-count output)]
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
                         (pos? (seq-count motif))
                         (zero? (mod lgh (seq-count motif))))
                (let [reps (/ lgh (seq-count motif))]
                  (when (same-seqish? output (repeat-call reps motif))
                    [[reps]]))))
        ()))))

(defn- cumsum-call
  [xs]
  (when (and (seqish? xs)
             (every? number? (seq-values xs)))
    (dense/cumsum xs)))

(defn- diff-call
  [xs]
  (when (and (seqish? xs)
             (every? number? (seq-values xs)))
    (dense/diff xs)))

(defn- trange-call
  [start stop step]
  (dense/arange start stop step))

(defn- trange-inversions
  [output cond]
  (when (and (empty? cond)
             (seqish? output)
             (seq (seq-values output))
             (every? integer? (seq-values output)))
    (let [values (seq-values output)
          step (if (> (count values) 1)
                 (- (second values) (first values))
                 1)
          stop (+ (peek values) step)]
      (when (and (not (zero? step))
                 (same-seqish? output (trange-call (first values) stop step)))
        [[(first values) stop step]]))))

(defn- mean-call
  [xs]
  (when (and (seqish? xs)
             (seq (seq-values xs))
             (every? number? (seq-values xs)))
    (/ (double (reduce + (seq-values xs)))
       (seq-count xs))))

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
    (and (seqish? content) (= (seq-count content) n)) (seq-values content)
    (and (string? content) (= (count content) n)) (vec content)
    (seqish? content) nil
    (string? content) (vec (clojure.core/repeat n content))
    :else (vec (clojure.core/repeat n content))))

(defn- insert-call
  [indices content rest]
  (when (and (index-vector? indices) (seq-literal? rest))
    (let [indices (unique-indices (seq-values indices))
          content (content-values content (count indices))
          rest-values (if (string? rest) (vec rest) (seq-values rest))
          n (+ (count content) (count rest-values))]
      (when (and content
                 (every? #(< % n) indices))
        (let [by-index (zipmap indices content)
              rest-values (seq rest-values)
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
            (maybe-array result content rest)))))))

(defn- partition-given-indices
  [output indices]
  (when (and (seq-literal? output) (index-vector? indices))
    (let [indices (unique-indices (seq-values indices))
          n (seq-count output)]
      (when (every? #(< % n) indices)
        (let [index-set (set indices)
              output-values (if (string? output) (vec output) (seq-values output))
              content (mapv output-values indices)
              rest (keep-indexed (fn [idx x]
                                   (when-not (index-set idx)
                                     x))
                                 output-values)
              content (if (string? output) (apply str content) content)
              rest (if (string? output)
                     (apply str rest)
                     (maybe-array (vec rest) output))
              content-result (if (string? output)
                               content
                               (maybe-array content output))
              scalar-content (when (and (seq content)
                                        (not (string? content))
                                        (apply = content))
                               (first content))]
          (cond-> [[content-result rest]]
            scalar-content (conj [scalar-content rest])))))))

(defn- common-prefix-len
  [xs ys]
  (count (take-while true? (map = xs ys))))

(defn- common-unused-prefix-len
  [output used content start]
  (let [n (count output)
        m (count content)]
    (loop [idx start
           content-idx 0]
      (if (and (< idx n)
               (< content-idx m)
               (not (contains? used idx))
               (= (nth output idx) (nth content content-idx)))
        (recur (inc idx) (inc content-idx))
        content-idx))))

(defn- best-content-block
  [output used content]
  (let [n (count output)
        content-count (count content)]
    (loop [idx 0
           best {:idx 0 :len 0}]
      (cond
        (= idx n)
        best

        (contains? used idx)
        (recur (inc idx) best)

        :else
        (let [prefix-len (common-unused-prefix-len output used content idx)
              best (if (> prefix-len (:len best))
                     {:idx idx :len prefix-len}
                     best)]
          (if (= prefix-len content-count)
            best
            (recur (inc idx) best)))))))

(defn- partition-given-content
  [output content]
  (when (seq-literal? output)
    (let [output-values (if (string? output) (vec output) (seq-values output))]
      (cond
        (seqish? content)
        (loop [remaining (seq-values content)
               used #{}
               indices []]
          (if (empty? remaining)
            (let [index-set (set indices)
                  rest (keep-indexed (fn [idx x]
                                       (when-not (index-set idx)
                                          x))
                                     output-values)]
              [[(dense/from-flat indices [(count indices)] {:dtype :int64})
                (if (string? output) (apply str rest) (maybe-array (vec rest) output))]])
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
              [[(dense/from-flat (vec indices) [(count indices)] {:dtype :int64})
                (if (string? output) (apply str rest) (maybe-array (vec rest) output))]])))))))

(defn- getitem-call
  [xs idx]
  (let [xs-values (seq-values xs)]
    (cond
      (integer? idx)
      (when (valid-index? xs-values idx)
        (nth xs-values idx))

      (bool-mask? idx)
      (when (= (count xs-values) (seq-count idx))
        (if (dense/ndarray? xs)
          (dense/take-indices xs (vec (mask-indices idx)))
          (maybe-array (mapv #(nth xs-values %) (mask-indices idx)) xs)))

      (index-vector? idx)
      (let [idxs (seq-values idx)]
        (when (valid-indices? xs-values idxs)
          (if (dense/ndarray? xs)
            (dense/take-indices xs idxs)
            (maybe-array (mapv #(nth xs-values %) idxs) xs))))

      :else nil)))

(defn- getitem-unconditioned-inverse
  [output]
  (loop [remaining (seq (seq-values output))
         value->index {}
         values []
         indices []]
    (if-let [remaining (seq remaining)]
      (let [x (first remaining)]
        (if-let [idx (get value->index x)]
          (recur (next remaining)
                 value->index
                 values
                 (conj indices idx))
          (let [idx (count values)]
            (recur (next remaining)
                   (assoc value->index x idx)
                   (conj values x)
                   (conj indices idx)))))
      [[(maybe-array values output)
        (dense/from-flat indices [(count indices)] {:dtype :int64})]])))

(defn- set-many
  [xs indices values]
  (when (and (valid-indices? xs indices)
             (= (count indices) (count values)))
    (reduce (fn [acc [idx value]]
              (assoc acc idx value))
            (seq-values xs)
            (map vector indices values))))

(defn- setitem-call
  [xs idx item]
  (let [xs-values (seq-values xs)]
    (cond
      (integer? idx)
      (when (valid-index? xs-values idx)
        (if (dense/ndarray? xs)
          (dense/put xs [idx] [item])
          (maybe-array (assoc xs-values idx item) xs item)))

      (bool-mask? idx)
      (when (= (count xs-values) (seq-count idx))
        (let [indices (vec (mask-indices idx))]
          (if (dense/ndarray? xs)
            (dense/put xs indices item)
            (maybe-array (set-many xs-values
                                   indices
                                   (seq-values item))
                         xs item))))

      (index-vector? idx)
      (if (dense/ndarray? xs)
        (dense/put xs (seq-values idx) item)
        (maybe-array (set-many xs-values (seq-values idx) (seq-values item))
                     xs item))

      :else nil)))

(defn- array-like-vector?
  [x]
  (or (dense/ndarray? x)
      (and (vector? x)
           (every? #(or (number? %) (boolean? %)) x))))

(defn- missing-sentinel
  [x]
  (cond
    (string? x) ""
    (boolean? x) false
    (number? x) Double/NaN
    :else nil))

(defn- source-template-after-set
  [output indices]
  (when (valid-indices? output indices)
    (let [written? (set indices)]
      (let [values (mapv (fn [idx value]
                           (if (written? idx)
                             (missing-sentinel value)
                             value))
                         (range (seq-count output))
                         (seq-values output))]
        (if (dense/ndarray? output)
          (dense/with-flat output values)
          values)))))

(defn- setitem-source-inversions
  [output xs]
  (when (= (seq-count xs) (seq-count output))
    (let [diffs (vec (keep-indexed (fn [idx old]
                                     (when (not= old (seq-nth output idx))
                                       idx))
                                   (seq-values xs)))]
      (cond
        (and (seq diffs)
             (array-like-vector? xs)
             (array-like-vector? output))
        [[(dense/from-flat diffs [(count diffs)] {:dtype :int64})
          (maybe-array (mapv #(seq-nth output %) diffs) output)]]

        (= 1 (count diffs))
        (let [idx (first diffs)]
          [[idx (seq-nth output idx)]])

        :else nil))))

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
                 (= (seq-count shortcut) (seq-count xs)))
          shortcut
          (maybe-array
           (mapv (fn [x]
                   (value/datum (apply-op f [(value/value x)])))
                 (seq-values xs))
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
  (when (seqish? output)
    (let [output-template output
          output (seq-values output)
          output-count (count output)
          empty-positions (vec (clojure.core/repeat output-count []))
          inversions-by-type
          (loop [idx 0
                 by-type {}]
            (if (= idx output-count)
              by-type
              (let [inversions (mapv (fn [values]
                                       (value/datum (first values)))
                                     (invert-op f (value/value (nth output idx)) [] []))]
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
             (mapv (fn [xs] [(maybe-array (vec xs) output-template)])))))))

(defn- map-inversions
  [output cond-inputs cond]
  (when (= [0] (vec cond))
    (when-let [f (callable-op (first cond-inputs))]
      (or (seq (mapv (fn [values]
                       [(value/datum (first values))])
                     (invert-op f (value/value output) [] [])))
          (elementwise-inversions f output)))))

(defn- count-values
  [values]
  (let [n (count values)]
    (loop [idx 0
           counts {}]
      (if (= idx n)
        counts
        (let [x (nth values idx)]
          (recur (inc idx)
                 (update counts x (fnil inc 0))))))))

(defn- more-common-partition-value?
  [[left-value left-count] [right-value right-count]]
  (or (> left-count right-count)
      (and (= left-count right-count)
           (neg? (compare (pr-str left-value)
                          (pr-str right-value))))))

(defn- most-common-value
  [value-counts]
  (first
   (reduce (fn [best entry]
             (if (more-common-partition-value? entry best)
               entry
               best))
           (first value-counts)
           (rest value-counts))))

(defn- partition-values-around-rest
  [values rest-value]
  (let [n (count values)]
    (loop [idx 0
           indices (transient [])
           content (transient [])
           rest-values (transient [])
           content-scalar? true
           first-content ::none]
      (if (= idx n)
        {:indices (persistent! indices)
         :content (persistent! content)
         :rest (persistent! rest-values)
         :content-scalar? content-scalar?}
        (let [x (nth values idx)]
          (if (= x rest-value)
            (recur (inc idx)
                   indices
                   content
                   (conj! rest-values x)
                   content-scalar?
                   first-content)
            (let [content-scalar? (and content-scalar?
                                       (or (= first-content ::none)
                                           (= first-content x)))
                  first-content (if (= first-content ::none)
                                  x
                                  first-content)]
              (recur (inc idx)
                     (conj! indices idx)
                     (conj! content x)
                     rest-values
                     content-scalar?
                     first-content))))))))

(defn partition-by-frequency
  "Partition output as `[indices content rest]` using the most common value as rest."
  [output]
  (when (or (dense/ndarray? output)
            (vector? output))
    (let [values (seq-values output)
          value-counts (count-values values)]
      (when (seq value-counts)
        (let [rest-value (most-common-value value-counts)
              rest-count (get value-counts rest-value)
              {:keys [indices content rest content-scalar?]}
              (partition-values-around-rest values rest-value)
              content (if (and (seq content) content-scalar?)
                        (first content)
                        content)
              content (if (vector? content)
                        (maybe-array content output)
                        content)
              rest (maybe-array rest output)]
          (when (and (> rest-count 1)
                     (seq indices))
            [[(dense/from-flat (vec indices) [(count indices)] {:dtype :int64})
              content
              rest]]))))))

(def add
  (operator
   {:id :add
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (dense/add x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (when-let [result (maybe-call dense/subtract output known)]
                     [[result]]))))}))

(def sub
  (operator
   {:id :sub
    :conditions [[0] [1]]
    :call (fn [[x y]]
            (dense/subtract x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (when-let [result (case (first cond)
                                       0 (maybe-call dense/subtract known output)
                                       1 (maybe-call dense/add output known)
                                       nil)]
                     [[result]]))))}))

(def mult
  (operator
   {:id :mult
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (dense/multiply x y))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (when-not (or (and (number? known) (zero? known))
                                 (and (seqish? known) (some zero? (seq-values known))))
                     (when-let [result (maybe-call dense/divide output known)]
                       (when-let [result (maybe-integral-quotient output known result)]
                         [[result]]))))))}))

(def negate
  (operator
   {:id :negate
    :conditions [[]]
    :commutative? true
    :call (fn [[x]]
            (dense/negative x))
    :inverse (fn [output _cond-inputs cond]
               (when (empty? cond)
                 [[(dense/negative output)]]))}))

(def lessthan
  (operator
   {:id :lessthan
    :conditions [[0 1]]
    :call (fn [[x y]]
            (dense/less x y))
    :inverse (fn [output cond-inputs condition]
               (when (= [0 1] (vec condition))
                 (let [[x y] cond-inputs]
                   (when (= output (maybe-call dense/less x y))
                     [[]]))))}))

(def equal
  (operator
   {:id :equal
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[x y]]
            (dense/equal x y))
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
            (seq-count x))}))

(def brange
  (operator
   {:id :brange
    :conditions [[]]
    :call (fn [[start n]]
            (dense/arange start n))
    :inverse (fn [output _cond-inputs cond]
               (when (and (empty? cond)
                          (seqish? output)
                          (seq (seq-values output))
                          (every? integer? (seq-values output)))
                 (let [values (seq-values output)]
                   (when (= values (vec (range (first values)
                                               (+ (first values) (count values)))))
                     [[(first values) (inc (last values))]]))))}))

(def trange
  (operator
   {:id :trange
    :conditions [[]]
    :call (fn [[start stop step]]
            (or (trange-call start stop step)
                (throw (ex-info "trange expects a non-zero step"
                                {:start start :stop stop :step step}))))
    :inverse (fn [output _cond-inputs cond]
               (trange-inversions output cond))}))

(def mean
  (operator
   {:id :mean
    :call (fn [[xs]]
            (or (mean-call xs)
                (throw (ex-info "mean expects a non-empty numeric sequence"
                                {:xs xs}))))}))

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
              (if (and (or (dense/ndarray? left) (dense/ndarray? right))
                       (dense-concat-compatible? left)
                       (dense-concat-compatible? right))
                (dense/concatenate [left right])
                (maybe-array (into (strict-vec left)
                                   (if (string? right) (vec right) (seq-values right)))
                             left
                             right))))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (case (first cond)
                     0 (when (and (seq-literal? known)
                                  (seq-literal? output)
                                  (same-seqish? known (prefix-motif output (seq-count known))))
                         [[(if (string? output)
                             (subs output (count known))
                             (maybe-array (subvec (seq-values output) (seq-count known))
                                          output))]])
                     1 (let [split (- (seq-count output) (seq-count known))]
                         (when (and (seq-literal? known)
                                    (seq-literal? output)
                                    (<= 0 split)
                                    (same-seqish? known (if (string? output)
                                                          (subs output split)
                                                          (subvec (seq-values output) split))))
                           [[(if (string? output)
                               (subs output 0 split)
                               (maybe-array (subvec (seq-values output) 0 split)
                                            output))]]))
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
                 [] (when (seqish? output)
                      (getitem-unconditioned-inverse output))
                 [1] (let [idx (first cond-inputs)]
                       (cond
                         (and (bool-mask? idx)
                              (= (selected-count idx) (seq-count output)))
                         [[(maybe-array
                            (reduce (fn [acc [selected-idx value]]
                                      (assoc acc selected-idx value))
                                    (vec (clojure.core/repeat (seq-count idx) nil))
                                    (map vector (mask-indices idx) (seq-values output)))
                            output)]]

                         (and (index-vector? idx)
                              (= (seq-count idx) (seq-count output)))
                         (let [idxs (seq-values idx)
                               n (inc (reduce max -1 idxs))]
                           [[(maybe-array
                              (reduce (fn [acc [selected-idx value]]
                                        (assoc acc selected-idx value))
                                      (vec (clojure.core/repeat n nil))
                                      (map vector idxs (seq-values output)))
                              output)]])

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
                 [0] (setitem-source-inversions output (seq-values (first cond-inputs)))
                 [1] (let [idx (first cond-inputs)]
                       (cond
                         (integer? idx)
                         (when (valid-index? output idx)
                           [[(maybe-array
                              (assoc (seq-values output)
                                     idx
                                     (missing-sentinel (seq-nth output idx)))
                              output)
                             (seq-nth output idx)]])

                         (bool-mask? idx)
                         (let [indices (vec (mask-indices idx))]
                           (when (and (= (seq-count idx) (seq-count output))
                                      (seq indices))
                             [[(source-template-after-set output indices)
                               (maybe-array (mapv #(seq-nth output %) indices)
                                            output)]]))

                         (index-vector? idx)
                         (when (seq (seq-values idx))
                           [[(source-template-after-set output (seq-values idx))
                             (maybe-array (mapv #(seq-nth output %) (seq-values idx))
                                          output)]])

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
   :trange trange
   :mean mean
   :repeat repeat
   :map map-op
   :insert insert
   :cumsum cumsum
   :concat concat
   :getitem getitem
   :setitem setitem})
