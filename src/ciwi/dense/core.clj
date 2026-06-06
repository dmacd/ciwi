(ns ciwi.dense.core
  (:refer-clojure :exclude [array compare concat repeat take])
  (:require [ciwi.dense.protocols :as p]
            [ciwi.dense.vector :as vector-backend]))

(defonce ^:private backends
  (atom {:ciwi.vector vector-backend/backend}))

(defonce ^:private default-backend-id
  (atom :ciwi.vector))

(declare asarray)

(defn ndarray?
  [x]
  (satisfies? p/DenseArray x))

(defn backend
  [x]
  (p/-backend x))

(defn dtype
  [x]
  (p/-dtype x))

(defn shape
  [x]
  (p/-shape x))

(defn ndim
  [x]
  (p/-ndim x))

(defn size
  [x]
  (p/-size x))

(defn ravel
  [x]
  (p/-ravel x))

(defn tolist
  [x]
  (p/-tolist x))

(defn array-info
  [x]
  (p/-array-info (asarray x)))

(defn content-fingerprint
  "Return a backend-provided deterministic fingerprint for dense content."
  [x]
  (p/-content-fingerprint (asarray x)))

(defn same-content?
  "Exact dense-content equality with backend-normalized missing-value semantics."
  [left right]
  (and (ndarray? left)
       (ndarray? right)
       (p/-same-content? left right)))

(defn nan?
  [x]
  (vector-backend/nan? x))

(defn missing?
  [x]
  (vector-backend/missing? x))

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

(defn array-literal?
  "Return true when native data should be treated as a dense numeric graph array.

  CIWI only auto-promotes rectangular Clojure vectors with numeric, boolean, or
  missing leaves. Lists remain structural data, and empty vectors stay native
  unless a caller explicitly constructs `(array [])`.
  "
  [x]
  (when (vector? x)
    (when-let [_shape (rectangular-shape x)]
      (let [flat (vec (flatten-data x))]
        (and (seq flat)
             (or (every? #(or (true? %) (false? %)) flat)
                 (every? #(or (missing? %) (number? %)) flat)))))))

(defn register-backend!
  "Register a dense backend implementation by backend id."
  [backend]
  (swap! backends assoc (p/-backend-id backend) backend)
  backend)

(defn set-default-backend!
  "Set the process-local default dense backend by id or backend value."
  [backend]
  (let [backend-id (if (satisfies? p/DenseBackend backend)
                     (p/-backend-id backend)
                     backend)]
    (when-not (contains? @backends backend-id)
      (throw (ex-info "Unknown dense backend" {:backend backend-id})))
    (reset! default-backend-id backend-id)
    backend-id))

(defn default-backend
  []
  (get @backends @default-backend-id))

(defn- backend-instance
  [backend]
  (cond
    (nil? backend)
    (default-backend)

    (satisfies? p/DenseBackend backend)
    backend

    :else
    (or (get @backends backend)
        (throw (ex-info "Unknown dense backend" {:backend backend})))))

(defn- backend-for
  [x opts]
  (backend-instance (or (:backend opts)
                        (when (ndarray? x) (backend x)))))

(defn array
  "Construct a dense array through the selected backend."
  ([data]
   (array data {}))
  ([data opts]
   (p/-array (backend-for data opts) data opts)))

(defn asarray
  ([x]
   (if (ndarray? x) x (array x)))
  ([x opts]
   (array x opts)))

(defn from-flat
  "Construct a dense array from flat data and a known shape."
  ([flat shape]
   (from-flat flat shape {}))
  ([flat shape opts]
   (p/-from-flat (backend-instance (:backend opts)) flat shape opts)))

(defn array-like
  "Construct a dense array with the backend and shape of `template`."
  ([template flat]
   (array-like template flat {}))
  ([template flat opts]
   (p/-array-like (backend-for template opts) template flat opts)))

(defn with-flat
  "Construct a dense array by replacing `template`'s flat data."
  ([template flat]
   (array-like template flat))
  ([template flat opts]
   (array-like template flat opts)))

(defn arange
  ([stop]
   (arange 0 stop 1))
  ([start stop]
   (arange start stop 1))
  ([start stop step]
   (arange start stop step {}))
  ([start stop step opts]
   (p/-arange (backend-instance (:backend opts)) start stop step opts)))

(defn tile
  "Repeat a dense motif `n` times along a flat 1D axis."
  ([n motif]
   (tile n motif {}))
  ([n motif opts]
   (p/-tile (backend-for motif opts) n motif opts)))

(defn concatenate
  "Concatenate dense or native 1D arrays."
  ([xs]
   (concatenate xs {}))
  ([xs opts]
   (let [template (first (filter ndarray? xs))]
     (p/-concatenate (backend-for template opts) xs opts))))

(defn- elementwise
  [f xs opts]
  (let [template (first (filter ndarray? xs))]
    (p/-elementwise (backend-for template opts) f xs opts)))

(defn add
  [x y]
  (elementwise + [x y] {}))

(defn subtract
  [x y]
  (elementwise - [x y] {}))

(defn multiply
  [x y]
  (elementwise * [x y] {}))

(defn divide
  [x y]
  (elementwise / [x y] {}))

(defn negative
  [x]
  (elementwise - [x] {}))

(defn- less-scalar
  [x y]
  (if (or (nan? x) (nan? y))
    false
    (< x y)))

(defn less
  [x y]
  (elementwise less-scalar [x y] {:dtype :bool}))

(defn- equal-scalar
  [x y]
  (if (or (nan? x) (nan? y))
    false
    (= x y)))

(defn equal
  [x y]
  (elementwise equal-scalar [x y] {:dtype :bool}))

(defn isnan
  [x]
  (elementwise nan? [x] {:dtype :bool}))

(defn dot
  [x y]
  (p/-dot (backend-for x {}) x y))

(defn sum
  ([x]
   (sum x nil))
  ([x axis]
   (p/-sum (backend-for x {}) x axis)))

(defn take-indices
  ([x indices]
   (take-indices x indices {}))
  ([x indices opts]
   (p/-take-indices (backend-for x opts) x indices opts)))

(defn put
  ([x indices values]
   (put x indices values {}))
  ([x indices values opts]
   (p/-put (backend-for x opts) x indices values opts)))

(defn cumsum
  ([x]
   (cumsum x {}))
  ([x opts]
   (p/-cumsum (backend-for x opts) x opts)))

(defn diff
  ([x]
   (diff x {}))
  ([x opts]
   (p/-diff (backend-for x opts) x opts)))
