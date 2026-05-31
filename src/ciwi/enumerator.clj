(ns ciwi.enumerator
  (:require [ciwi.graph :as graph]
            [ciwi.value :as value]))

(defrecord BufferedIterator [state])

(defn buffered-iterator
  [xs]
  (->BufferedIterator {:xs (vec xs)}))

(defn nth-buffered
  [buffered idx]
  (let [xs (get-in buffered [:state :xs])]
    (when (or (neg? idx) (>= idx (count xs)))
      (throw (ex-info "Buffered iterator index out of range" {:idx idx})))
    (nth xs idx)))

(defn int-values
  [free-values]
  (concat (filter #(integer? (:data %)) free-values)
          [(value/value 0)]
          (map #(value/value (long (Math/pow 2 %))) (range 5))))

(defn float-values
  [free-values]
  (concat (filter #(float? (:data %)) free-values)
          [(value/value 0.0)]))

(defn generator-for
  [spec free-values]
  (case spec
    :int (vec (int-values (get free-values :int [])))
    :float (vec (float-values (get free-values :float [])))
    (vec (get free-values spec []))))

(defn- index-cost
  [spec free-count idx]
  (if (< idx free-count)
    0.0
    (let [offset (- idx free-count)]
      (case spec
        :float 1.0
        :int (if (zero? offset)
               1.0
               (double (+ 2 (quot (inc offset) 2))))
        (double (inc offset))))))

(defn tuple-dl
  ([values]
   (reduce + 0.0 (map value/desc-len values)))
  ([specs free-values indices]
   (reduce + 0.0
           (map (fn [spec idx]
                  (index-cost spec (count (get free-values spec [])) idx))
                specs
                indices))))

(defn- tuple-at
  [gens indices]
  (mapv (fn [gen idx]
          (when (>= idx (count gen))
            (throw (ex-info "Generator exhausted" {:idx idx})))
          (nth gen idx))
        gens
        indices))

(defn- heap-set
  []
  (sorted-set-by (fn [a b]
                   (compare [(:dl a) (:order a)]
                            [(:dl b) (:order b)]))))

(defn input-tuples
  "Enumerate input value tuples in increasing prototype DL.

  `specs` are simple keywords for now: `:int`, `:float`, or custom keys looked
  up in `free-values`.
  "
  ([specs]
   (input-tuples specs {}))
  ([specs {:keys [free-values max-results max-heap-size]
           :or {free-values {}
                max-results 100
                max-heap-size 10000}}]
   (let [specs (vec specs)
         gens (mapv #(generator-for % free-values) specs)
         order (atom 0)
         make-item (fn [indices]
                     (let [values (tuple-at gens indices)]
                       {:dl (tuple-dl specs free-values indices)
                        :order (swap! order inc)
                        :indices indices
                        :values values}))
         start (vec (repeat (count gens) 0))]
     (loop [heap (conj (heap-set) (make-item start))
            queued #{start}
            emitted-values #{}
            emitted []]
       (cond
         (or (empty? heap) (>= (count emitted) max-results))
         emitted

         (>= (count heap) max-heap-size)
         emitted

         :else
         (let [item (first heap)
               heap (disj heap item)
               value-key (mapv :data (:values item))
               [emitted-values emitted] (if (contains? emitted-values value-key)
                                          [emitted-values emitted]
                                          [(conj emitted-values value-key)
                                           (conj emitted item)])
               next-items
               (for [k (range (count (:indices item)))
                     :let [idxs (update (:indices item) k inc)]
                     :when (not (contains? queued idxs))]
                 (try
                   (make-item idxs)
                   (catch Exception _ nil)))
               next-items (vec (remove nil? next-items))]
           (recur (into heap next-items)
                  (into queued (map :indices next-items))
                  emitted-values
                  emitted)))))))


(defn- node-index-dl
  [idx]
  (value/elias-discrete (inc idx)))

(defn node-tuples
  "Enumerate graph value-node tuples by breadth-first node index cost."
  [g root-id {:keys [max-tuple-len max-results max-heap-size]
              :or {max-tuple-len 3
                   max-results 100
                   max-heap-size 10000}}]
  (let [ids (graph/breadth-first-walk g root-id {:above? false
                                                :below? true
                                                :values? true
                                                :operators? false})
        gens (fn [n] (vec (repeat n ids)))
        order (atom 0)
        make-item (fn [indices]
                    {:dl (reduce + 0.0 (map node-index-dl indices))
                     :order (swap! order inc)
                     :indices indices
                     :nodes (mapv ids indices)})
        starts (for [n (range 1 (inc max-tuple-len))]
                 (vec (repeat n 0)))]
    (loop [heap (into (heap-set) (map make-item starts))
           queued (set starts)
           emitted []]
      (cond
        (or (empty? heap) (>= (count emitted) max-results))
        emitted

        (>= (count heap) max-heap-size)
        emitted

        :else
        (let [item (first heap)
              heap (disj heap item)
              emitted (conj emitted item)
              next-items
              (for [k (range (count (:indices item)))
                    :let [idxs (update (:indices item) k inc)]
                    :when (and (< (nth idxs k) (count ids))
                               (not (contains? queued idxs)))]
                (make-item idxs))]
          (recur (into heap next-items)
                 (into queued (map :indices next-items))
                 emitted))))))
