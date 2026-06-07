(ns ciwi.dense.djl
  (:refer-clojure :exclude [repeat])
  (:require [ciwi.dense.protocols :as p]
            [ciwi.dense.vector :as vector-backend]
            [ciwi.value :as value])
  (:import [ai.djl.ndarray NDArray NDManager]
           [ai.djl.ndarray.types DataType Shape]
           [java.util Arrays]))

(defrecord DJLArray [dtype shape array flat primitive-flat data fingerprint])

(def ^:private boolean-array-class (class (boolean-array 0)))
(def ^:private long-array-class (class (long-array 0)))
(def ^:private double-array-class (class (double-array 0)))

(defonce ^:private manager*
  (atom nil))

(defn manager
  "Return the process-local DJL manager for this experimental backend."
  []
  (or @manager*
      (locking manager*
        (or @manager*
            (reset! manager* (NDManager/newBaseManager "PyTorch"))))))

(defn shutdown!
  "Close the process-local DJL manager if it has been realized."
  []
  (locking manager*
    (when-let [m @manager*]
      (.close ^NDManager m)
      (reset! manager* nil))))

(defn- shape-object
  [shape]
  (Shape. (long-array shape)))

(defn- shape-vector
  [^Shape shape]
  (mapv long (.getShape shape)))

(defn- product
  [xs]
  (reduce * 1 xs))

(defn- expected-flat-size
  [shape]
  (if (seq shape)
    (product shape)
    1))

(defn- djl-dtype
  [dtype]
  (case (vector-backend/canonical-dtype dtype)
    :bool DataType/BOOLEAN
    :int64 DataType/INT64
    :float64 DataType/FLOAT64))

(defn- ciwi-dtype
  [^DataType dtype]
  (cond
    (= dtype DataType/BOOLEAN) :bool
    (or (= dtype DataType/INT64)
        (= dtype DataType/INT32)
        (= dtype DataType/INT16)
        (= dtype DataType/INT8)) :int64
    (or (= dtype DataType/FLOAT64)
        (= dtype DataType/FLOAT32)
        (= dtype DataType/FLOAT16)
        (= dtype DataType/BFLOAT16)) :float64
    :else (throw (ex-info "unsupported DJL dtype" {:dtype (str dtype)}))))

(defn- coerce-leaf
  [dtype x]
  (case dtype
    :bool
    (if (or (true? x) (false? x))
      x
      (throw (ex-info "bool dense arrays cannot represent missing or numeric leaves"
                      {:value x})))

    :int64
    (if (and (integer? x) (not (vector-backend/nan? x)))
      (long x)
      (throw (ex-info "int64 dense arrays cannot represent missing or non-integer leaves"
                      {:value x})))

    :float64
    (if (vector-backend/missing? x)
      Double/NaN
      (if (number? x)
        (double x)
        (throw (ex-info "float64 dense arrays require numeric leaves"
                        {:value x}))))))

(defn- normalize-flat
  [flat dtype]
  (let [flat (vec flat)
        dtype (or (vector-backend/canonical-dtype dtype)
                  (vector-backend/infer-dtype flat))]
    {:dtype dtype
     :flat (mapv #(coerce-leaf dtype %) flat)}))

(defn- create-ndarray
  [dtype shape flat]
  (let [^NDManager m (manager)
        ^Shape shape (shape-object shape)]
    (case dtype
      :bool
      (.create m ^booleans (boolean-array flat) shape)

      :int64
      (.create m ^longs (long-array flat) shape)

      :float64
      (.create m ^doubles (double-array flat) shape))))

(defn- scalar?
  [^NDArray array]
  (zero? (.dimension (.getShape array))))

(defn- scalar-value
  [^NDArray array]
  (case (ciwi-dtype (.getDataType array))
    :bool (aget (.toBooleanArray array) 0)
    :int64 (aget (.toLongArray array) 0)
    :float64 (aget (.toDoubleArray array) 0)))

(defn- dense-array?
  [x]
  (satisfies? p/DenseArray x))

(defn- array-operand?
  [x]
  (or (dense-array? x)
      (and (sequential? x) (not (string? x)))))

(defn- ravel
  [^DJLArray x]
  (or (:flat x)
      (let [flat (or (:primitive-flat x)
                     (let [^NDArray array (:array x)]
                       (case (:dtype x)
                         :bool (.toBooleanArray array)
                         :int64 (.toLongArray array)
                         :float64 (.toDoubleArray array))))]
        (case (:dtype x)
          :bool (vec flat)
          :int64 (vec flat)
          :float64 (vec flat)))))

(defn- primitive-flat
  [^DJLArray x]
  (or (:primitive-flat x)
      (let [^NDArray array (:array x)]
        (case (:dtype x)
          :bool (.toBooleanArray array)
          :int64 (.toLongArray array)
          :float64 (.toDoubleArray array)))))

(def ^:private hash-seed-1 1469598103934665603)
(def ^:private hash-seed-2 -7046029254386353131)
(def ^:private hash-prime 1099511628211)

(defn- mix-hash
  [h bits]
  (unchecked-multiply (bit-xor (long h) (long bits)) hash-prime))

(defn- flat-hash-longs
  [^longs flat]
  (let [n (alength flat)]
    (loop [idx 0
           h1 hash-seed-1
           h2 hash-seed-2]
      (if (= idx n)
        [h1 h2]
        (let [bits (aget flat idx)]
          (recur (inc idx)
                 (mix-hash h1 bits)
                 (mix-hash h2 (Long/rotateLeft bits 32))))))))

(defn- flat-hash-doubles
  [^doubles flat]
  (let [n (alength flat)]
    (loop [idx 0
           h1 hash-seed-1
           h2 hash-seed-2]
      (if (= idx n)
        [h1 h2]
        (let [bits (Double/doubleToLongBits (aget flat idx))]
          (recur (inc idx)
                 (mix-hash h1 bits)
                 (mix-hash h2 (Long/rotateLeft bits 32))))))))

(defn- flat-hash-booleans
  [^booleans flat]
  (let [n (alength flat)]
    (loop [idx 0
           h1 hash-seed-1
           h2 hash-seed-2]
      (if (= idx n)
        [h1 h2]
        (let [bits (if (aget flat idx) 1 0)]
          (recur (inc idx)
                 (mix-hash h1 bits)
                 (mix-hash h2 (Long/rotateLeft bits 32))))))))

(defn- flat-hash-seq
  [dtype flat]
  (loop [xs (seq flat)
         h1 hash-seed-1
         h2 hash-seed-2]
    (if-let [xs (seq xs)]
      (let [x (first xs)
            bits (case dtype
                   :bool (if x 1 0)
                   :int64 (long x)
                   :float64 (Double/doubleToLongBits (double x)))]
        (recur (next xs)
               (mix-hash h1 bits)
               (mix-hash h2 (Long/rotateLeft bits 32))))
      [h1 h2])))

(defn- flat->digest
  [dtype shape flat]
  [:ciwi.dense.djl.v3
   (cond
     (instance? boolean-array-class flat)
     (flat-hash-booleans flat)

     (instance? long-array-class flat)
     (flat-hash-longs flat)

     (instance? double-array-class flat)
     (flat-hash-doubles flat)

     :else
     (flat-hash-seq dtype flat))])

(defn content-fingerprint
  [x]
  (or (:fingerprint x)
      [:dense
       (p/-dtype x)
       (p/-shape x)
       (flat->digest (p/-dtype x)
                     (p/-shape x)
                     (or (:flat x)
                         (:primitive-flat x)
                         (primitive-flat x)))]))

(defn- array-data
  [x]
  (or (:data x)
      (vector-backend/build-tree (:shape x) (ravel x))))

(declare wrap)

(defn- one-dimensional-description-shape?
  [shape]
  (and (< (count shape) 3)
       (not (and (= 2 (count shape))
                 (> (second shape) 1)))))

(defn- desc-len-data
  [x {:keys [mode] :or {mode :use-gaussian} :as opts}]
  (let [shape (p/-shape x)
        kind (case (p/-dtype x)
               :bool :bool
               :int64 :int
               :float64 :float)
        size (expected-flat-size shape)]
    (when (or (= :bool kind)
              (not= mode :use-gaussian)
              (one-dimensional-description-shape? shape))
      (value/array-desc-len {:shape shape
                             :flat (primitive-flat x)
                             :kind kind
                             :size size}
                            opts))))

(defn- better-partition-entry?
  [left right]
  (or (nil? right)
      (> (:count left) (:count right))
      (and (= (:count left) (:count right))
           (neg? (compare (pr-str (:value left))
                          (pr-str (:value right)))))))

(defn- most-common-entry
  [entries]
  (reduce (fn [best entry]
            (if (better-partition-entry? entry best)
              entry
              best))
          nil
          entries))

(defn- long-counts
  [^longs values]
  (let [n (alength values)]
    (loop [idx 0
           counts {}]
      (if (= idx n)
        counts
        (let [x (aget values idx)]
          (recur (inc idx)
                 (update counts x
                         (fn [entry]
                           (if entry
                             (update entry :count inc)
                             {:value x :count 1})))))))))

(defn- double-key
  [x]
  (if (Double/isNaN x)
    ::nan
    (Double/doubleToLongBits x)))

(defn- double-counts
  [^doubles values]
  (let [n (alength values)]
    (loop [idx 0
           counts {}]
      (if (= idx n)
        counts
        (let [x (aget values idx)
              k (double-key x)]
          (recur (inc idx)
                 (update counts k
                         (fn [entry]
                           (if entry
                             (update entry :count inc)
                             {:value x :count 1})))))))))

(defn- wrap-long-flat
  [^longs flat]
  (let [shape [(alength flat)]]
    (->DJLArray :int64
                shape
                (.create ^NDManager (manager) flat (shape-object shape))
                nil
                flat
                nil
                nil)))

(defn- wrap-double-flat
  [^doubles flat]
  (let [shape [(alength flat)]]
    (->DJLArray :float64
                shape
                (.create ^NDManager (manager) flat (shape-object shape))
                nil
                flat
                nil
                nil)))

(defn- repeated-long-array
  [n value]
  (let [xs (long-array n)]
    (Arrays/fill ^longs xs (long value))
    xs))

(defn- repeated-double-array
  [n value]
  (let [xs (double-array n)]
    (Arrays/fill ^doubles xs (double value))
    xs))

(defn- partition-long-by-frequency
  [^longs values]
  (let [n (alength values)
        {:keys [value count]} (most-common-entry (vals (long-counts values)))
        content-count (- n count)]
    (when (and (> count 1)
               (pos? content-count))
      (let [indices (long-array content-count)
            content (long-array content-count)]
        (loop [idx 0
               out 0
               first-content 0
               seen-content? false
               scalar? true]
          (if (= idx n)
            (let [content-value (if scalar?
                                  first-content
                                  (wrap-long-flat content))
                  rest (wrap-long-flat (repeated-long-array count value))]
              [[(wrap-long-flat indices) content-value rest]])
            (let [x (aget values idx)]
              (if (= value x)
                (recur (inc idx)
                       out
                       first-content
                       seen-content?
                       scalar?)
                (do
                  (aset-long indices out idx)
                  (aset-long content out x)
                  (recur (inc idx)
                         (inc out)
                         (if seen-content? first-content x)
                         true
                         (and scalar?
                              (or (not seen-content?)
                                  (= first-content x)))))))))))))

(defn- partition-double-by-frequency
  [^doubles values]
  (let [n (alength values)
        {:keys [value count]} (most-common-entry (vals (double-counts values)))
        rest-key (double-key value)
        content-count (- n count)]
    (when (and (> count 1)
               (pos? content-count))
      (let [indices (long-array content-count)
            content (double-array content-count)]
        (loop [idx 0
               out 0
               first-content 0.0
               first-key nil
               seen-content? false
               scalar? true]
          (if (= idx n)
            (let [content-value (if scalar?
                                  first-content
                                  (wrap-double-flat content))
                  rest (wrap-double-flat (repeated-double-array count value))]
              [[(wrap-long-flat indices) content-value rest]])
            (let [x (aget values idx)
                  k (double-key x)]
              (if (= rest-key k)
                (recur (inc idx)
                       out
                       first-content
                       first-key
                       seen-content?
                       scalar?)
                (do
                  (aset-long indices out idx)
                  (aset-double content out x)
                  (recur (inc idx)
                         (inc out)
                         (if seen-content? first-content x)
                         (if seen-content? first-key k)
                         true
                         (and scalar?
                              (or (not seen-content?)
                                  (= first-key k)))))))))))))

(defn- partition-by-frequency
  [x]
  (case (p/-dtype x)
    :int64 (partition-long-by-frequency (primitive-flat x))
    :float64 (partition-double-by-frequency (primitive-flat x))
    :bool nil))

(defn- djl-array
  [dtype shape array flat]
  (let [flat (vec flat)
        fingerprint nil]
    (->DJLArray dtype shape array flat nil nil fingerprint)))

(defn- wrap
  [^NDArray array]
  (->DJLArray (ciwi-dtype (.getDataType array))
              (shape-vector (.getShape array))
              array
              nil
              nil
              nil
              nil))

(defn from-flat
  [backend flat shape {:keys [dtype]}]
  (let [shape (vec shape)
        flat (vec flat)]
    (when-not (= (count flat) (expected-flat-size shape))
      (throw (ex-info "flat dense data does not match shape"
                      {:shape shape
                       :flat-count (count flat)})))
    (let [{:keys [dtype flat]} (normalize-flat flat dtype)]
      (djl-array dtype
                 shape
                 (create-ndarray dtype shape flat)
                 flat))))

(defn array
  [backend data {:keys [dtype] :as opts}]
  (if (dense-array? data)
    (let [dtype (vector-backend/canonical-dtype dtype)]
      (cond
        (and (instance? DJLArray data)
             (or (nil? dtype)
                 (= dtype (p/-dtype data))))
        data

        dtype
        (from-flat backend (p/-ravel data) (p/-shape data) opts)

        :else
        (from-flat backend (p/-ravel data) (p/-shape data) opts)))
    (let [shape (or (vector-backend/rectangular-shape data)
                    (throw (ex-info "dense arrays must be rectangular"
                                    {:data data})))
          flat (vec (vector-backend/flatten-data data))]
      (from-flat backend flat shape opts))))

(defn- same-flat-content?
  [left right]
  (and (= (count left) (count right))
       (every? (fn [[x y]]
                 (if (and (vector-backend/nan? x)
                          (vector-backend/nan? y))
                   true
                   (= x y)))
               (map vector left right))))

(defn same-content?
  [left right]
  (and (dense-array? right)
       (= (p/-dtype left) (p/-dtype right))
       (= (p/-shape left) (p/-shape right))
       (same-flat-content? (p/-ravel left) (p/-ravel right))))

(extend-type DJLArray
  p/DenseArray
  (-backend [_] :ciwi.djl)
  (-dtype [x] (:dtype x))
  (-shape [x] (:shape x))
  (-ndim [x] (count (:shape x)))
  (-size [x] (expected-flat-size (:shape x)))
  (-ravel [x] (ravel x))
  (-tolist [x]
    (array-data x))
  (-array-info [x]
    {:shape (:shape x)
     :flat (ravel x)
     :kind (case (:dtype x)
             :bool :bool
             :int64 :int
             :float64 :float)
     :size (expected-flat-size (:shape x))
     :data (array-data x)})
  (-content-fingerprint [x]
    (content-fingerprint x))
  (-same-content? [x y]
    (same-content? x y))

  p/DenseArrayDescription
  (-desc-len-data [x opts]
    (desc-len-data x opts))

  p/DenseArrayEdit
  (-partition-by-frequency [x]
    (partition-by-frequency x)))

(defn- as-djl-array
  [backend x]
  (:array (array backend x {})))

(defn- as-djl-array-with-dtype
  [backend dtype x]
  (:array (array backend x {:dtype dtype})))

(defn- result-shape
  [xs]
  (let [arrays (filter array-operand? xs)
        shapes (mapv (fn [x]
                       (if (dense-array? x)
                         (p/-shape x)
                         (vector-backend/rectangular-shape x)))
                     arrays)]
    (if (seq shapes)
      (if (apply = shapes)
        (first shapes)
        (throw (ex-info "dense operands are not broadcast-compatible"
                        {:shapes shapes})))
      [])))

(defn- broadcast-flat
  [x target-shape target-size]
  (cond
    (dense-array? x)
    (if (= target-shape (p/-shape x))
      (p/-ravel x)
      (throw (ex-info "dense operands are not broadcast-compatible"
                      {:left target-shape
                       :right (p/-shape x)})))

    (and (sequential? x) (not (string? x)))
    (let [shape (vector-backend/rectangular-shape x)]
      (if (= target-shape shape)
        (vec (vector-backend/flatten-data x))
        (throw (ex-info "dense operands are not broadcast-compatible"
                        {:left target-shape
                         :right shape}))))

    :else
    (vec (clojure.core/repeat target-size x))))

(defn- elementwise-native
  [backend f xs opts]
  (let [shape (result-shape xs)
        target-size (expected-flat-size shape)
        flats (mapv #(broadcast-flat % shape target-size) xs)
        result-flat (apply mapv f flats)]
    (if (some array-operand? xs)
      (from-flat backend result-flat shape opts)
      (first result-flat))))

(defn- sample
  [f & args]
  (try
    (apply f args)
    (catch Throwable _
      ::failed)))

(defn- numeric=
  [left right]
  (and (number? left)
       (number? right)
       (== (double left) (double right))))

(defn- classify-elementwise
  [f arity opts]
  (case arity
    1
    (cond
      (and (numeric= (sample f 2) -2)
           (numeric= (sample f -2) 2))
      :neg

      (and (= :bool (:dtype opts))
           (true? (sample f Double/NaN))
           (false? (sample f 1.0)))
      :isnan

      :else nil)

    2
    (cond
      (and (numeric= (sample f 2 3) 5)
           (numeric= (sample f -2 3) 1))
      :add

      (and (numeric= (sample f 2 3) -1)
           (numeric= (sample f -2 3) -5))
      :sub

      (and (numeric= (sample f 2 3) 6)
           (numeric= (sample f -2 3) -6))
      :mul

      (and (= :bool (:dtype opts))
           (true? (sample f 1 2))
           (false? (sample f 2 1))
           (false? (sample f 1 1)))
      :lt

      (and (= :bool (:dtype opts))
           (true? (sample f 1 1))
           (false? (sample f 1 2)))
      :eq

      :else nil)

    nil))

(defn- native-number?
  [x]
  (and (number? x) (not (dense-array? x))))

(defn- wrap-array-result
  [^NDArray result]
  (if (scalar? result)
    (scalar-value result)
    (wrap result)))

(defn- binary-array-op
  [backend op x y opts]
  (let [x-array? (array-operand? x)
        y-array? (array-operand? y)]
    (cond
      (and x-array? y-array?)
      (let [x (as-djl-array backend x)
            y (as-djl-array backend y)]
        (wrap (case op
                :add (.add ^NDArray x ^NDArray y)
                :sub (.sub ^NDArray x ^NDArray y)
                :mul (.mul ^NDArray x ^NDArray y)
                :lt (.lt ^NDArray x ^NDArray y)
                :eq (.eq ^NDArray x ^NDArray y))))

      (and x-array? (native-number? y))
      (let [x (as-djl-array backend x)]
        (wrap (case op
                :add (.add ^NDArray x ^Number y)
                :sub (.sub ^NDArray x ^Number y)
                :mul (.mul ^NDArray x ^Number y)
                :lt (.lt ^NDArray x ^Number y)
                :eq (.eq ^NDArray x ^Number y))))

      (and y-array? (native-number? x) (#{:add :mul} op))
      (binary-array-op backend op y x opts)

      :else
      (elementwise-native backend
                          (case op
                            :add +
                            :sub -
                            :mul *
                            :lt (fn [a b]
                                  (if (or (vector-backend/nan? a)
                                          (vector-backend/nan? b))
                                    false
                                    (< a b)))
                            :eq (fn [a b]
                                  (if (or (vector-backend/nan? a)
                                          (vector-backend/nan? b))
                                    false
                                    (= a b))))
                          [x y]
                          opts))))

(defn elementwise
  [backend f xs opts]
  (let [xs (vec xs)
        op (classify-elementwise f (count xs) opts)]
    (case op
      :add (binary-array-op backend :add (first xs) (second xs) opts)
      :sub (binary-array-op backend :sub (first xs) (second xs) opts)
      :mul (binary-array-op backend :mul (first xs) (second xs) opts)
      :lt (binary-array-op backend :lt (first xs) (second xs) opts)
      :eq (binary-array-op backend :eq (first xs) (second xs) opts)
      :neg (if (array-operand? (first xs))
             (wrap (.neg ^NDArray (as-djl-array backend (first xs))))
             (f (first xs)))
      :isnan (if (array-operand? (first xs))
               (wrap (.isNaN ^NDArray (as-djl-array backend (first xs))))
               (f (first xs)))
      (elementwise-native backend f xs opts))))

(defn arange
  [backend start stop step opts]
  (when-not (zero? step)
    (if (every? integer? [start stop step])
      (wrap (.arange ^NDManager (manager)
                     (int start)
                     (int stop)
                     (int step)
                     DataType/INT64))
      (wrap (.arange ^NDManager (manager)
                     (float start)
                     (float stop)
                     (float step)
                     DataType/FLOAT64)))))

(defn tile
  [backend n motif opts]
  (let [motif (as-djl-array backend motif)
        flat (.reshape ^NDArray motif (long-array [(long (.size (.getShape ^NDArray motif)))]))]
    (wrap (.tile ^NDArray flat (long n)))))

(defn- operand-dtype
  [x]
  (if (dense-array? x)
    (p/-dtype x)
    (vector-backend/infer-dtype (vec x))))

(defn- promote-dtypes
  [dtypes]
  (cond
    (some #{:float64} dtypes) :float64
    (some #{:int64} dtypes) :int64
    (some #{:bool} dtypes) :bool
    :else :float64))

(defn concatenate
  [backend xs opts]
  (let [xs (vec xs)
        dtype (or (vector-backend/canonical-dtype (:dtype opts))
                  (promote-dtypes (map operand-dtype xs)))
        arrays (mapv #(as-djl-array-with-dtype backend dtype %) xs)]
    (if-let [first-array (first arrays)]
      (wrap (reduce (fn [acc x]
                      (.concat ^NDArray acc ^NDArray x))
                    first-array
                    (rest arrays)))
      (from-flat backend [] [0] opts))))

(defn take-indices
  [backend x indices opts]
  (let [x (array backend x {})
        values (p/-ravel x)
        indices (if (dense-array? indices) (p/-ravel indices) (vec indices))]
    (from-flat backend
               (mapv #(nth values %) indices)
               [(count indices)]
               opts)))

(defn put
  [backend x indices values opts]
  (let [x (array backend x {})
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
  (wrap (.cumSum ^NDArray (as-djl-array backend x))))

(defn diff
  [backend x opts]
  (let [x (array backend x {})
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

(defn dot
  [backend x y]
  (let [x (as-djl-array-with-dtype backend :float64 x)
        y (as-djl-array-with-dtype backend :float64 y)
        x-rank (.dimension (.getShape ^NDArray x))
        y-rank (.dimension (.getShape ^NDArray y))
        result (if (and (= 1 x-rank) (= 1 y-rank))
                 (.dot ^NDArray x ^NDArray y)
                 (.matMul ^NDArray x ^NDArray y))]
    (wrap-array-result result)))

(defn sum
  [backend x axis]
  (let [x (as-djl-array-with-dtype backend :float64 x)
        result (if (nil? axis)
                 (.sum ^NDArray x)
                 (.sum ^NDArray x (int-array [(int axis)])))]
    (wrap-array-result result)))

(defrecord DJLBackend []
  p/DenseBackend
  (-backend-id [_]
    :ciwi.djl)
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
    (arange backend start stop step opts))
  (-tile [backend n motif opts]
    (tile backend n motif opts))
  (-concatenate [backend xs opts]
    (concatenate backend xs opts))
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
  (->DJLBackend))
