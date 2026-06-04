(ns ciwi.alice
  (:require [ciwi.compress :as compress]
            [ciwi.fix :as fix]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]))

(defrecord CompressionTask [name targets threshold-rate free-values solutions metadata])
(defrecord TaskDomain [name tasks opts metadata])

(def basic-operator-registry
  "Operator basis used by the Alice parity harness, matching test_alice.py."
  (assoc (select-keys op/registry
                      [:map :brange :add :mult :negate :concat :repeat
                       :getitem :insert :cumsum :lessthan :equal])
         :fix fix/operator))

(def basic-operator-ids
  [:map :fix :brange :add :mult :negate :concat :repeat
   :getitem :insert :cumsum :lessthan :equal])

(defn compression-task
  [targets {:keys [name threshold-rate free-values solutions metadata]
            :or {name "task"
                 threshold-rate 0.0
                 free-values []
                 solutions {}
                 metadata {}}}]
  (->CompressionTask name (vec targets) threshold-rate (vec free-values) solutions metadata))

(defn task-domain
  [name tasks & [{:keys [opts metadata]
                  :or {opts {}
                       metadata {}}}]]
  (->TaskDomain name (vec tasks) opts metadata))

(defn- indexed-id
  [prefix idx]
  (keyword (str (name prefix) idx)))

(defn task-graph
  [task]
  (let [target-ids (mapv #(indexed-id :target %) (range (count (:targets task))))
        free-ids (mapv #(indexed-id :free %) (range (count (:free-values task))))
        g (reduce (fn [g [id value]]
                    (graph/add-value g id value))
                  (graph/empty-graph)
                  (concat (map vector target-ids (:targets task))
                          (map vector free-ids (:free-values task))))]
    {:graph g
     :target-ids target-ids
     :free-ids free-ids
     :value-ids (vec (concat target-ids free-ids))}))

(defn compression-rate
  [initial-dl compressed-dl]
  (if (pos? initial-dl)
    (* 100.0 (- 1.0 (/ compressed-dl initial-dl)))
    0.0))

(defn- selected-targets
  [g target-ids]
  (into {}
        (map (fn [target-id]
               [target-id (mdl/selected-expression g target-id)]))
        target-ids))

(defn- result-summary
  [task mode target-ids free-ids initial-dl result]
  (let [rate (compression-rate initial-dl (:dl result))]
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
  "Run one Alice-style compression task through the existing compression loop.

  `mode` is `:exhaustive` or `:bounded`. The task graph contains target roots and
  optional free-value roots. Search receives all task value ids as local node ids
  so configured graph rewrite operators can reuse free values without making the
  harness depend on helper-library internals.
  "
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
                  (throw (ex-info "Unknown Alice task mode" {:mode mode})))]
     (result-summary task mode target-ids free-ids initial-dl result))))

(defn run-task-comparison
  "Run exhaustive and bounded compression for one task and compare target outputs."
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
  ([domain]
   (run-domain domain {}))
  ([domain opts]
   {:domain-name (:name domain)
    :results (mapv #(run-task-comparison % (merge (:opts domain) opts))
                   (:tasks domain))}))
