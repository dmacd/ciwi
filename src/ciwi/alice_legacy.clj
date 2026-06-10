(ns ciwi.alice-legacy
  "Legacy local Alice harness over `ciwi.compress`.

  This namespace is not the active Python Alice parity path. It is retained as
  a small baseline for running Alice-shaped task records through CIWI's local
  exhaustive/bounded compression loops with no default recognizers installed.
  That baseline is useful for proving the local compression API does not create
  shortcut parity evidence accidentally. The active parity runner is
  `ciwi.alice.wunderbaum/run-greedy-task`."
  (:require [ciwi.alice :as alice]
            [ciwi.compress :as compress]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]))

(defn- indexed-id
  [prefix idx]
  (keyword (str (name prefix) idx)))

(defn task-graph
  "Build the legacy local-compression graph for an Alice task."
  [task]
  (let [target-ids (mapv #(indexed-id :target %) (range (count (:targets task))))
        free-ids (mapv #(indexed-id :free %) (range (count (:free-values task))))
        value-ids (vec (concat target-ids free-ids))
        g (reduce (fn [g [id value]]
                    (graph/add-value g id value))
                  (graph/empty-graph)
                  (concat (map vector target-ids (:targets task))
                          (map vector free-ids (:free-values task))))
        g (graph/set-roots g value-ids)]
    {:graph g
     :target-ids target-ids
     :free-ids free-ids
     :value-ids value-ids}))

(defn- selected-targets
  [g target-ids]
  (into {}
        (map (fn [target-id]
               [target-id (mdl/selected-expression g target-id)]))
        target-ids))

(defn- result-summary
  [task mode target-ids free-ids initial-dl result]
  (let [rate (alice/compression-rate initial-dl (:dl result))]
    {:task-name (:name task)
     :mode mode
     :target-ids target-ids
     :free-ids free-ids
     :initial-dl initial-dl
     :dl (:dl result)
     :compression-rate rate
     :meets-threshold? (>= rate (double (:threshold-rate task)))
     :selected (selected-targets (:graph result) target-ids)
     :result result
     :resource (:resource result)}))

(defn run-task
  "Run one Alice task through the legacy local compression loop.

  `mode` is `:exhaustive` or `:bounded`. The task graph contains target roots
  and optional free-value roots. Search receives all task value ids as local
  node ids so configured graph rewrite operators can reuse free values. No
  rewrite operators are installed unless the caller supplies them in `opts`."
  ([task]
   (run-task task {}))
  ([task {:keys [mode targets opts]
          :or {mode :exhaustive
               opts {}}}]
   (let [{:keys [graph target-ids free-ids value-ids]} (task-graph task)
         targets (vec (or targets target-ids))
         initial-dl (mdl/graph-dl graph)
         opts (assoc opts :local-node-ids value-ids)
         result (case mode
                  :exhaustive (compress/compress-exhaustive graph opts)
                  :bounded (compress/compress-bounded graph targets opts)
                  (throw (ex-info "Unknown legacy Alice task mode"
                                  {:mode mode})))]
     (result-summary task mode target-ids free-ids initial-dl result))))

(defn run-task-comparison
  "Run legacy exhaustive and bounded local compression for one Alice task."
  ([task]
   (run-task-comparison task {}))
  ([task {:keys [exhaustive-opts bounded-opts bounded-targets]
          :or {exhaustive-opts {}
               bounded-opts {}}}]
   (let [exhaustive (run-task task {:mode :exhaustive
                                    :opts exhaustive-opts})
         bounded (run-task task {:mode :bounded
                                 :targets bounded-targets
                                 :opts bounded-opts})]
     {:task-name (:name task)
      :exhaustive exhaustive
      :bounded bounded
      :same-selected? (= (:selected exhaustive) (:selected bounded))
      :same-dl? (= (:dl exhaustive) (:dl bounded))
      :meets-threshold? (and (:meets-threshold? exhaustive)
                             (:meets-threshold? bounded))})))

(defn run-domain
  "Run the legacy local baseline across all tasks in an Alice task domain."
  ([domain]
   (run-domain domain {}))
  ([domain opts]
   {:domain-name (:name domain)
    :results (mapv #(run-task-comparison % (merge (:opts domain) opts))
                   (:tasks domain))}))
