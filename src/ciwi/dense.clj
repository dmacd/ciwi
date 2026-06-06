(ns ciwi.dense
  (:refer-clojure :exclude [array compare equal max min repeat sum]))

(defrecord NDArray [backend dtype shape data flat])

(defprotocol DenseArray
  (backend [x])
  (dtype [x])
  (shape [x])
  (ndim [x])
  (size [x])
  (ravel [x])
  (tolist [x]))

(extend-type NDArray
  DenseArray
  (backend [x] (:backend x))
  (dtype [x] (:dtype x))
  (shape [x] (:shape x))
  (ndim [x] (count (:shape x)))
  (size [x] (count (:flat x)))
  (ravel [x] (:flat x))
  (tolist [x] (:data x)))

(defn ndarray?
  [x]
  (instance? NDArray x))

(defn nan?
  [x]
  (and (number? x)
       (Double/isNaN (double x))))

(defn missing?
  [x]
  (or (nil? x)
      (nan? x)))

(defn- seq-array?
  [x]
  (and (sequential? x)
       (not (string? x))))

(defn- rectangular-shape
  [x]
  (if (seq-array? x)
    (let [xs (vec x)]
      (if (empty? xs)
        [0]
        (let [child-shapes (mapv rectangular-shape xs)]
          (when (and (every? some? child-shapes)
                     (apply = child-shapes))
            (into [(count xs)] (first child-shapes))))))
    []))

(defn- flatten-data
  [x]
  (if (seq-array? x)
    (mapcat flatten-data x)
    [x]))

(defn- canonical-dtype
  [dtype]
  (case dtype
    nil nil
    :bool :bool
    :boolean :bool
    :int :int64
    :integer :int64
    :int64 :int64
    :long :int64
    :float :float64
    :double :float64
    :float64 :float64
    (throw (ex-info "unsupported dense dtype" {:dtype dtype}))))

(defn- infer-dtype
  [flat]
  (cond
    (empty? flat)
    :float64

    (every? #(or (true? %) (false? %)) flat)
    :bool

    (every? #(and (integer? %) (not (nan? %))) flat)
    :int64

    (every? #(or (missing? %) (number? %)) flat)
    :float64

    :else
    (throw (ex-info "dense arrays support numeric and boolean leaves"
                    {:flat flat}))))

(defn- coerce-leaf
  [dtype x]
  (case dtype
    :bool
    (if (or (true? x) (false? x))
      x
      (throw (ex-info "bool dense arrays cannot represent missing or numeric leaves"
                      {:value x})))

    :int64
    (if (and (integer? x) (not (nan? x)))
      (long x)
      (throw (ex-info "int64 dense arrays cannot represent missing or non-integer leaves"
                      {:value x})))

    :float64
    (if (missing? x)
      Double/NaN
      (if (number? x)
        (double x)
        (throw (ex-info "float64 dense arrays require numeric leaves"
                        {:value x}))))))

(defn- map-tree
  [f x]
  (if (seq-array? x)
    (mapv #(map-tree f %) x)
    (f x)))

(defn- build-tree*
  [shape flat]
  (if (empty? shape)
    [(first flat) (rest flat)]
    (let [n (first shape)]
      (loop [idx 0
             remaining flat
             result []]
        (if (= idx n)
          [result remaining]
          (let [[child remaining] (build-tree* (rest shape) remaining)]
            (recur (inc idx) remaining (conj result child))))))))

(defn- build-tree
  [shape flat]
  (first (build-tree* shape flat)))

(defn array
  "Construct a vector-backed dense array.

  This intentionally follows NumPy's broad naming rather than Clojure-specific
  collection naming. `nil` leaves in numeric arrays are normalized to `NaN`,
  which is the missing-value convention CIWI uses for dense numeric propagation.
  "
  ([data]
   (array data {}))
  ([data {:keys [dtype backend]
          :or {backend :ciwi.vector}}]
   (if (ndarray? data)
     (if dtype
       (array (tolist data) {:dtype dtype :backend backend})
       data)
     (let [shape (or (rectangular-shape data)
                     (throw (ex-info "dense arrays must be rectangular"
                                     {:data data})))
           flat (vec (flatten-data data))
           dtype (or (canonical-dtype dtype)
                     (infer-dtype flat))
           coerced-flat (mapv #(coerce-leaf dtype %) flat)
           coerced-data (build-tree shape coerced-flat)]
       (->NDArray backend dtype shape coerced-data coerced-flat)))))

(defn asarray
  ([x]
   (if (ndarray? x) x (array x)))
  ([x opts]
   (array x opts)))

(defn array-info
  [x]
  (let [x (asarray x)
        dtype (dtype x)]
    {:shape (shape x)
     :flat (ravel x)
     :kind (case dtype
             :bool :bool
             :int64 :int
             :float64 :float)
     :size (size x)
     :data (tolist x)}))

(defn- scalar?
  [x]
  (not (ndarray? x)))

(defn- broadcast-flat
  [x target-shape target-size]
  (cond
    (ndarray? x)
    (if (= target-shape (shape x))
      (ravel x)
      (throw (ex-info "dense operands are not broadcast-compatible"
                      {:left target-shape
                       :right (shape x)})))

    :else
    (vec (clojure.core/repeat target-size x))))

(defn- result-shape
  [xs]
  (let [arrays (filter ndarray? xs)]
    (if (seq arrays)
      (let [shapes (map shape arrays)]
        (if (apply = shapes)
          (first shapes)
          (throw (ex-info "dense operands are not broadcast-compatible"
                          {:shapes (vec shapes)}))))
      [])))

(defn- elementwise
  [f & xs]
  (let [shape (result-shape xs)
        target-size (if (seq shape)
                      (clojure.core/reduce * 1 shape)
                      1)
        flats (mapv #(broadcast-flat % shape target-size) xs)
        result-flat (apply mapv f flats)]
    (if (some ndarray? xs)
      (array (build-tree shape result-flat))
      (first result-flat))))

(defn add
  [x y]
  (elementwise + x y))

(defn subtract
  [x y]
  (elementwise - x y))

(defn multiply
  [x y]
  (elementwise * x y))

(defn divide
  [x y]
  (elementwise / x y))

(defn negative
  [x]
  (elementwise - x))

(defn- less-scalar
  [x y]
  (if (or (nan? x) (nan? y))
    false
    (< x y)))

(defn less
  [x y]
  (elementwise less-scalar x y))

(defn- equal-scalar
  [x y]
  (if (or (nan? x) (nan? y))
    false
    (= x y)))

(defn equal
  [x y]
  (elementwise equal-scalar x y))

(defn isnan
  [x]
  (elementwise nan? x))

(defn- check-rank
  [x ranks]
  (when-not (contains? ranks (ndim x))
    (throw (ex-info "unsupported dense rank"
                    {:shape (shape x)
                     :ranks ranks}))))

(defn- dot1
  [xs ys]
  (when-not (= (count xs) (count ys))
    (throw (ex-info "dot shape mismatch"
                    {:left (count xs)
                     :right (count ys)})))
  (clojure.core/reduce + 0.0 (map * xs ys)))

(defn- get2
  [x row col]
  (let [[_ cols] (shape x)]
    (nth (ravel x) (+ (* row cols) col))))

(defn dot
  [x y]
  (let [x (asarray x)
        y (asarray y)
        sx (shape x)
        sy (shape y)]
    (check-rank x #{1 2})
    (check-rank y #{1 2})
    (cond
      (and (= 1 (ndim x)) (= 1 (ndim y)))
      (dot1 (ravel x) (ravel y))

      (and (= 2 (ndim x)) (= 1 (ndim y)))
      (let [[rows cols] sx]
        (when-not (= cols (first sy))
          (throw (ex-info "dot shape mismatch" {:left sx :right sy})))
        (array (mapv (fn [row]
                       (dot1 (mapv #(get2 x row %) (range cols))
                             (ravel y)))
                     (range rows))))

      (and (= 1 (ndim x)) (= 2 (ndim y)))
      (let [[rows cols] sy]
        (when-not (= (first sx) rows)
          (throw (ex-info "dot shape mismatch" {:left sx :right sy})))
        (array (mapv (fn [col]
                       (dot1 (ravel x)
                             (mapv #(get2 y % col) (range rows))))
                     (range cols))))

      :else
      (let [[x-rows x-cols] sx
            [y-rows y-cols] sy]
        (when-not (= x-cols y-rows)
          (throw (ex-info "dot shape mismatch" {:left sx :right sy})))
        (array
         (mapv (fn [row]
                 (mapv (fn [col]
                         (dot1 (mapv #(get2 x row %) (range x-cols))
                               (mapv #(get2 y % col) (range y-rows))))
                       (range y-cols)))
               (range x-rows)))))))

(defn sum
  ([x]
   (clojure.core/reduce + 0.0 (ravel (asarray x))))
  ([x axis]
   (let [x (asarray x)
         sx (shape x)]
     (case axis
       nil (sum x)
       0 (do
           (check-rank x #{2})
           (let [[rows cols] sx]
             (array (mapv (fn [col]
                            (clojure.core/reduce + 0.0
                                                 (map #(get2 x % col)
                                                      (range rows))))
                          (range cols)))))
       1 (do
           (check-rank x #{2})
           (let [[rows cols] sx]
             (array (mapv (fn [row]
                            (clojure.core/reduce + 0.0
                                                 (map #(get2 x row %)
                                                      (range cols))))
                          (range rows)))))
       (throw (ex-info "unsupported dense sum axis"
                       {:axis axis
                        :shape sx}))))))
