(ns ciwi.value
  (:require [ciwi.cache :as cache]
            [ciwi.dense.core :as dense]
            [ciwi.hashing :as hashing])
  (:import [java.lang.ref WeakReference]))

(defrecord Value [data name spec permeable? dummy?])

(deftype IdentityValueCacheKey [v opts dummy?]
  Object
  (equals [_ other]
    (and (instance? IdentityValueCacheKey other)
         (identical? v (.-v ^IdentityValueCacheKey other))
         (= opts (.-opts ^IdentityValueCacheKey other))
         (= dummy? (.-dummy? ^IdentityValueCacheKey other))))
  (hashCode [_]
    (hash [::identity-value
           (System/identityHashCode v)
           opts
           dummy?])))

(deftype WeakIdentityValueCacheKey [ref identity-hash opts dummy?]
  Object
  (equals [_ other]
    (and (instance? WeakIdentityValueCacheKey other)
         (= identity-hash (.-identity-hash ^WeakIdentityValueCacheKey other))
         (= opts (.-opts ^WeakIdentityValueCacheKey other))
         (= dummy? (.-dummy? ^WeakIdentityValueCacheKey other))
         (let [v (.get ^WeakReference ref)
               other-v (.get ^WeakReference
                             (.-ref ^WeakIdentityValueCacheKey other))]
           (and v other-v (identical? v other-v)))))
  (hashCode [_]
    (hash [::weak-identity-value
           identity-hash
           opts
           dummy?])))

(defn- weak-identity-value-cache-key
  [v opts dummy?]
  (WeakIdentityValueCacheKey. (WeakReference. v)
                              (System/identityHashCode v)
                              opts
                              dummy?))

(def max-precision 10)
(def intnan64 Long/MIN_VALUE)
(def max-unique 10)
(def precision-epsilon 1.0e-10)

(def ^:private long-array-class (class (long-array 0)))
(def ^:private double-array-class (class (double-array 0)))

(defn- primitive-long-array?
  [x]
  (instance? long-array-class x))

(defn- primitive-double-array?
  [x]
  (instance? double-array-class x))

(defn log2
  [x]
  (/ (Math/log (double x)) (Math/log 2.0)))

(defn elias-discrete
  "Exact Elias delta code length for positive integers.

  This mirrors Python WILLIAM's `elias_discrete` helper and is still used by
  the enumerators for index ordering. Value description lengths use `jelias`,
  Python's continuous approximation.
  "
  [n]
  (let [n (max 1 (long n))
        bit-len (inc (long (Math/floor (log2 n))))
        len-len (inc (long (Math/floor (log2 bit-len))))]
    (+ bit-len (* 2 len-len) -1)))

(defn jelias
  "Continuous Elias length used by Python WILLIAM's description codec."
  [x]
  (let [n (log2 x)]
    (+ n (* 2.0 (log2 (inc n))) 1.0)))

(defn jelias-posneg
  "Continuous Elias length for signed integers, including zero."
  [x]
  (jelias (inc (* 2.0 (Math/abs (double x))))))

(defn value?
  [x]
  (instance? Value x))

(defn- coerce-data
  [data]
  (if (dense/array-literal? data)
    (dense/array data)
    data))

(defn value
  ([data]
   (value data {}))
  ([data {:keys [name spec permeable? dummy?]
          :or {permeable? true
               dummy? false}}]
   (if (value? data)
     data
     (->Value (coerce-data data) name spec permeable? dummy?))))

(defn datum
  [x]
  (if (value? x)
    (:data x)
    x))

(defn plain-datum
  [x]
  (let [x (datum x)]
    (if (dense/ndarray? x)
      (dense/tolist x)
      x)))

(declare desc-len-data)

(defn- pow10
  [n]
  (Math/pow 10.0 (double n)))

(defn- nan-number?
  [x]
  (and (number? x)
       (Double/isNaN (double x))))

(defn- intnan?
  [x]
  (and (integer? x)
       (= intnan64 (long x))))

(defn- missing-number?
  [x]
  (or (nan-number? x)
      (intnan? x)))

(defn- python-rint
  [x]
  (Math/rint (double x)))

(defn- python-round
  [x decimals]
  (if (neg? decimals)
    (let [scale (pow10 (- decimals))]
      (* (python-rint (/ (double x) scale)) scale))
    (let [scale (pow10 decimals)]
      (/ (python-rint (* (double x) scale)) scale))))

(defn round-to-precision
  "Round a numeric scalar with Python WILLIAM's `np.round`-style semantics."
  [x decimals]
  (if (missing-number? x)
    x
    (python-round x decimals)))

(defn precision-scalar
  "Port of Python WILLIAM's `precision_scalar`.

  Precision is the decimal exponent used before Elias-significand coding.
  Negative precisions describe trailing powers of ten.
  "
  [x]
  (cond
    (missing-number? x) (- max-precision)
    (< (Math/abs (double x)) precision-epsilon) 0
    :else
    (loop [k 0
           prec 0
           positive? true]
      (if (>= k 50)
        prec
        (let [rounded-negative (python-round x (- k))]
          (cond
            (and (<= prec 0)
                 (< (Math/abs (- (double x) rounded-negative))
                    precision-epsilon))
            (recur (inc k) (- k) false)

            (not positive?)
            prec

            (< (Math/abs (- (double x) (python-round x k)))
               precision-epsilon)
            k

            :else
            (recur (inc k) prec positive?)))))))

(defn precision-array
  [xs]
  (cond
    (primitive-double-array? xs)
    (let [^doubles xs xs
          n (alength xs)]
      (if (pos? n)
        (loop [idx 0
               best (- max-precision)]
          (if (= idx n)
            best
            (recur (inc idx)
                   (max best (precision-scalar (aget xs idx))))))
        (- max-precision)))

    :else
    (let [n (count xs)]
      (if (pos? n)
        (loop [idx 0
               best (- max-precision)]
          (if (= idx n)
            best
            (recur (inc idx)
                   (max best (precision-scalar (nth xs idx))))))
        (- max-precision)))))

(defn- precision-int-scalar
  [x]
  (cond
    (intnan? x) (- max-precision)
    (zero? (long x)) 0
    :else
    (loop [n (Math/abs (long x))
           precision 0]
      (if (and (< precision 50)
               (zero? (rem n 10)))
        (recur (quot n 10) (inc precision))
        (- precision)))))

(defn- precision-int-array
  [xs]
  (cond
    (primitive-long-array? xs)
    (let [^longs xs xs
          n (alength xs)]
      (if (pos? n)
        (loop [idx 0
               best (- max-precision)]
          (if (= idx n)
            best
            (let [precision (precision-int-scalar (aget xs idx))
                  best (max best precision)]
              (if (zero? best)
                0
                (recur (inc idx) best)))))
        (- max-precision)))

    :else
    (let [n (count xs)]
      (if (pos? n)
        (loop [idx 0
               best (- max-precision)]
          (if (= idx n)
            best
            (let [precision (precision-int-scalar (nth xs idx))
                  best (max best precision)]
              (if (zero? best)
                0
                (recur (inc idx) best)))))
        (- max-precision)))))

(defn desc-len-int
  ([x]
   (desc-len-int x (precision-scalar x)))
  ([x decimals]
   (if (intnan? x)
     0.0
     (let [significand (python-rint (* (double x) (pow10 decimals)))]
       (+ (jelias-posneg significand)
          (jelias (- 1.0 decimals)))))))

(defn desc-len-float
  ([x]
   (desc-len-float x (precision-scalar x)))
  ([x decimals]
   (if (nan-number? x)
     0.0
     (let [significand (python-rint (* (double x) (pow10 decimals)))]
       (+ (jelias-posneg significand)
          (jelias-posneg decimals))))))

(defn- scalar-desc-len
  [x opts]
  (cond
    (nil? x) 1.0
    (or (true? x) (false? x)) 1.0
    (integer? x) (double (desc-len-int x))
    (float? x) (double (desc-len-float x))
    (number? x) (double (desc-len-float x))
    (string? x) (double (+ (if (pos? (count x))
                             (jelias (count x))
                             0.0)
                           (* 8.0 (count x))))
    (keyword? x) (desc-len-data (name x) opts)
    :else nil))

(defn- operator-like?
  [x]
  (and (map? x)
       (contains? x :dl)
       (contains? x :conditions)
       (contains? x :call)
       (contains? x :inverse)))

(defn- rectangular-shape
  [x]
  (if (vector? x)
    (if (empty? x)
      [0]
      (let [child-shapes (mapv rectangular-shape x)]
        (when (and (every? some? child-shapes)
                   (apply = child-shapes))
          (into [(count x)] (first child-shapes)))))
    []))

(defn- scalar-vector-kind
  [xs]
  (loop [remaining (seq xs)
         kind :empty]
    (if-let [remaining (seq remaining)]
      (let [x (first remaining)]
        (cond
          (or (true? x) (false? x))
          (if (#{:empty :bool} kind)
            (recur (next remaining) :bool)
            nil)

          (integer? x)
          (case kind
            :empty (recur (next remaining) :int)
            :int (recur (next remaining) :int)
            :float (recur (next remaining) :float)
            nil)

          (number? x)
          (if (#{:empty :int :float} kind)
            (recur (next remaining) :float)
            nil)

          (string? x)
          (if (#{:empty :string} kind)
            (recur (next remaining) :string)
            nil)

          :else nil))
      kind)))

(defn- flatten-array
  [x]
  (cond
    (dense/ndarray? x) (dense/ravel x)
    (vector? x)
    (mapcat flatten-array x)
    :else [x]))

(defn- product
  [xs]
  (reduce * 1 xs))

(defn- homogeneous-kind
  [xs]
  (cond
    (empty? xs) :empty
    (every? #(or (true? %) (false? %)) xs) :bool
    (every? integer? xs) :int
    (every? number? xs) :float
    (every? string? xs) :string
    :else nil))

(defn- python-array-info
  [x]
  (cond
    (dense/ndarray? x)
    (dense/array-info x)

    (vector? x)
    (if-let [kind (scalar-vector-kind x)]
      {:shape [(count x)]
       :flat x
       :kind kind
       :size (count x)
       :data x}
      (when-let [shape (rectangular-shape x)]
        (let [flat (vec (flatten-array x))
              kind (homogeneous-kind flat)]
          (when kind
            {:shape shape
             :flat flat
             :kind kind
             :size (product shape)
             :data x}))))

    :else nil))

(defn- array-elias
  [xs decimals]
  (let [scale (pow10 decimals)]
    (if (primitive-double-array? xs)
      (let [^doubles xs xs
            n (alength xs)]
        (loop [idx 0
               sig-dl 0.0
               all-nan? true]
          (if (= idx n)
            [sig-dl all-nan?]
            (let [s (aget xs idx)]
              (if (Double/isNaN s)
                (recur (inc idx) sig-dl all-nan?)
                (recur (inc idx)
                       (+ sig-dl
                          (jelias-posneg (python-rint (* s scale))))
                       false))))))
      (loop [idx 0
             n (count xs)
             sig-dl 0.0
             all-nan? true]
        (if (= idx n)
          [sig-dl all-nan?]
          (let [s (nth xs idx)]
            (if (missing-number? s)
              (recur (inc idx) n sig-dl all-nan?)
              (recur (inc idx)
                     n
                     (+ sig-dl
                        (jelias-posneg (python-rint (* (double s) scale))))
                     false))))))))

(defn- array-elias-int
  [xs decimals]
  (let [divisor (when (neg? decimals)
                  (long (pow10 (- decimals))))
        long-loop
        (fn [^longs xs]
          (let [n (alength xs)]
            (loop [idx 0
                   sig-dl 0.0
                   all-nan? true]
              (if (= idx n)
                [sig-dl all-nan?]
                (let [x (aget xs idx)]
                  (if (= intnan64 x)
                    (recur (inc idx) sig-dl all-nan?)
                    (let [significand (if divisor
                                        (quot x divisor)
                                        x)]
                      (recur (inc idx)
                             (+ sig-dl (jelias-posneg significand))
                             false))))))))]
    (if (primitive-long-array? xs)
      (long-loop xs)
      (let [n (count xs)]
        (loop [idx 0
               sig-dl 0.0
               all-nan? true]
          (if (= idx n)
            [sig-dl all-nan?]
            (let [x (nth xs idx)]
              (if (intnan? x)
                (recur (inc idx) sig-dl all-nan?)
                (let [significand (if divisor
                                    (quot (long x) divisor)
                                    (long x))]
                  (recur (inc idx)
                         (+ sig-dl (jelias-posneg significand))
                         false))))))))))

(defn desc-len-array-elias-signal
  "Return `[dl all-nan?]` for Python WILLIAM's `_jdesc_len_array_elias` score.

  This intentionally excludes array shape and precision code lengths. It is
  useful for optimizer objectives that score a fixed residual array, where the
  Python reference calls the private helper directly.
  "
  [x decimals]
  (let [info (or (python-array-info x)
                 (throw (ex-info "expected dense or rectangular array data"
                                 {:value x})))
        flat (:flat info)]
    (case (:kind info)
      :int (array-elias-int flat decimals)
      :float (array-elias flat decimals)
      (throw (ex-info "Elias signal DL only supports numeric arrays"
                      {:kind (:kind info)})))))

(defn- valid-number?
  [x]
  (and (number? x)
       (not (missing-number? x))))

(defn jgaussian
  [x mu sigma]
  (max 0.0
       (+ (/ (Math/pow (- (double x) (double mu)) 2.0)
             (* 2.0 (Math/log 2.0) (Math/pow (double sigma) 2.0)))
          (log2 (Math/sqrt (/ Math/PI 2.0)))
          (log2 (* 2.0 (double sigma))))))

(defn- valid-number-stats
  [xs]
  (cond
    (primitive-double-array? xs)
    (let [^doubles xs xs
          total-count (alength xs)]
      (loop [idx 0
             valid-count 0
             sum 0.0]
        (if (= idx total-count)
          {:n valid-count
           :sum sum}
          (let [x (aget xs idx)]
            (if (Double/isNaN x)
              (recur (inc idx) valid-count sum)
              (recur (inc idx)
                     (inc valid-count)
                     (+ sum x)))))))

    (primitive-long-array? xs)
    (let [^longs xs xs
          total-count (alength xs)]
      (loop [idx 0
             valid-count 0
             sum 0.0]
        (if (= idx total-count)
          {:n valid-count
           :sum sum}
          (let [x (aget xs idx)]
            (if (= intnan64 x)
              (recur (inc idx) valid-count sum)
              (recur (inc idx)
                     (inc valid-count)
                     (+ sum (double x))))))))

    :else
    (loop [idx 0
           total-count (count xs)
           valid-count 0
           sum 0.0]
      (if (= idx total-count)
        {:n valid-count
         :sum sum}
        (let [x (nth xs idx)]
          (if (valid-number? x)
            (recur (inc idx)
                   total-count
                   (inc valid-count)
                   (+ sum (double x)))
            (recur (inc idx) total-count valid-count sum)))))))

(defn- variance-sum
  [xs mu]
  (cond
    (primitive-double-array? xs)
    (let [^doubles xs xs
          n (alength xs)]
      (loop [idx 0
             total 0.0]
        (if (= idx n)
          total
          (let [x (aget xs idx)]
            (if (Double/isNaN x)
              (recur (inc idx) total)
              (let [d (- x mu)]
                (recur (inc idx)
                       (+ total (* d d)))))))))

    (primitive-long-array? xs)
    (let [^longs xs xs
          n (alength xs)]
      (loop [idx 0
             total 0.0]
        (if (= idx n)
          total
          (let [x (aget xs idx)]
            (if (= intnan64 x)
              (recur (inc idx) total)
              (let [d (- (double x) mu)]
                (recur (inc idx)
                       (+ total (* d d)))))))))

    :else
    (loop [idx 0
           n (count xs)
           total 0.0]
      (if (= idx n)
        total
        (let [x (nth xs idx)]
          (if (valid-number? x)
            (let [d (- (double x) mu)]
              (recur (inc idx)
                     n
                     (+ total (* d d))))
            (recur (inc idx) n total)))))))

(defn- gaussian-signal-dl
  [xs scale mu sigma]
  (cond
    (primitive-double-array? xs)
    (let [^doubles xs xs
          n (alength xs)]
      (loop [idx 0
             total 0.0
             all-nan? true]
        (if (= idx n)
          [total all-nan?]
          (let [x (aget xs idx)]
            (if (Double/isNaN x)
              (recur (inc idx) total all-nan?)
              (recur (inc idx)
                     (+ total (jgaussian (* x scale) mu sigma))
                     false))))))

    (primitive-long-array? xs)
    (let [^longs xs xs
          n (alength xs)]
      (loop [idx 0
             total 0.0
             all-nan? true]
        (if (= idx n)
          [total all-nan?]
          (let [x (aget xs idx)]
            (if (= intnan64 x)
              (recur (inc idx) total all-nan?)
              (recur (inc idx)
                     (+ total (jgaussian (* (double x) scale) mu sigma))
                     false))))))

    :else
    (loop [idx 0
           n (count xs)
           total 0.0
           all-nan? true]
      (if (= idx n)
        [total all-nan?]
        (let [x (nth xs idx)]
          (if (missing-number? x)
            (recur (inc idx) n total all-nan?)
            (recur (inc idx)
                   n
                   (+ total (jgaussian (* (double x) scale) mu sigma))
                   false)))))))

(defn- constant-valid-summary
  [xs]
  (cond
    (primitive-double-array? xs)
    (let [^doubles xs xs
          n (alength xs)]
      (loop [idx 0
             seen-valid? false
             first-valid 0.0]
        (if (= idx n)
          {:constant? true
           :all-nan? (not seen-valid?)
           :value first-valid}
          (let [x (aget xs idx)]
            (if (Double/isNaN x)
              (recur (inc idx) seen-valid? first-valid)
              (cond
                (not seen-valid?)
                (recur (inc idx) true x)

                (= first-valid x)
                (recur (inc idx) seen-valid? first-valid)

                :else
                {:constant? false
                 :all-nan? false
                 :value first-valid}))))))

    (primitive-long-array? xs)
    (let [^longs xs xs
          n (alength xs)]
      (loop [idx 0
             seen-valid? false
             first-valid 0]
        (if (= idx n)
          {:constant? true
           :all-nan? (not seen-valid?)
           :value first-valid}
          (let [x (aget xs idx)]
            (if (= intnan64 x)
              (recur (inc idx) seen-valid? first-valid)
              (cond
                (not seen-valid?)
                (recur (inc idx) true x)

                (= first-valid x)
                (recur (inc idx) seen-valid? first-valid)

                :else
                {:constant? false
                 :all-nan? false
                 :value first-valid}))))))

    :else
    (let [n (count xs)]
      (loop [idx 0
             seen-valid? false
             first-valid nil]
        (if (= idx n)
          {:constant? true
           :all-nan? (not seen-valid?)
           :value first-valid}
          (let [x (nth xs idx)]
            (if (missing-number? x)
              (recur (inc idx) seen-valid? first-valid)
              (cond
                (not seen-valid?)
                (recur (inc idx) true x)

                (= (double first-valid) (double x))
                (recur (inc idx) seen-valid? first-valid)

                :else
                {:constant? false
                 :all-nan? false
                 :value first-valid}))))))))

(defn- one-dimensional-gaussian-array?
  [shape]
  (and (< (count shape) 3)
       (not (and (= 2 (count shape))
                 (> (second shape) 1)))))

(defn- constant-gaussian-result
  [shape flat decimals]
  (when (one-dimensional-gaussian-array? shape)
    (let [{:keys [constant? all-nan? value]} (constant-valid-summary flat)]
      (when constant?
        {:dl (if all-nan?
               0.0
               (let [scale (pow10 decimals)]
                 (jelias-posneg (python-rint (* (double value) scale)))))
         :all-nan? all-nan?}))))

(defn- mean
  [xs]
  (/ (reduce + 0.0 xs) (double (count xs))))

(defn desc-len-1d-array-gaussian
  [xs scale]
  (let [{:keys [n sum]} (valid-number-stats xs)]
    (if (pos? n)
      (let [mu0 (/ sum (double n))
            mu (* mu0 scale)
            mu-dl (jelias-posneg (python-rint mu))
            sigma (* (Math/sqrt (/ (variance-sum xs mu0)
                                   (double n)))
                     scale)]
        (if (zero? sigma)
          [mu-dl true]
          (let [sigma-dl (jelias-posneg (python-rint sigma))
                [sig-dl all-nan?] (gaussian-signal-dl xs scale mu sigma)]
            [(+ sig-dl mu-dl sigma-dl) all-nan?])))
      [0.0 true])))

(defn- rows
  [x]
  (mapv vec x))

(defn- row-validity
  [row]
  (let [missing (count (filter missing-number? row))
        n (count row)]
    (cond
      (zero? missing) :valid
      (= missing n) :all-missing
      :else :partial)))

(defn- column
  [matrix j]
  (mapv #(nth % j) matrix))

(defn- covariance-matrix
  [matrix]
  (let [n (count matrix)
        d (count (first matrix))
        means (mapv #(mean (column matrix %)) (range d))]
    (mapv (fn [i]
            (mapv (fn [j]
                    (/ (reduce + 0.0
                               (map (fn [row]
                                      (* (- (nth row i) (nth means i))
                                         (- (nth row j) (nth means j))))
                                    matrix))
                       (double (dec n))))
                  (range d)))
          (range d))))

(defn- swapv
  [v i j]
  (assoc v i (nth v j) j (nth v i)))

(defn- determinant
  [matrix]
  (let [n (count matrix)]
    (loop [i 0
           m (mapv #(mapv double %) matrix)
           det 1.0
           sign 1.0]
      (if (= i n)
        (* sign det)
        (let [pivot-row (apply max-key #(Math/abs (get-in m [% i]))
                               (range i n))
              pivot (get-in m [pivot-row i])]
          (if (< (Math/abs pivot) 1.0e-12)
            0.0
            (let [m (if (= pivot-row i) m (swapv m i pivot-row))
                  sign (if (= pivot-row i) sign (- sign))
                  pivot (get-in m [i i])
                  m (reduce (fn [acc r]
                              (let [factor (/ (get-in acc [r i]) pivot)]
                                (assoc acc r
                                       (mapv (fn [rv iv]
                                               (- rv (* factor iv)))
                                             (nth acc r)
                                             (nth acc i)))))
                            m
                            (range (inc i) n))]
              (recur (inc i) m (* det pivot) sign))))))))

(defn- identity-matrix
  [n]
  (mapv (fn [i]
          (mapv (fn [j] (if (= i j) 1.0 0.0)) (range n)))
        (range n)))

(defn- inverse-matrix
  [matrix]
  (let [n (count matrix)]
    (loop [i 0
           left (mapv #(mapv double %) matrix)
           right (identity-matrix n)]
      (if (= i n)
        right
        (let [pivot-row (apply max-key #(Math/abs (get-in left [% i]))
                               (range i n))
              pivot (get-in left [pivot-row i])]
          (when (>= (Math/abs pivot) 1.0e-12)
            (let [left (if (= pivot-row i) left (swapv left i pivot-row))
                  right (if (= pivot-row i) right (swapv right i pivot-row))
                  pivot (get-in left [i i])
                  left (assoc left i (mapv #(/ % pivot) (nth left i)))
                  right (assoc right i (mapv #(/ % pivot) (nth right i)))
                  [left right]
                  (reduce (fn [[l r] row]
                            (if (= row i)
                              [l r]
                              (let [factor (get-in l [row i])]
                                [(assoc l row
                                        (mapv (fn [rv iv]
                                                (- rv (* factor iv)))
                                              (nth l row)
                                              (nth l i)))
                                 (assoc r row
                                        (mapv (fn [rv iv]
                                                (- rv (* factor iv)))
                                              (nth r row)
                                              (nth r i)))])))
                          [left right]
                          (range n))]
              (recur (inc i) left right))))))))

(defn- mat-vec
  [matrix v]
  (mapv (fn [row]
          (reduce + 0.0 (map * row v)))
        matrix))

(defn- dot
  [a b]
  (reduce + 0.0 (map * a b)))

(defn desc-len-nd-points-gaussian
  [matrix scale]
  (let [matrix (rows matrix)
        n (count matrix)
        d (count (first matrix))
        row-kinds (mapv row-validity matrix)
        valid (mapv #(mapv (fn [x] (* (double x) scale)) %)
                    (map first
                         (filter (fn [[row kind]] (= :valid kind))
                                 (map vector matrix row-kinds))))
        partial (mapv first
                      (filter (fn [[_ kind]] (= :partial kind))
                              (map vector matrix row-kinds)))]
    (cond
      (and (empty? valid) (empty? partial))
      [0.0 true]

      (< (count valid) (+ d 2))
      [Double/POSITIVE_INFINITY false]

      :else
      (let [mu (mapv #(mean (column valid %)) (range d))
            cov (covariance-matrix valid)
            det (determinant cov)]
        (if-not (pos? det)
          [Double/POSITIVE_INFINITY false]
          (if-let [cov-inv (inverse-matrix cov)]
            (let [log-det (Math/log det)
                  mahal-sq (reduce + 0.0
                                   (map (fn [row]
                                          (let [diff (mapv - row mu)]
                                            (dot (mat-vec cov-inv diff) diff)))
                                        valid))
                  data-dl (+ (/ mahal-sq (* 2.0 (Math/log 2.0)))
                             (* (count valid)
                                (+ (* (/ d 2.0) (log2 (* 2.0 Math/PI)))
                                   (/ (* 0.5 log-det) (Math/log 2.0)))))
                  mu-dl (reduce + 0.0 (map #(jelias-posneg (python-rint %)) mu))
                  cov-dl (reduce + 0.0
                                 (for [i (range d)
                                       j (range i d)]
                                   (jelias-posneg
                                    (python-rint (get-in cov [i j])))))
                  sigmas (mapv #(Math/sqrt (get-in cov [% %])) (range d))
                  partial-dl
                  (reduce + 0.0
                          (for [row partial
                                j (range d)
                                :let [x (nth row j)]
                                :when (not (missing-number? x))]
                            (jgaussian (* (double x) scale)
                                       (nth mu j)
                                       (nth sigmas j))))]
              [(+ (max 0.0 data-dl) partial-dl mu-dl cov-dl) false])
            [Double/POSITIVE_INFINITY false]))))))

(defn- channel-flat
  [x channel-index]
  (vec
   (mapcat (fn [row]
             (mapcat (fn [cell]
                       (flatten-array (nth cell channel-index)))
                     row))
           x)))

(defn desc-len-array-gaussian
  [array-info decimals]
  (let [{:keys [shape flat]} array-info]
    (if (> decimals max-precision)
      [Double/POSITIVE_INFINITY false]
      (let [scale (pow10 decimals)]
        (cond
          (and (= 2 (count shape))
               (> (second shape) 1))
          (desc-len-nd-points-gaussian (:data array-info) scale)

          (< (count shape) 3)
          (desc-len-1d-array-gaussian flat scale)

          :else
          (let [[dl all-nan?]
                (reduce (fn [[total all-nan?] channel-index]
                          (let [[channel-dl channel-all-nan?]
                                (desc-len-1d-array-gaussian
                                 (channel-flat (:data array-info) channel-index)
                                 scale)]
                            [(+ total channel-dl)
                             (and all-nan? channel-all-nan?)]))
                        [0.0 false]
                        (range (nth shape 2)))]
            [dl all-nan?]))))))

(defn array-desc-len
  [{:keys [shape flat kind size] :as info}
   {:keys [mode] :or {mode :use-gaussian}}]
  (cond
      (zero? size)
      0.0

      (= :empty kind)
      0.0

      (= :bool kind)
      (+ (reduce + 0.0 (map jelias shape))
         size)

      (= :string kind)
      (+ (reduce + 0.0 (map jelias shape))
         (reduce + 0.0
                 (map #(desc-len-data % {:mode :default}) flat)))

      (#{:int :float} kind)
      (let [decimals (if (= :int kind)
                       (precision-int-array flat)
                       (precision-array flat))
            constant-gauss (when (and (= mode :use-gaussian)
                                       (<= decimals max-precision))
                              (constant-gaussian-result shape flat decimals))
            [elias-dl elias-all-nan?] (if constant-gauss
                                        [Double/POSITIVE_INFINITY
                                         (:all-nan? constant-gauss)]
                                        (if (= :int kind)
                                          (array-elias-int flat decimals)
                                          (array-elias flat decimals)))
            gauss-dl (cond
                       constant-gauss
                       (:dl constant-gauss)

                       (= mode :use-gaussian)
                       (first (desc-len-array-gaussian info decimals))

                       :else
                       Double/POSITIVE_INFINITY)
            sig-dl (min elias-dl gauss-dl)
            dl (+ (reduce + 0.0 (map jelias shape))
                  sig-dl)
            dec-dl (if elias-all-nan?
                     0.0
                     (if (= :float kind)
                       (jelias-posneg decimals)
                       (jelias (- 1.0 decimals))))]
        (+ dl dec-dl))

      :else
      nil))

(defn- sequential-dl
  [xs opts]
  (+ (if (seq xs) (jelias (count xs)) 0.0)
     (reduce + 0.0 (map #(desc-len-data % opts) xs))))

(defn log-fac
  [m n]
  (if (> m n)
    0.0
    (reduce + 0.0 (map log2 (range m (inc n))))))

(defn log-binomial
  [n k]
  (if (> k (- n k))
    (- (log-fac (inc (- n k)) n)
       (log-fac 2 k))
    (- (log-fac (inc k) n)
       (log-fac 2 (- n k)))))

(defn log-multinomial
  [n counts]
  (let [counts (vec counts)]
    (reduce (fn [total c]
              (- total (log-fac 2 c)))
            (log-fac (inc (first counts)) n)
            (rest counts))))

(defn- unique-counts
  [xs]
  (vals (frequencies xs)))

(defn describe-by-permutation-index
  "Port of Python WILLIAM's private `_describe_by_permutation_index` helper."
  [x]
  (let [{:keys [flat kind size]} (python-array-info x)
        y (if (= :float kind)
            (remove nan-number? flat)
            flat)
        counts (if (= :float kind)
                 (conj (vec (unique-counts y))
                       (count (filter nan-number? flat)))
                 (vec (unique-counts y)))]
    (if (>= (count counts) (min max-unique size))
      Double/POSITIVE_INFINITY
      (let [counts (vec (sort counts))
            num (log-multinomial size counts)
            dl-index (+ num (* 2.0 (log2 (inc num))) 1.0)
            dl-counts (+ (reduce + 0.0
                                 (map #(jelias (inc %)) (butlast counts)))
                         (jelias (last counts)))
            unique-elements (vec (distinct y))
            unique-elements (if (= :float kind)
                              (conj unique-elements Double/NaN)
                              unique-elements)
            dl-uniques (if (= :bool kind)
                         0.0
                         (desc-len-data unique-elements {:mode :default}))]
        (+ dl-index dl-counts dl-uniques)))))

(defn desc-len-data
  "Python WILLIAM-compatible description length for native CIWI data.

  Dense arrays, plus raw vectors that are rectangular and homogeneous in
  numeric, boolean, or string scalars, are treated as Python `np.ndarray`
  values. Other sequential values are structural lists. The default mode
  matches `Value.desc_len()` in Python WILLIAM, which uses Gaussian
  numeric-array coding.
  "
  ([x]
   (desc-len-data x {:mode :use-gaussian}))
  ([x opts]
   (if-let [scalar-dl (scalar-desc-len x opts)]
     scalar-dl
     (cond
       (value? x)
       (if (:dummy? x)
         0.0
         (desc-len-data (:data x) opts))

       (operator-like? x)
       (double (:dl x))

       :else
       (if-let [array-dl (when (dense/ndarray? x)
                           (dense/desc-len-data x opts))]
         (double array-dl)
         (if-let [array-info (python-array-info x)]
           (double (array-desc-len array-info opts))
         (cond
           (sequential? x)
           (double (sequential-dl (vec x) opts))

           (set? x)
           (double (sequential-dl (hashing/sort-anything x) opts))

           (map? x)
           (double (+ (if (seq x) (jelias (count x)) 0.0)
                      (reduce + 0.0
                              (map (fn [[k v]]
                                     (+ (desc-len-data k opts)
                                        (desc-len-data v opts)))
                                   (sort (fn [[left _] [right _]]
                                           (hashing/stable-compare left right))
                                         x)))))

           :else
           (double (+ (if (seq (pr-str x)) (jelias (count (pr-str x))) 0.0)
                      (* 8.0 (count (pr-str x))))))))))))

(defn desc-len
  ([v]
   (desc-len v {:mode :use-gaussian}))
  ([v opts]
   (if (:dummy? v)
     0.0
     (desc-len-data (:data v) opts))))

(defn desc-len-cached
  "Return `desc-len` using a caller-owned cache store.

  Python WILLIAM memoizes `Value.desc_len()` on each Value instance. CIWI keeps
  values immutable, so callers that score many related candidate graphs pass an
  explicit cache instead of mutating the value record. Existing `Value` records
  are cached by identity, matching Python's per-instance memoization and
  avoiding repeated large-vector hash work on cache hits. Dense `Value` records
  use weak identity keys, so search-scoped caches can reuse scores while the
  value is live without retaining every generated dense array. Raw non-`Value`
  inputs keep value-based keys because they do not have a stable instance
  identity.
  "
  ([cache v]
   (desc-len-cached cache v {:mode :use-gaussian}))
  ([cache v opts]
   (if (nil? cache)
     (desc-len v opts)
     (let [value-input? (value? v)
           v (value v)
           data (:data v)
           k (cond
               (and value-input? (dense/ndarray? data))
               (weak-identity-value-cache-key v opts (:dummy? v))

               (dense/ndarray? data)
               [::dense-value-desc-len
                opts
                (:dummy? v)
                (hashing/content-fingerprint data)]

               value-input?
               (IdentityValueCacheKey. v opts (:dummy? v))

               :else
               [opts (:dummy? v) data])]
       (cache/get-or-compute! cache k #(desc-len v opts))))))
