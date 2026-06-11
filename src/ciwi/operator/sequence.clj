(ns ciwi.operator.sequence
  (:refer-clojure :exclude [concat repeat])
  (:require [ciwi.dense.core :as dense]
            [ciwi.operator.core :as core]
            [ciwi.operator.util :as u]
            [clojure.set :as set]))

(defn- repeat-call
  [n motif]
  (when (and (integer? n)
             (not (neg? n))
             (u/seq-literal? motif))
    (if (string? motif)
      (apply str (clojure.core/repeat n motif))
      (if (dense/ndarray? motif)
        (dense/tile n motif)
        (u/maybe-array
         (vec (apply clojure.core/concat
                     (clojure.core/repeat n (u/seq-values motif))))
         motif)))))

(defn repeated-motif
  "Return `[repetitions motif]` for the shortest motif that exactly tiles output."
  [output]
  (when (u/seq-literal? output)
    (let [n (u/seq-count output)]
      (cond
        (zero? n) [1 output]
        (= 1 n) [1 output]
        :else
        (first
         (for [motif-len (range 1 (inc n))
               :when (zero? (mod n motif-len))
               :let [reps (/ n motif-len)
                     motif (u/prefix-motif output motif-len)]
               :when (= output (repeat-call reps motif))]
           [reps motif]))))))

(defn- repeat-inversions
  [output cond-inputs cond]
  (when (u/seq-literal? output)
    (let [lgh (u/seq-count output)]
      (case (vec cond)
        [] (when-let [[reps motif] (repeated-motif output)]
             [[reps motif]])
        [0] (let [rep-num (first cond-inputs)]
              (when (and (integer? rep-num)
                         (pos? rep-num)
                         (zero? (mod lgh rep-num)))
                (let [motif (u/prefix-motif output (/ lgh rep-num))]
                  (when (u/same-seqish? output (repeat-call rep-num motif))
                    [[motif]]))))
        [1] (let [motif (first cond-inputs)]
              (when (and (u/seq-literal? motif)
                         (pos? (u/seq-count motif))
                         (zero? (mod lgh (u/seq-count motif))))
                (let [reps (/ lgh (u/seq-count motif))]
                  (when (u/same-seqish? output (repeat-call reps motif))
                    [[reps]]))))
        ()))))

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
    (and (u/seqish? content) (= (u/seq-count content) n)) (u/seq-values content)
    (and (string? content) (= (count content) n)) (vec content)
    (u/seqish? content) nil
    (string? content) (vec (clojure.core/repeat n content))
    :else (vec (clojure.core/repeat n content))))

(defn- insert-call
  [indices content rest]
  (when (and (u/index-vector? indices) (u/seq-literal? rest))
    (let [indices (unique-indices (u/seq-values indices))
          content (content-values content (count indices))
          rest-values (if (string? rest) (vec rest) (u/seq-values rest))
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
            (u/maybe-array result content rest)))))))

(defn- partition-given-indices
  [output indices]
  (when (and (u/seq-literal? output) (u/index-vector? indices))
    (let [indices (unique-indices (u/seq-values indices))
          n (u/seq-count output)]
      (when (every? #(< % n) indices)
        (let [index-set (set indices)
              output-values (if (string? output) (vec output) (u/seq-values output))
              content (mapv output-values indices)
              rest (keep-indexed (fn [idx x]
                                   (when-not (index-set idx)
                                     x))
                                 output-values)
              content (if (string? output) (apply str content) content)
              rest (if (string? output)
                     (apply str rest)
                     (u/maybe-array (vec rest) output))
              content-result (if (string? output)
                               content
                               (u/maybe-array content output))
              scalar-content (when (and (seq content)
                                        (not (string? content))
                                        (apply = content))
                               (first content))]
          (cond-> [[content-result rest]]
            scalar-content (conj [scalar-content rest])))))))

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
  (when (u/seq-literal? output)
    (let [output-values (if (string? output) (vec output) (u/seq-values output))]
      (cond
        (u/seqish? content)
        (loop [remaining (u/seq-values content)
               used #{}
               indices []]
          (if (empty? remaining)
            (let [index-set (set indices)
                  rest (keep-indexed (fn [idx x]
                                       (when-not (index-set idx)
                                         x))
                                     output-values)]
              [[(dense/from-flat indices [(count indices)] {:dtype :int64})
                (if (string? output) (apply str rest) (u/maybe-array (vec rest) output))]])
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
                (if (string? output) (apply str rest) (u/maybe-array (vec rest) output))]])))))))

(defn- axis0-count
  [x]
  (if (dense/ndarray? x)
    (first (dense/shape x))
    (u/seq-count x)))

(defn- valid-axis0-index?
  [x idx]
  (and (integer? idx) (<= 0 idx) (< idx (axis0-count x))))

(defn- valid-axis0-indices?
  [x idxs]
  (every? #(valid-axis0-index? x %) idxs))

(defn- getitem-call
  [xs idx]
  (let [xs-values (u/seq-values xs)]
    (cond
      (integer? idx)
      (when (valid-axis0-index? xs idx)
        (if (and (dense/ndarray? xs)
                 (> (dense/ndim xs) 1))
          (let [row (dense/take-indices xs [idx])
                row-shape (subvec (dense/shape xs) 1)]
            (dense/from-flat (dense/ravel row)
                             row-shape
                             {:backend (dense/backend xs)
                              :dtype (dense/dtype xs)}))
          (nth xs-values idx)))

      (u/bool-mask? idx)
      (when (= (axis0-count xs) (u/seq-count idx))
        (if (dense/ndarray? xs)
          (dense/take-indices xs (vec (u/mask-indices idx)))
          (u/maybe-array (mapv #(nth xs-values %) (u/mask-indices idx)) xs)))

      (u/index-vector? idx)
      (let [idxs (u/seq-values idx)]
        (when (valid-axis0-indices? xs idxs)
          (if (dense/ndarray? xs)
            (dense/take-indices xs idxs)
            (u/maybe-array (mapv #(nth xs-values %) idxs) xs))))

      :else nil)))

(defn- getitem-unconditioned-inverse
  [output]
  (loop [remaining (seq (u/seq-values output))
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
      [[(u/maybe-array values output)
        (dense/from-flat indices [(count indices)] {:dtype :int64})]])))

(defn- set-many
  [xs indices values]
  (when (and (u/valid-indices? xs indices)
             (= (count indices) (count values)))
    (reduce (fn [acc [idx value]]
              (assoc acc idx value))
            (u/seq-values xs)
            (map vector indices values))))

(defn- setitem-call
  [xs idx item]
  (let [xs-values (u/seq-values xs)]
    (cond
      (integer? idx)
      (when (u/valid-index? xs-values idx)
        (if (dense/ndarray? xs)
          (dense/put xs [idx] [item])
          (u/maybe-array (assoc xs-values idx item) xs item)))

      (u/bool-mask? idx)
      (when (= (count xs-values) (u/seq-count idx))
        (let [indices (vec (u/mask-indices idx))]
          (if (dense/ndarray? xs)
            (dense/put xs indices item)
            (u/maybe-array (set-many xs-values
                                     indices
                                     (u/seq-values item))
                           xs item))))

      (u/index-vector? idx)
      (if (dense/ndarray? xs)
        (dense/put xs (u/seq-values idx) item)
        (u/maybe-array (set-many xs-values (u/seq-values idx) (u/seq-values item))
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
  (when (u/valid-indices? output indices)
    (let [written? (set indices)]
      (let [values (mapv (fn [idx value]
                           (if (written? idx)
                             (missing-sentinel value)
                             value))
                         (range (u/seq-count output))
                         (u/seq-values output))]
        (if (dense/ndarray? output)
          (dense/from-flat values
                           (dense/shape output)
                           {:backend (dense/backend output)})
          values)))))

(defn- setitem-source-inversions
  [output xs]
  (when (= (u/seq-count xs) (u/seq-count output))
    (let [diffs (vec (keep-indexed (fn [idx old]
                                     (when (not= old (u/seq-nth output idx))
                                       idx))
                                   (u/seq-values xs)))]
      (cond
        (and (seq diffs)
             (array-like-vector? xs)
             (array-like-vector? output))
        [[(dense/from-flat diffs [(count diffs)] {:dtype :int64})
          (u/maybe-array (mapv #(u/seq-nth output %) diffs) output)]]

        (= 1 (count diffs))
        (let [idx (first diffs)]
          [[idx (u/seq-nth output idx)]])

        :else nil))))

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
  (or (when (dense/ndarray? output)
        (dense/partition-by-frequency output))
      (when (or (dense/ndarray? output)
                (vector? output))
        (let [values (u/seq-values output)
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
                            (u/maybe-array content output)
                            content)
                  rest (u/maybe-array rest output)]
              (when (and (> rest-count 1)
                         (seq indices))
                [[(dense/from-flat (vec indices) [(count indices)] {:dtype :int64})
                  content
                  rest]])))))))

(def repeat
  (core/operator
   {:id :repeat
    :conditions [[] [0] [1]]
    :call (fn [[n motif]]
            (or (repeat-call n motif)
                (throw (ex-info "repeat expects a non-negative integer and a vector/string motif"
                                {:n n :motif motif}))))
    :inverse (fn [output cond-inputs cond]
               (repeat-inversions output cond-inputs cond))}))

(def insert
  (core/operator
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
  (core/operator
   {:id :concat
    :conditions [[0] [1]]
    :call (fn [[left right]]
            (if (and (string? left) (string? right))
              (str left right)
              (if (and (or (dense/ndarray? left) (dense/ndarray? right))
                       (u/dense-concat-compatible? left)
                       (u/dense-concat-compatible? right))
                (dense/concatenate [left right])
                (u/maybe-array (into (u/strict-vec left)
                                     (if (string? right)
                                       (vec right)
                                       (u/seq-values right)))
                               left
                               right))))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (case (first cond)
                     0 (when (and (u/seq-literal? known)
                                  (u/seq-literal? output)
                                  (u/same-seqish? known
                                                   (u/prefix-motif output (u/seq-count known))))
                         [[(if (string? output)
                             (subs output (count known))
                             (u/maybe-array (subvec (u/seq-values output)
                                                    (u/seq-count known))
                                            output))]])
                     1 (let [split (- (u/seq-count output) (u/seq-count known))]
                         (when (and (u/seq-literal? known)
                                    (u/seq-literal? output)
                                    (<= 0 split)
                                    (u/same-seqish? known
                                                     (if (string? output)
                                                       (subs output split)
                                                       (subvec (u/seq-values output) split))))
                           [[(if (string? output)
                               (subs output 0 split)
                               (u/maybe-array (subvec (u/seq-values output) 0 split)
                                              output))]]))
                     ()))))}))

(defn- row-values
  [x row]
  (let [shape (dense/shape x)
        row-size (reduce * 1 (subvec shape 1))
        start (* row row-size)]
    (subvec (dense/ravel x) start (+ start row-size))))

(defn- same-row?
  [left right]
  (and (= (count left) (count right))
       (every? (fn [[x y]]
                 (if (and (dense/nan? x) (dense/nan? y))
                   true
                   (= x y)))
               (map vector left right))))

(defn- remove-known-rows
  [output known]
  (when (and (dense/ndarray? output)
             (dense/ndarray? known)
             (>= (dense/ndim output) 2)
             (= (dense/ndim output) (dense/ndim known))
             (= (subvec (dense/shape output) 1)
                (subvec (dense/shape known) 1))
             (<= (first (dense/shape known))
                 (first (dense/shape output))))
    (let [known-rows (mapv #(row-values known %) (range (first (dense/shape known))))]
      (loop [row 0
             known-rows known-rows
             remaining-rows []]
        (if (= row (first (dense/shape output)))
          (when (empty? known-rows)
            (dense/from-flat (mapcat identity remaining-rows)
                             (into [(count remaining-rows)]
                                   (subvec (dense/shape output) 1))
                             {:backend (dense/backend output)
                              :dtype (dense/dtype output)}))
          (let [output-row (row-values output row)
                match-index (first (keep-indexed (fn [idx known-row]
                                                   (when (same-row? output-row known-row)
                                                     idx))
                                                 known-rows))]
            (if match-index
              (recur (inc row)
                     (vec (clojure.core/concat
                           (subvec known-rows 0 match-index)
                           (subvec known-rows (inc match-index))))
                     remaining-rows)
              (recur (inc row)
                     known-rows
                     (conj remaining-rows output-row)))))))))

(def union
  (core/operator
   {:id :union
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [xs]
            (cond
              (every? #(and (dense/ndarray? %)
                            (>= (dense/ndim %) 2))
                      xs)
              (dense/concatenate-axis0 xs)

              (every? set? xs)
              (apply set/union xs)

              :else
              (apply set/union (map set xs))))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (clojure.core/cond
                     (dense/ndarray? output)
                     (when-let [remaining (remove-known-rows output known)]
                       [[remaining]])

                     (and (set? output) (coll? known))
                     (when (every? #(contains? output %) known)
                       [[(set/difference output (set known))]])

                     :else nil))))}))

(def getitem
  (core/operator
   {:id :getitem
    :conditions [[] [1]]
    :call (fn [[xs idx]]
            (or (getitem-call xs idx)
                (throw (ex-info "getitem index out of bounds or unsupported"
                                {:xs xs :idx idx}))))
    :inverse (fn [output cond-inputs condition]
               (case (vec condition)
                 [] (when (u/seqish? output)
                      (getitem-unconditioned-inverse output))
                 [1] (let [idx (first cond-inputs)]
                       (cond
                         (and (u/bool-mask? idx)
                              (= (u/selected-count idx) (u/seq-count output)))
                         [[(u/maybe-array
                            (reduce (fn [acc [selected-idx value]]
                                      (assoc acc selected-idx value))
                                    (vec (clojure.core/repeat (u/seq-count idx) nil))
                                    (map vector
                                         (u/mask-indices idx)
                                         (u/seq-values output)))
                            output)]]

                         (and (u/index-vector? idx)
                              (= (u/seq-count idx) (u/seq-count output)))
                         (let [idxs (u/seq-values idx)
                               n (inc (reduce max -1 idxs))]
                           [[(u/maybe-array
                              (reduce (fn [acc [selected-idx value]]
                                        (assoc acc selected-idx value))
                                      (vec (clojure.core/repeat n nil))
                                      (map vector idxs (u/seq-values output)))
                              output)]])

                         :else nil))
                 nil))}))

(def setitem
  (core/operator
   {:id :setitem
    :conditions [[0] [1]]
    :call (fn [[xs idx item]]
            (or (setitem-call xs idx item)
                (throw (ex-info "setitem index out of bounds or unsupported"
                                {:xs xs :idx idx :item item}))))
    :inverse (fn [output cond-inputs condition]
               (case (vec condition)
                 [0] (setitem-source-inversions output (u/seq-values (first cond-inputs)))
                 [1] (let [idx (first cond-inputs)]
                       (cond
                         (integer? idx)
                         (when (u/valid-index? output idx)
                           [[(u/maybe-array
                              (assoc (u/seq-values output)
                                     idx
                                     (missing-sentinel (u/seq-nth output idx)))
                              output)
                             (u/seq-nth output idx)]])

                         (u/bool-mask? idx)
                         (let [indices (vec (u/mask-indices idx))]
                           (when (and (= (u/seq-count idx) (u/seq-count output))
                                      (seq indices))
                             [[(source-template-after-set output indices)
                               (u/maybe-array (mapv #(u/seq-nth output %) indices)
                                              output)]]))

                         (u/index-vector? idx)
                         (when (seq (u/seq-values idx))
                           [[(source-template-after-set output (u/seq-values idx))
                             (u/maybe-array (mapv #(u/seq-nth output %) (u/seq-values idx))
                                            output)]])

                         :else nil))
                 nil))}))
