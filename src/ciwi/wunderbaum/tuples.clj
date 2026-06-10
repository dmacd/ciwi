(ns ciwi.wunderbaum.tuples
  (:require [ciwi.graph :as graph]
            [ciwi.value :as value]))

(defn- graph-value-order
  ([g]
   (graph-value-order g (graph/roots g)))
  ([g root-order]
   (let [root-order (vec (concat (filter #(graph/node g %) root-order)
                                  (remove (set root-order) (graph/roots g))))]
     (loop [roots root-order
            seen #{}
            result []]
       (if-let [root-id (first roots)]
         (let [ids (graph/breadth-first-walk g root-id {:above? false
                                                        :below? true
                                                        :values? true
                                                        :operators? false})
               new-ids (remove seen ids)]
           (recur (rest roots)
                  (into seen new-ids)
                  (into result new-ids)))
         result)))))

(defn- node-index-dl
  [idx]
  (value/elias-discrete (inc idx)))

(defn- tuple-item
  [ids indices]
  {:dl (reduce + 0.0 (map node-index-dl indices))
   :indices (vec indices)
   :nodes (mapv ids indices)})

(defn- tuple-rank
  [item]
  [(:dl item) (:indices item)])

(defn- tuple-queue
  []
  (sorted-set-by (fn [left right]
                   (compare (tuple-rank left)
                            (tuple-rank right)))))

(defn- pop-tuple
  [queue]
  (let [item (first queue)]
    [item (disj queue item)]))

(defn- starting-tuples
  [ids max-tuple-len]
  (for [n (range 1 (inc max-tuple-len))]
    (tuple-item ids (vec (repeat n 0)))))

(defn- next-tuple-indices
  [id-count indices]
  (for [position (range (count indices))
        :let [next-index (inc (nth indices position))]
        :when (< next-index id-count)]
    (assoc indices position next-index)))

(defn- enqueue-next-tuples
  [ids queue seen indices]
  (reduce (fn [[queue seen] next-indices]
            (if (contains? seen next-indices)
              [queue seen]
              [(conj queue (tuple-item ids next-indices))
               (conj seen next-indices)]))
          [queue seen]
          (next-tuple-indices (count ids) indices)))

(defn node-tuples
  "Enumerate graph value-node tuples in Python NodeTupleEnumerator order."
  [g {:keys [max-tuple-len max-results]
      :or {max-tuple-len 2
           max-results 1000}
      :as opts}]
  (let [ids (vec (graph-value-order g (:root-order opts)))]
    (if (empty? ids)
      []
      (loop [queue (into (tuple-queue) (starting-tuples ids max-tuple-len))
             seen (set (map :indices queue))
             result []]
        (if (or (empty? queue)
                (>= (count result) max-results))
          result
          (let [[item queue] (pop-tuple queue)
                [queue seen] (enqueue-next-tuples ids queue seen (:indices item))]
            (recur queue seen (conj result item))))))))
