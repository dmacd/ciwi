(ns ciwi.hashing
  (:import [java.math BigInteger]
           [java.security MessageDigest]
           [java.util IdentityHashMap]))

(defn- container?
  [x]
  (or (record? x)
      (map? x)
      (set? x)
      (vector? x)
      (and (sequential? x)
           (not (string? x)))))

(defn- mark-seen!
  [^IdentityHashMap seen x]
  (.put seen x true))

(defn- unmark-seen!
  [^IdentityHashMap seen x]
  (.remove seen x))

(defn- seen?
  [^IdentityHashMap seen x]
  (.containsKey seen x))

(defn- floating-key
  [x]
  (let [x (double x)]
    (if (Double/isNaN x)
      [true "NaN"]
      [false x])))

(defn- scalar-key
  [x]
  (cond
    (nil? x) [0 0 nil nil]
    (or (true? x) (false? x)) [0 1 (if x 1 0) nil]
    (integer? x) [0 2 (bigint x) nil]
    (number? x) [0 3 (floating-key x) nil]
    (string? x) [0 4 x nil]
    (keyword? x) [0 15 [(namespace x) (name x)] nil]
    (symbol? x) [0 16 [(namespace x) (name x)] nil]
    (char? x) [0 17 (str x) nil]
    (class? x) [0 18 (.getName ^Class x) nil]
    :else nil))

(declare compare-keys)

(defn- compare-sequential-keys
  [left right]
  (loop [xs (seq left)
         ys (seq right)]
    (cond
      (and (nil? xs) (nil? ys)) 0
      (nil? xs) -1
      (nil? ys) 1
      :else (let [c (compare-keys (first xs) (first ys))]
              (if (zero? c)
                (recur (next xs) (next ys))
                c)))))

(defn- compare-keys
  [left right]
  (if (and (vector? left) (vector? right))
    (compare-sequential-keys left right)
    (compare left right)))

(defn stable-key
  "Return a deterministic, comparable key for native Clojure data.

  The key is type-aware, recursively normalizes unordered collections, and gives
  scalars precedence over collections. It intentionally mirrors the useful part
  of Python WILLIAM's stable hashing support without carrying over Python's
  object-specific branches.
  "
  ([x]
   (stable-key x (IdentityHashMap.)))
  ([x seen]
   (if-let [k (scalar-key x)]
     k
     (if (container? x)
       (if (seen? seen x)
         [-1 (System/identityHashCode x) nil nil]
         (do
           (mark-seen! seen x)
           (try
             (cond
               (record? x)
               [1 [(stable-key (.getName (class x)) seen)
                   (stable-key (into {} x) seen)]
                98 nil]

               (map? x)
               (let [entries (->> x
                                  (sort (fn [[left _] [right _]]
                                          (compare-keys (stable-key left seen)
                                                        (stable-key right seen))))
                                  (mapcat (fn [[k v]]
                                            [(stable-key k seen)
                                             (stable-key v seen)]))
                                  vec)]
                 [1 entries 10 nil])

               (set? x)
               [1 (vec (sort compare-keys (map #(stable-key % seen) x))) 8 nil]

               (vector? x)
               [1 (mapv #(stable-key % seen) x) 7 nil]

               (sequential? x)
               [1 (mapv #(stable-key % seen) x) 6 nil])
             (finally
               (unmark-seen! seen x)))))
       [0 99 [(stable-key (.getName (class x)) seen)
              (pr-str x)]
       nil]))))

(defn stable-compare
  "Compare native values by their stable keys."
  [left right]
  (compare-keys (stable-key left) (stable-key right)))

(defn sort-anything
  "Sort native values using stable-key."
  [xs]
  (vec (sort stable-compare xs)))

(defn unique-hash
  "Return a positive deterministic 64-bit-ish hash as a BigInteger.

  The hash is derived from the stable key, so map/set iteration order does not
  affect it and type distinctions such as `1` vs `\"1\"` remain visible.
  "
  [x]
  (let [digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes (pr-str (stable-key x)) "UTF-8")))
        bytes (.digest digest)
        first-eight (byte-array 8)]
    (System/arraycopy bytes 0 first-eight 0 8)
    (let [n (BigInteger. 1 first-eight)]
      (if (zero? n)
        1N
        n))))
