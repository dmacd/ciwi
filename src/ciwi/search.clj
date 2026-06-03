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

(defn- sum-resource
  [results k]
  (reduce + 0 (map #(get-in % [:resource k] 0) results)))

(defn- trace-resource-sum
  [trace k]
  (reduce +
          0
          (keep (fn [entry]
                  (let [v (get-in entry [:resource k])]
                    (when (number? v) v)))
                trace)))

(defn rewrite-search
  "Run local rewrite proposal over value nodes and return candidates plus metadata."
  [g node-ids {:keys [parallel?]
               :or {parallel? true}
               :as opts}]
  (let [requested-node-ids (vec node-ids)
        items (value-work-items g requested-node-ids)
        opts (cond-> (assoc opts :local-node-ids (or (:local-node-ids opts) items))
               (:composite-templates? opts)
               (-> (update :extra-templates into (library/builtin-templates))
                   (dissoc :composite-templates?)))
        node-results (if parallel?
                       (parallel-mapv #(rewrite/proposal-for-node g % opts) items)
                       (mapv #(rewrite/proposal-for-node g % opts) items))
        candidates (->> node-results
                        (mapcat :candidates)
                        (sort-by (juxt :after :delta (comp str :node-id) (comp str :reason)))
                        vec)
        trace (vec (mapcat :trace node-results))]
    {:node-ids items
     :requested-node-ids requested-node-ids
     :candidates candidates
     :resource {:parallel? parallel?
                :nodes-requested (count requested-node-ids)
                :nodes-considered (sum-resource node-results :nodes-considered)
                :local-node-count (count (:local-node-ids opts))
                :templates-considered (sum-resource node-results :templates-considered)
                :candidates-proposed (sum-resource node-results :candidates-proposed)
                :candidates-accepted (count candidates)
                :candidates-rejected (sum-resource node-results :candidates-rejected)
                :generated-expressions (trace-resource-sum trace :generated-expressions)}
     :trace trace}))

(defn- step-from-search
  [g mode search-result resource-extra]
  (let [candidate (first (:candidates search-result))]
    (cond-> {:candidate candidate
             :search search-result
             :resource (merge (:resource search-result)
                              {:mode mode
                               :applied? (boolean candidate)}
                              resource-extra)
             :trace (:trace search-result)}
      candidate (assoc :graph (rewrite/apply-candidate g candidate)))))

(defn exhaustive-step
  [g opts]
  (step-from-search g :exhaustive (rewrite-search g (graph/value-ids g) opts) {}))

(defn bounded-step
  [g target-ids {:keys [re-eval-budget] :as opts
                 :or {re-eval-budget 32}}]
  (let [neighborhoods (mapv (fn [target-id]
                              {:target-id target-id
                               :node-ids (vec (graph/neighborhood g target-id re-eval-budget))})
                            target-ids)
        node-ids (->> neighborhoods
                      (mapcat :node-ids)
                      distinct
                      vec)
        search-result (rewrite-search g node-ids opts)
        resource-extra {:target-count (count target-ids)
                        :re-eval-budget re-eval-budget
                        :neighborhood-count (count neighborhoods)
                        :neighborhood-node-visits (reduce + 0 (map (comp count :node-ids)
                                                                   neighborhoods))
                        :candidate-node-count (count node-ids)}
        step (step-from-search g :bounded search-result resource-extra)]
    (-> step
        (assoc :neighborhoods neighborhoods)
        (update :trace into [{:kind :bounded-neighborhoods
                              :target-ids (vec target-ids)
                              :re-eval-budget re-eval-budget
                              :neighborhoods neighborhoods}]))))

(def ^:private aggregate-resource-keys
  [:nodes-requested
   :nodes-considered
   :local-node-count
   :templates-considered
   :candidates-proposed
   :candidates-accepted
   :candidates-rejected
   :generated-expressions
   :neighborhood-node-visits])

(defn- aggregate-resources
  [resources]
  (into {}
        (for [k aggregate-resource-keys]
          [k (reduce + 0 (map #(get % k 0) resources))])))

(defn- convergence-resource
  [steps terminal-step stopped]
  (let [resources (cond-> (mapv :resource steps)
                    terminal-step (conj (:resource terminal-step)))]
    (assoc (aggregate-resources resources)
           :stopped stopped
           :applied-steps (count steps)
           :searches-run (count resources))))

(defn- applied-step
  [n current {:keys [candidate graph] :as step}]
  {:step n
   :candidate candidate
   :search (:search step)
   :resource (:resource step)
   :trace (:trace step)
   :neighborhoods (:neighborhoods step)
   :dl-before (mdl/graph-dl current)
   :dl-after (mdl/graph-dl graph)})

(defn- result
  [current history steps stopped terminal-step]
  (cond-> {:graph current
           :history history
           :steps steps
           :trace (vec (mapcat :trace steps))
           :resource (convergence-resource steps terminal-step stopped)
           :dl (mdl/graph-dl current)
           :stopped stopped}
    terminal-step (assoc :terminal-search (:search terminal-step)
                         :terminal-resource (:resource terminal-step))))

(defn converge
  [g step-fn {:keys [max-steps]
              :or {max-steps 100}
              :as opts}]
  (loop [current g
         history []
         steps []
         n 0]
    (if (>= n max-steps)
      (result current history steps :max-steps nil)
      (let [step (step-fn current opts)]
        (if-let [candidate (:candidate step)]
          (recur (:graph step)
                 (conj history candidate)
                 (conj steps (applied-step n current step))
                 (inc n))
          (result current history steps :fixed-point step))))))

(defn exhaustive-converge
  [g opts]
  (converge g exhaustive-step opts))

(defn bounded-converge
  [g target-ids opts]
  (converge g #(bounded-step % target-ids %2) opts))
