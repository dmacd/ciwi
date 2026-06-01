(ns ciwi.search
  (:require [ciwi.graph :as graph]
            [ciwi.library :as library]
            [ciwi.mdl :as mdl]
            [ciwi.rewrite :as rewrite])
  (:import [java.util.concurrent Callable Executors TimeUnit]))

(defn- value-work-items
  [g node-ids]
  (->> node-ids
       distinct
       (filter #(graph/value-node? (graph/node g %)))
       vec))

(defn- parallel-mapv
  [f xs]
  (let [xs (vec xs)]
    (if (< (count xs) 2)
      (mapv f xs)
      (let [n-threads (max 1 (min (count xs)
                                  (.availableProcessors (Runtime/getRuntime))))
            pool (Executors/newFixedThreadPool n-threads)
            tasks (mapv (fn [x]
                          (reify Callable
                            (call [_]
                              (f x))))
                        xs)]
        (try
          (mapv #(.get %) (.invokeAll pool tasks))
          (finally
            (.shutdown pool)
            (.awaitTermination pool 5 TimeUnit/SECONDS)))))))

(defn candidates
  [g node-ids {:keys [parallel?]
               :or {parallel? true}
               :as opts}]
  (let [opts (cond-> opts
               (:composite-templates? opts)
               (-> (update :extra-templates into (library/builtin-templates))
                   (dissoc :composite-templates?)))
        items (value-work-items g node-ids)
        batches (if parallel?
                  (parallel-mapv #(rewrite/candidates-for-node g % opts) items)
                  (mapv #(rewrite/candidates-for-node g % opts) items))]
    (->> batches
         (apply concat)
         (sort-by (juxt :after :delta (comp str :node-id) (comp str :reason)))
         vec)))

(defn best-candidate
  [g node-ids opts]
  (first (candidates g node-ids opts)))

(defn exhaustive-step
  [g opts]
  (when-let [c (best-candidate g (graph/value-ids g) opts)]
    {:candidate c
     :graph (rewrite/apply-candidate g c)}))

(defn bounded-step
  [g target-ids {:keys [re-eval-budget] :as opts
                 :or {re-eval-budget 32}}]
  (let [node-ids (mapcat #(graph/neighborhood g % re-eval-budget) target-ids)]
    (when-let [c (best-candidate g node-ids opts)]
      {:candidate c
       :graph (rewrite/apply-candidate g c)})))

(defn converge
  [g step-fn {:keys [max-steps]
              :or {max-steps 100}
              :as opts}]
  (loop [current g
         history []
         n 0]
    (if (>= n max-steps)
      {:graph current
       :history history
       :dl (mdl/graph-dl current)
       :stopped :max-steps}
      (if-let [{:keys [candidate graph]} (step-fn current opts)]
        (recur graph (conj history candidate) (inc n))
        {:graph current
         :history history
         :dl (mdl/graph-dl current)
         :stopped :fixed-point}))))

(defn exhaustive-converge
  [g opts]
  (converge g exhaustive-step opts))

(defn bounded-converge
  [g target-ids opts]
  (converge g #(bounded-step % target-ids %2) opts))
