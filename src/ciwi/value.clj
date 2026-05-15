(ns ciwi.value)

(defrecord Value [data name spec permeable? dummy?])

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

(defn desc-len
  "Small placeholder for WILLIAM's description length.

  The Python implementation has a richer, type-aware description model. This
  prototype keeps a deterministic stand-in so graph shape and propagation can be
  tested before porting the full coding scheme.
  "
  [v]
  (if (:dummy? v)
    0.0
    (double (count (pr-str (:data v))))))
