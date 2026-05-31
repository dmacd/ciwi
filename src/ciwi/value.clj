(ns ciwi.value)

(defrecord Value [data name spec permeable? dummy?])

(defn log2
  [x]
  (/ (Math/log (double x)) (Math/log 2.0)))

(defn elias-discrete
  "Approximate Elias delta code length for positive integers."
  [n]
  (let [n (max 1 (long n))
        bit-len (inc (long (Math/floor (log2 n))))
        len-len (inc (long (Math/floor (log2 bit-len))))]
    (+ bit-len (* 2 len-len) -1)))

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

(defn- number-dl
  [x]
  (cond
    (nil? x) 0.0
    (integer? x) (double (+ 1 (elias-discrete (inc (abs (long x))))))
    (float? x) (double (+ 1 (* 8 (count (pr-str x)))))
    (number? x) (double (+ 1 (* 8 (count (pr-str x)))))
    :else nil))

(defn- sequential-dl
  [xs]
  (+ (elias-discrete (count xs))
     (reduce + 0.0 (map desc-len-data xs))))

(defn desc-len-data
  "Deterministic, compositional description length used by the prototype.

  This is intentionally simpler than Python WILLIAM's Gaussian/precision-aware
  codec, but it preserves the property the rewrite engine needs: structured
  descriptions can beat raw literals.
  "
  [x]
  (cond
    (nil? x) 0.0
    (number? x) (number-dl x)
    (string? x) (double (+ (elias-discrete (count x)) (* 8 (count x))))
    (keyword? x) (desc-len-data (name x))
    (vector? x) (double (sequential-dl x))
    (sequential? x) (double (sequential-dl (vec x)))
    (set? x) (double (sequential-dl (sort-by pr-str x)))
    (map? x) (double (+ (elias-discrete (count x))
                        (reduce + 0.0
                                (map (fn [[k v]]
                                       (+ (desc-len-data k)
                                          (desc-len-data v)))
                                     (sort-by (comp pr-str key) x)))))
    :else (double (+ (elias-discrete (count (pr-str x)))
                     (* 8 (count (pr-str x)))))))

(defn desc-len
  [v]
  (if (:dummy? v)
    0.0
    (desc-len-data (:data v))))
