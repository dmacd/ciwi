(ns ciwi.dense.vector
  (:refer-clojure :exclude [repeat])
  (:require [ciwi.dense.protocols :as p])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

(defrecord NDArray [backend dtype shape data flat])

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

(defn rectangular-shape
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

(defn flatten-data
  [x]
  (if (seq-array? x)
    (mapcat flatten-data x)
    [x]))

(defn product
  [xs]
  (reduce * 1 xs))

(defn canonical-dtype
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

(defn infer-dtype
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

(defn build-tree*
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

(defn build-tree
  [shape flat]
  (first (build-tree* shape flat)))

(defn- expected-flat-size
  [shape]
  (if (seq shape)
    (product shape)
    1))

(defn- normalize-flat
  [flat dtype]
  (let [flat (vec flat)
        dtype (or (canonical-dtype dtype)
                  (infer-dtype flat))]
    {:dtype dtype
     :flat (mapv #(coerce-leaf dtype %) flat)}))

(defn from-flat
  [backend flat shape {:keys [dtype]}]
  (let [shape (vec shape)
        flat (vec flat)]
    (when-not (= (count flat) (expected-flat-size shape))
      (throw (ex-info "flat dense data does not match shape"
                      {:shape shape
                       :flat-count (count flat)})))
    (let [{:keys [dtype flat]} (normalize-flat flat dtype)]
      (->NDArray (p/-backend-id backend)
                 dtype
                 shape
                 (build-tree shape flat)
                 flat))))

(defn array
  [backend data {:keys [dtype] :as opts}]
  (if (satisfies? p/DenseArray data)
    (if dtype
      (array backend (p/-tolist data) opts)
      data)
    (let [shape (or (rectangular-shape data)
                    (throw (ex-info "dense arrays must be rectangular"
                                    {:data data})))
          flat (vec (flatten-data data))]
      (from-flat backend flat shape opts))))

(defn- flat->digest
  [dtype shape flat]
  (let [digest (MessageDigest/getInstance "SHA-256")
        update-str! (fn [s]
                      (.update digest (.getBytes (str s) "UTF-8")))]
    (update-str! "ciwi.dense.v1|")
    (update-str! dtype)
    (update-str! "|")
    (update-str! shape)
    (update-str! "|")
    (doseq [x flat]
      (case dtype
        :bool
        (update-str! (if x "b1|" "b0|"))

        :int64
        (do
          (update-str! "i")
          (update-str! (long x))
          (update-str! "|"))

        :float64
        (if (nan? x)
          (update-str! "fnan|")
          (do
            (update-str! "f")
            (update-str! (Double/doubleToLongBits (double x)))
            (update-str! "|")))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn content-fingerprint
  [x]
  [:dense
   (p/-dtype x)
   (p/-shape x)
   (flat->digest (p/-dtype x) (p/-shape x) (p/-ravel x))])

(defn- same-flat-content?
  [left right]
  (and (= (count left) (count right))
       (every? (fn [[x y]]
                 (if (and (nan? x) (nan? y))
                   true
                   (= x y)))
               (map vector left right))))

(defn same-content?
  [left right]
  (and (satisfies? p/DenseArray right)
       (= (p/-dtype left) (p/-dtype right))
       (= (p/-shape left) (p/-shape right))
       (same-flat-content? (p/-ravel left) (p/-ravel right))))

(extend-type NDArray
  p/DenseArray
  (-backend [x] (:backend x))
  (-dtype [x] (:dtype x))
  (-shape [x] (:shape x))
  (-ndim [x] (count (:shape x)))
  (-size [x] (count (:flat x)))
  (-ravel [x] (:flat x))
  (-tolist [x] (:data x))
  (-array-info [x]
    {:shape (:shape x)
     :flat (:flat x)
     :kind (case (:dtype x)
             :bool :bool
             :int64 :int
             :float64 :float)
     :size (count (:flat x))
     :data (:data x)})
  (-content-fingerprint [x]
    (content-fingerprint x))
  (-same-content? [x y]
    (same-content? x y)))

(defn- dense-array?
  [x]
  (satisfies? p/DenseArray x))

(defn- broadcast-flat
  [x target-shape target-size]
  (cond
    (dense-array? x)
    (if (= target-shape (p/-shape x))
      (p/-ravel x)
      (throw (ex-info "dense operands are not broadcast-compatible"
                      {:left target-shape
                       :right (p/-shape x)})))

    :else
    (vec (clojure.core/repeat target-size x))))

(defn- result-shape
  [xs]
  (let [arrays (filter dense-array? xs)]
    (if (seq arrays)
      (let [shapes (map p/-shape arrays)]
        (if (apply = shapes)
          (first shapes)
          (throw (ex-info "dense operands are not broadcast-compatible"
                          {:shapes (vec shapes)}))))
      [])))

(defn elementwise
  [backend f xs opts]
  (let [shape (result-shape xs)
        target-size (expected-flat-size shape)
        flats (mapv #(broadcast-flat % shape target-size) xs)
        result-flat (apply mapv f flats)]
    (if (some dense-array? xs)
      (from-flat backend result-flat shape opts)
      (first result-flat))))

(defn- check-rank
  [x ranks]
  (when-not (contains? ranks (p/-ndim x))
    (throw (ex-info "unsupported dense rank"
                    {:shape (p/-shape x)
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
  (let [[_ cols] (p/-shape x)]
    (nth (p/-ravel x) (+ (* row cols) col))))

(defn dot
  [backend x y]
  (let [x (p/-array backend x {})
        y (p/-array backend y {})
        sx (p/-shape x)
        sy (p/-shape y)]
    (check-rank x #{1 2})
    (check-rank y #{1 2})
    (cond
      (and (= 1 (p/-ndim x)) (= 1 (p/-ndim y)))
      (dot1 (p/-ravel x) (p/-ravel y))

      (and (= 2 (p/-ndim x)) (= 1 (p/-ndim y)))
      (let [[rows cols] sx]
        (when-not (= cols (first sy))
          (throw (ex-info "dot shape mismatch" {:left sx :right sy})))
        (from-flat backend
                   (mapv (fn [row]
                           (dot1 (mapv #(get2 x row %) (range cols))
                                 (p/-ravel y)))
                         (range rows))
                   [rows]
                   {}))

      (and (= 1 (p/-ndim x)) (= 2 (p/-ndim y)))
      (let [[rows cols] sy]
        (when-not (= (first sx) rows)
          (throw (ex-info "dot shape mismatch" {:left sx :right sy})))
        (from-flat backend
                   (mapv (fn [col]
                           (dot1 (p/-ravel x)
                                 (mapv #(get2 y % col) (range rows))))
                         (range cols))
                   [cols]
                   {}))

      :else
      (let [[x-rows x-cols] sx
            [y-rows y-cols] sy]
        (when-not (= x-cols y-rows)
          (throw (ex-info "dot shape mismatch" {:left sx :right sy})))
        (from-flat backend
                   (for [row (range x-rows)
                         col (range y-cols)]
                     (dot1 (mapv #(get2 x row %) (range x-cols))
                           (mapv #(get2 y % col) (range y-rows))))
                   [x-rows y-cols]
                   {})))))

(defn sum
  [backend x axis]
  (let [x (p/-array backend x {})
        sx (p/-shape x)]
    (case axis
      nil (clojure.core/reduce + 0.0 (p/-ravel x))
      0 (do
          (check-rank x #{2})
          (let [[rows cols] sx]
            (from-flat backend
                       (mapv (fn [col]
                               (clojure.core/reduce + 0.0
                                                    (map #(get2 x % col)
                                                         (range rows))))
                             (range cols))
                       [cols]
                       {})))
      1 (do
          (check-rank x #{2})
          (let [[rows cols] sx]
            (from-flat backend
                       (mapv (fn [row]
                               (clojure.core/reduce + 0.0
                                                    (map #(get2 x row %)
                                                         (range cols))))
                             (range rows))
                       [rows]
                       {})))
      (throw (ex-info "unsupported dense sum axis"
                      {:axis axis
                       :shape sx})))))

(defn take-indices
  [backend x indices opts]
  (let [x (p/-array backend x {})
        values (p/-ravel x)
        indices (if (dense-array? indices) (p/-ravel indices) (vec indices))]
    (from-flat backend
               (mapv #(nth values %) indices)
               [(count indices)]
               opts)))

(defn put
  [backend x indices values opts]
  (let [x (p/-array backend x {})
        base (p/-ravel x)
        indices (if (dense-array? indices) (p/-ravel indices) (vec indices))
        values (if (dense-array? values)
                 (p/-ravel values)
                 (if (and (sequential? values) (not (string? values)))
                   (vec values)
                   (vec (clojure.core/repeat (count indices) values))))]
    (when (= (count indices) (count values))
      (from-flat backend
                 (reduce (fn [acc [idx value]]
                           (assoc acc idx value))
                         base
                         (map vector indices values))
                 (p/-shape x)
                 opts))))

(defn cumsum
  [backend x opts]
  (let [x (p/-array backend x {})
        values (p/-ravel x)]
    (from-flat backend
               (loop [remaining values
                      total 0
                      result (transient [])]
                 (if-let [remaining (seq remaining)]
                   (let [total (+ total (first remaining))]
                     (recur (next remaining) total (conj! result total)))
                   (persistent! result)))
               (p/-shape x)
               opts)))

(defn diff
  [backend x opts]
  (let [x (p/-array backend x {})
        values (p/-ravel x)]
    (from-flat backend
               (loop [remaining values
                      previous 0
                      result (transient [])]
                 (if-let [remaining (seq remaining)]
                   (let [current (first remaining)]
                     (recur (next remaining)
                            current
                            (conj! result (- current previous))))
                   (persistent! result)))
               (p/-shape x)
               opts)))

(defrecord VectorBackend []
  p/DenseBackend
  (-backend-id [_]
    :ciwi.vector)
  (-array [backend data opts]
    (array backend data opts))
  (-from-flat [backend flat shape opts]
    (from-flat backend flat shape opts))
  (-array-like [backend template flat opts]
    (let [shape (or (:shape opts)
                    (when (dense-array? template) (p/-shape template))
                    [(count flat)])]
      (from-flat backend flat shape opts)))
  (-arange [backend start stop step opts]
    (when-not (zero? step)
      (let [flat (vec (range start stop step))]
        (from-flat backend flat [(count flat)] opts))))
  (-tile [backend n motif opts]
    (let [motif (p/-array backend motif {})
          flat (vec (apply clojure.core/concat
                           (clojure.core/repeat n (p/-ravel motif))))]
      (from-flat backend flat [(count flat)] opts)))
  (-concatenate [backend xs opts]
    (let [flat (vec (mapcat (fn [x]
                              (if (dense-array? x)
                                (p/-ravel x)
                                (vec x)))
                            xs))]
      (from-flat backend flat [(count flat)] opts)))
  (-elementwise [backend f xs opts]
    (elementwise backend f xs opts))
  (-dot [backend x y]
    (dot backend x y))
  (-sum [backend x axis]
    (sum backend x axis))
  (-take-indices [backend x indices opts]
    (take-indices backend x indices opts))
  (-put [backend x indices values opts]
    (put backend x indices values opts))
  (-cumsum [backend x opts]
    (cumsum backend x opts))
  (-diff [backend x opts]
    (diff backend x opts)))

(def backend
  (->VectorBackend))
