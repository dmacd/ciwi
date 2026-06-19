(ns ciwi.wunderbaum.lazy-frontier
  (:require [ciwi.delayed-builder :as delayed]
            [ciwi.graph :as graph]
            [ciwi.wunderbaum.attachment :as attachment]
            [ciwi.wunderbaum.tuples :as tuples]))

(def ^:private cursor-kind ::cursor)

(defn cursor?
  [item]
  (= cursor-kind (:kind item)))

(defn- item-rank
  [item]
  [(:dl item) (:order item)])

(defn- rank<=
  [left right]
  (not (pos? (compare left right))))

(defn- candidate-queue
  []
  (sorted-set-by (fn [left right]
                   (compare (item-rank left)
                            (item-rank right)))))

(defn- min-element-dl
  [elements-by-condition-key]
  (let [dl (reduce min
                   Double/POSITIVE_INFINITY
                   (map :dl
                        (mapcat val elements-by-condition-key)))]
    (when (< dl Double/POSITIVE_INFINITY)
      dl)))

(defn- tuple-root-order
  [g {:keys [primary-root-id root-order free-root-ids recent-roots-first?]}]
  (if recent-roots-first?
    (let [fixed-roots (vec (distinct
                            (concat (when primary-root-id
                                      [primary-root-id])
                                    free-root-ids)))
          fixed-root-set (set fixed-roots)]
      (vec (concat fixed-roots
                   (remove fixed-root-set
                           (reverse (graph/roots g))))))
    root-order))

(defn- descriptor
  [dl order element condition-key conditioned-nodes element-index]
  {:dl dl
   :order order
   :operator-id (:id element)
   :condition-key condition-key
   :gen-cond (:gen-cond element)
   :input-specs (:input-specs element)
   :output-spec (:output-spec element)
   :conditioned-nodes (vec conditioned-nodes)
   :element-index element-index})

(defn- descriptor->build-item
  [{:keys [graph memory]} {:keys [dl conditioned-nodes condition-key
                                  element-index]
                           :as descriptor}]
  (assoc descriptor
         :build-info
         (delayed/build-info
          {:dl dl
           :graph graph
           :memory memory
           :conditioned-nodes conditioned-nodes
           :condition-key condition-key
           :element-index element-index})))

(defn- unscanned-lower-rank
  [{:keys [parent-dl min-element-dl block-order next-local-order
           scan-complete?]}]
  (when (and min-element-dl (not scan-complete?))
    [(+ (double parent-dl) (double min-element-dl))
     [block-order next-local-order]]))

(defn- expose-rank
  [cursor descriptor]
  (assoc cursor
         :dl (:dl descriptor)
         :order (:order descriptor)))

(defn- next-local-order
  [{:keys [next-local-order] :as cursor}]
  [[(:block-order cursor) next-local-order]
   (assoc cursor :next-local-order (inc (long next-local-order)))])

(defn- consider-element
  [{:keys [graph memory parent-dl opts max-dag-dl attachment-context
           callbacks candidates]
    :as cursor}
   nodes condition-key element-index element]
  (let [{:keys [record-frontier-decision! keep-frontier-item?]} callbacks
        new-dl (+ (double parent-dl) (double (:dl element)))
        decision (cond
                   (> new-dl max-dag-dl)
                   :max-dag-dl

                   (attachment/invalid? graph
                                        (:gen-cond element)
                                        nodes
                                        attachment-context)
                   :invalid-attachment

                   (not (keep-frontier-item?
                         opts
                         {:graph graph
                          :memory memory
                          :nodes nodes
                          :condition-key condition-key
                          :element element
                          :dl new-dl}))
                   :frontier-predicate

                   :else
                   :enqueued)]
    (record-frontier-decision! opts element condition-key decision)
    (if (not= :enqueued decision)
      cursor
      (let [[order cursor] (next-local-order cursor)
            candidate (descriptor new-dl
                                  order
                                  element
                                  condition-key
                                  nodes
                                  element-index)]
        (assoc cursor :candidates (conj candidates candidate))))))

(defn- scan-tuple
  [{:keys [graph elements-by-condition-key callbacks] :as cursor} tuple]
  (let [nodes (:nodes tuple)
        condition-key ((:node-condition-key callbacks) graph nodes)
        elements (get elements-by-condition-key condition-key)]
    (reduce (fn [cursor [element-index element]]
              (consider-element cursor
                                nodes
                                condition-key
                                element-index
                                element))
            cursor
            (map-indexed vector elements))))

(defn- scan-one
  [{:keys [tuple-cursor] :as cursor}]
  (if-let [[tuple tuple-cursor] (tuples/next-tuple tuple-cursor)]
    (scan-tuple (assoc cursor :tuple-cursor tuple-cursor) tuple)
    (assoc cursor :scan-complete? true)))

(defn- expose-next
  [cursor]
  (loop [cursor cursor]
    (let [best (first (:candidates cursor))
          lower-rank (unscanned-lower-rank cursor)]
      (cond
        (and best (or (nil? lower-rank)
                      (rank<= (item-rank best) lower-rank)))
        (expose-rank cursor best)

        (:scan-complete? cursor)
        (when best
          (expose-rank cursor best))

        :else
        (recur (scan-one cursor))))))

(defn- new-cursor
  [wb graph memory dl opts block-order callbacks]
  (let [preferred-nodes (when-let [preferred-node-fn (:preferred-node-fn opts)]
                          (preferred-node-fn {:graph graph
                                              :memory memory
                                              :opts opts}))
        tuple-cursor (tuples/tuple-cursor
                      graph
                      {:max-tuple-len (:max-tuple-len opts 2)
                       :max-results (:max-node-tuples opts 1000)
                       :root-order (tuple-root-order graph opts)
                       :preferred-nodes preferred-nodes})]
    {:kind cursor-kind
     :graph graph
     :memory memory
     :parent-dl dl
     :opts opts
     :max-dag-dl (:max-dag-dl opts Double/POSITIVE_INFINITY)
     :attachment-context (attachment/context graph
                                             (:primary-root-id opts)
                                             (:free-root-ids opts)
                                             opts)
     :elements-by-condition-key (:elements-by-condition-key wb)
     :min-element-dl (min-element-dl (:elements-by-condition-key wb))
     :callbacks callbacks
     :tuple-cursor tuple-cursor
     :candidates (candidate-queue)
     :block-order block-order
     :next-local-order 1
     :scan-complete? false}))

(defn expand-graph
  "Enqueue a lazy expansion cursor for `graph` when it has a next child."
  [wb queue graph memory dl opts order callbacks]
  (let [[block-order order] ((:next-frontier-order callbacks) opts order)
        cursor (expose-next (new-cursor wb
                                        graph
                                        memory
                                        dl
                                        opts
                                        block-order
                                        callbacks))]
    [(cond-> queue cursor (conj cursor)) order]))

(defn pop-cursor
  "Return `[build-item next-cursor]` for a lazy expansion cursor."
  [cursor]
  (when-let [cursor (expose-next cursor)]
    (let [candidate (first (:candidates cursor))
          cursor (assoc cursor :candidates (disj (:candidates cursor)
                                                 candidate))
          build-item (descriptor->build-item cursor candidate)
          next-cursor (expose-next cursor)]
      [build-item next-cursor])))
