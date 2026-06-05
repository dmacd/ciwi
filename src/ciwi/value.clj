(ns ciwi.value
  (:require [ciwi.hashing :as hashing]))

(defrecord Value [data name spec permeable? dummy?])

(def max-precision 10)
(def intnan64 Long/MIN_VALUE)
(def max-unique 10)
(def precision-epsilon 1.0e-10)

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

(defn value
  ([data]
   (value data {}))
  ([data {:keys [name spec permeable? dummy?]
          :or {permeable? true
               dummy? false}}]
   (if (value? data)
     data
     (->Value data name spec permeable? dummy?))))

(defn datum
  [x]
  (if (value? x)
    (:data x)
    x))

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
  (if (seq xs)
    (reduce max (map precision-scalar xs))
    (- max-precision)))

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

(defn- flatten-array
  [x]
  (if (vector? x)
    (mapcat flatten-array x)
    [x]))

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
  (when (vector? x)
    (when-let [shape (rectangular-shape x)]
      (let [flat (vec (flatten-array x))
            kind (homogeneous-kind flat)]
        (when kind
          {:shape shape
           :flat flat
           :kind kind
           :size (product shape)})))))

(defn- array-elias
  [xs decimals]
  (loop [remaining xs
         sig-dl 0.0
         all-nan? true]
    (if-let [s (first remaining)]
      (if (missing-number? s)
        (recur (rest remaining) sig-dl all-nan?)
        (recur (rest remaining)
               (+ sig-dl
                  (jelias-posneg (python-rint (* (double s)
                                                 (pow10 decimals)))))
               false))
      [sig-dl all-nan?])))

(defn- valid-number?
  [x]
  (and (number? x)
       (not (missing-number? x))))

(defn- mean
  [xs]
  (/ (reduce + 0.0 xs) (double (count xs))))

(defn- nanmean
  [xs]
  (let [valid (filter valid-number? xs)]
    (when (seq valid)
      (mean (map double valid)))))

(defn- nanstd
  [xs]
  (when-let [mu (nanmean xs)]
    (let [valid (map double (filter valid-number? xs))]
      (Math/sqrt (/ (reduce + 0.0 (map #(let [d (- % mu)] (* d d)) valid))
                    (double (count valid)))))))

(defn jgaussian
  [x mu sigma]
  (max 0.0
       (+ (/ (Math/pow (- (double x) (double mu)) 2.0)
             (* 2.0 (Math/log 2.0) (Math/pow (double sigma) 2.0)))
          (log2 (Math/sqrt (/ Math/PI 2.0)))
          (log2 (* 2.0 (double sigma))))))

(defn desc-len-1d-array-gaussian
  [xs scale]
  (if-let [mu0 (nanmean xs)]
    (let [mu (* mu0 scale)
          mu-dl (jelias-posneg (python-rint mu))
          sigma (* (or (nanstd xs) 0.0) scale)]
      (if (zero? sigma)
        [mu-dl true]
        (let [sigma-dl (jelias-posneg (python-rint sigma))
              [sig-dl all-nan?]
              (reduce (fn [[total all-nan?] x]
                        (if (missing-number? x)
                          [total all-nan?]
                          [(+ total (jgaussian (* (double x) scale) mu sigma))
                           false]))
                      [0.0 true]
                      xs)]
          [(+ sig-dl mu-dl sigma-dl) all-nan?])))
    [0.0 true]))

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

(defn- array-desc-len
  [x {:keys [mode] :or {mode :use-gaussian}}]
  (let [{:keys [shape flat kind size] :as info}
        (assoc (python-array-info x) :data x)]
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
      (let [decimals (precision-array flat)
            [elias-dl elias-all-nan?] (array-elias flat decimals)
            gauss-dl (if (= mode :use-gaussian)
                       (first (desc-len-array-gaussian info decimals))
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
      nil)))

(defn- python-array?
  [x]
  (boolean (python-array-info x)))

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

  Vectors that are rectangular and homogeneous in numeric, boolean, or string
  scalars are treated as Python `np.ndarray` values. Other sequential values are
  structural lists. The default mode matches `Value.desc_len()` in Python
  WILLIAM, which uses Gaussian numeric-array coding.
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

       (python-array? x)
       (double (array-desc-len x opts))

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
                  (* 8.0 (count (pr-str x)))))))))

(defn desc-len
  ([v]
   (desc-len v {:mode :use-gaussian}))
  ([v opts]
   (if (:dummy? v)
     0.0
     (desc-len-data (:data v) opts))))
