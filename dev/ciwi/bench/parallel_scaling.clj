(ns ciwi.bench.parallel-scaling
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum :as alice-wb]
            [clojure.string :as str]))

(def ^:private basic-operator-ids
  [:map :fix :brange :add :mult :negate :concat :repeat
   :getitem :insert :cumsum :lessthan :equal])

(def ^:private base-opts
  {:registry alice/basic-operator-registry
   :operator-ids basic-operator-ids
   :max-dag-dl 35
   :max-popped 10000
   :max-yields 1000})

(def ^:private default-tasks
  ["insert_repeat3" "increasing_runs" "reg_only_y"])

(def ^:private default-scales
  ["small" "medium" "large"])

(def ^:private default-workers
  [1 2 4 8])

(def ^:private strategies
  #{"partitioned" "global-best-first"})

(def ^:private reports
  #{"summary" "steps"})

(def ^:private stat-sum-keys
  [:frontier-popped
   :frontier-enqueued
   :materialized-results
   :duplicate-results
   :emitted
   :queue-wait-ns
   :materialization-ns
   :dedupe-ns
   :scoring-ns
   :candidate-transform-ns
   :expansion-ns
   :commit-wait-ns
   :cancelled-takes])

(defn- insert-repeat3-target
  [{:keys [head pairs tail]}]
  (let [head (long head)
        pairs (long pairs)
        tail (long tail)]
    (vec (concat (repeat head 45)
                 (take (* 2 pairs) (cycle [87 62]))
                 (repeat tail 164)))))

(defn- increasing-runs-target
  [n-runs]
  (vec (mapcat (fn [x]
                 (concat (repeat x 123) [64]))
               (range n-runs))))

(defn- reg-only-y-target
  [n]
  (mapv #(- (* 3 %) 5) (range n)))

(defn- task-case
  [task scale]
  (case [task scale]
    ["insert_repeat3" "small"]
    {:target (insert-repeat3-target {:head 25 :pairs 62 :tail 152})
     :threshold-rate 0.93}

    ["insert_repeat3" "medium"]
    {:target (insert-repeat3-target {:head 50 :pairs 125 :tail 305})
     :threshold-rate 0.93}

    ["insert_repeat3" "large"]
    {:target (insert-repeat3-target {:head 100 :pairs 250 :tail 610})
     :threshold-rate 0.93}

    ["increasing_runs" "small"]
    {:target (increasing-runs-target 150)
     :threshold-rate 0.999}

    ["increasing_runs" "medium"]
    {:target (increasing-runs-target 300)
     :threshold-rate 0.999}

    ["increasing_runs" "large"]
    {:target (increasing-runs-target 500)
     :threshold-rate 0.999}

    ["reg_only_y" "small"]
    {:target (reg-only-y-target 1000)
     :threshold-rate 0.98}

    ["reg_only_y" "medium"]
    {:target (reg-only-y-target 5000)
     :threshold-rate 0.98}

    ["reg_only_y" "large"]
    {:target (reg-only-y-target 10000)
     :threshold-rate 0.98}))

(defn- now-ms
  []
  (/ (double (System/nanoTime)) 1000000.0))

(defn- median
  [xs]
  (let [xs (vec (sort xs))
        n (count xs)]
    (if (odd? n)
      (nth xs (quot n 2))
      (/ (+ (nth xs (dec (quot n 2)))
            (nth xs (quot n 2)))
         2.0))))

(defn- aggregate-stats
  [result]
  (let [step-stats (keep :wunderbaum-stats (:steps result))
        summed (reduce (fn [acc stats]
                         (merge-with + acc (select-keys stats stat-sum-keys)))
                       {}
                       step-stats)
        max-active (reduce max
                           0
                           (map #(long (or (:max-active-frontier-items %) 0))
                                step-stats))]
    (assoc summed :max-active-frontier-items max-active)))

(defn- ns->ms
  [n]
  (/ (double (or n 0)) 1000000.0))

(defn- timed-run
  [task scale workers strategy collect-stats? {:keys [frontier-batch-size]}]
  (let [{:keys [target threshold-rate]} (task-case task scale)
        compression-task (alice/compression-task [target]
                                                 {:name task
                                                  :threshold-rate threshold-rate})
        opts (cond-> base-opts
               frontier-batch-size
               (assoc :frontier-batch-size frontier-batch-size)

               collect-stats?
               (assoc :collect-wunderbaum-stats? true)

               (> workers 1)
               (assoc :num-workers workers)

               (and (> workers 1)
                    (= "global-best-first" strategy))
               (assoc :parallel-strategy :global-best-first))
        t0 (now-ms)
        result (alice-wb/run-greedy-task compression-task opts)
        elapsed (- (now-ms) t0)
        stats (when collect-stats?
                (aggregate-stats result))]
    {:elapsed-ms elapsed
     :length (count target)
     :compression-rate (:compression-rate result)
     :meets-threshold? (:meets-threshold? result)
     :steps (count (:steps result))
     :stop-reason (name (get-in result [:resource :stop-reason]))
     :step-results (:steps result)
     :stats stats}))

(defn- summarize
  [task scale workers strategy warmups runs collect-stats? bench-opts]
  (dotimes [_ warmups]
    (timed-run task scale workers strategy collect-stats? bench-opts))
  (let [samples (repeatedly runs #(timed-run task
                                             scale
                                             workers
                                             strategy
                                             collect-stats?
                                             bench-opts))
        times (mapv :elapsed-ms samples)
        last-result (last samples)]
    (merge last-result
           {:impl (if (= "global-best-first" strategy)
                    "ciwi-global"
                    "ciwi")
            :task task
            :scale scale
            :workers workers
            :strategy strategy
            :warmups warmups
            :runs runs
            :median-ms (median times)
            :min-ms (apply min times)
            :max-ms (apply max times)})))

(defn- base-csv-row
  [row]
  (str/join ","
            [(:impl row)
             (:task row)
             (:scale row)
             (:workers row)
             (:length row)
             (:warmups row)
             (:runs row)
             (format "%.3f" (:median-ms row))
             (format "%.3f" (:min-ms row))
             (format "%.3f" (:max-ms row))
             (format "%.9f" (:compression-rate row))
             (:meets-threshold? row)
             (:steps row)
             (:stop-reason row)]))

(defn- stats-csv-columns
  [row]
  (let [stats (:stats row)]
    [(long (or (:frontier-popped stats) 0))
     (long (or (:frontier-enqueued stats) 0))
     (long (or (:materialized-results stats) 0))
     (long (or (:duplicate-results stats) 0))
     (long (or (:emitted stats) 0))
     (long (or (:max-active-frontier-items stats) 0))
     (format "%.3f" (ns->ms (:queue-wait-ns stats)))
     (format "%.3f" (ns->ms (:materialization-ns stats)))
     (format "%.3f" (ns->ms (:dedupe-ns stats)))
     (format "%.3f" (ns->ms (:scoring-ns stats)))
     (format "%.3f" (ns->ms (:candidate-transform-ns stats)))
     (format "%.3f" (ns->ms (:expansion-ns stats)))
     (format "%.3f" (ns->ms (:commit-wait-ns stats)))]))

(defn- csv-row
  [row collect-stats?]
  (if collect-stats?
    (str (base-csv-row row)
         ","
         (str/join "," (stats-csv-columns row)))
    (base-csv-row row)))

(defn- step-csv-row
  [row run-index step-index step]
  (let [stats (:wunderbaum-stats step)]
    (str/join ","
              [(:impl row)
               (:task row)
               (:scale row)
               (:workers row)
               (:length row)
               run-index
               step-index
               (pr-str (:path step))
               (format "%.6f" (:initial-dl step))
               (format "%.6f" (:dl step))
               (format "%.9f" (:compression-rate step))
               (:candidates-consumed step)
               (:stop-reason step)
               (format "%.3f" (double (or (:search-elapsed-ms step) 0.0)))
               (long (or (:frontier-popped stats) 0))
               (long (or (:frontier-enqueued stats) 0))
               (long (or (:materialized-results stats) 0))
               (long (or (:duplicate-results stats) 0))
               (long (or (:emitted stats) 0))
               (long (or (:max-active-frontier-items stats) 0))
               (format "%.3f" (ns->ms (:queue-wait-ns stats)))
               (format "%.3f" (ns->ms (:materialization-ns stats)))
               (format "%.3f" (ns->ms (:dedupe-ns stats)))
               (format "%.3f" (ns->ms (:scoring-ns stats)))
               (format "%.3f" (ns->ms (:candidate-transform-ns stats)))
               (format "%.3f" (ns->ms (:expansion-ns stats)))
               (format "%.3f" (ns->ms (:commit-wait-ns stats)))])))

(defn- print-step-rows!
  [task scale workers strategy warmups runs bench-opts]
  (dotimes [_ warmups]
    (timed-run task scale workers strategy true bench-opts))
  (doseq [run-index (range runs)
          :let [row (merge (timed-run task
                                       scale
                                       workers
                                       strategy
                                       true
                                       bench-opts)
                           {:impl (if (= "global-best-first" strategy)
                                    "ciwi-global"
                                    "ciwi")
                            :task task
                            :scale scale
                            :workers workers
                            :strategy strategy
                            :warmups warmups
                            :runs runs})]
          [step-index step] (map-indexed vector (:step-results row))]
    (println (step-csv-row row run-index step-index step))))

(defn- parse-list
  [s]
  (when s
    (str/split s #",")))

(defn- parse-int-list
  [s]
  (mapv parse-long (parse-list s)))

(defn- cli-opts
  [args]
  (loop [args args
         opts {:tasks default-tasks
               :scales default-scales
               :workers default-workers
               :strategy "partitioned"
               :warmups 1
               :runs 1}]
    (if-let [flag (first args)]
      (let [value (second args)]
        (case flag
          "--tasks"
          (recur (nnext args) (assoc opts :tasks (parse-list value)))

          "--scales"
          (recur (nnext args) (assoc opts :scales (parse-list value)))

          "--workers"
          (recur (nnext args) (assoc opts :workers (parse-int-list value)))

          "--strategy"
          (do
            (when-not (contains? strategies value)
              (throw (ex-info "Unknown benchmark strategy"
                              {:strategy value
                               :allowed strategies})))
            (recur (nnext args) (assoc opts :strategy value)))

          "--warmups"
          (recur (nnext args) (assoc opts :warmups (parse-long value)))

          "--runs"
          (recur (nnext args) (assoc opts :runs (parse-long value)))

          "--stats"
          (recur (nnext args) (assoc opts :collect-stats? (= "true" value)))

          "--frontier-batch-size"
          (recur (nnext args) (assoc opts :frontier-batch-size
                                     (parse-long value)))

          "--report"
          (do
            (when-not (contains? reports value)
              (throw (ex-info "Unknown benchmark report"
                              {:report value
                               :allowed reports})))
            (recur (nnext args) (assoc opts :report value)))

          (throw (ex-info "Unknown benchmark option" {:flag flag}))))
      opts)))

(defn -main
  [& args]
  (let [{:keys [tasks scales workers strategy warmups runs collect-stats?
                report frontier-batch-size]}
        (cli-opts args)
        report (or report "summary")
        bench-opts {:frontier-batch-size frontier-batch-size}
        header "impl,task,scale,workers,length,warmups,runs,median_ms,min_ms,max_ms,compression_rate,meets_threshold,steps,stop_reason"
        stats-header "frontier_popped,frontier_enqueued,materialized_results,duplicate_results,emitted,max_active_frontier_items,queue_wait_ms,materialization_ms,dedupe_ms,scoring_ms,candidate_transform_ms,expansion_ms,commit_wait_ms"
        step-header "impl,task,scale,workers,length,run,step,path,initial_dl,step_dl,step_compression_rate,candidates_consumed,stop_reason,search_ms,frontier_popped,frontier_enqueued,materialized_results,duplicate_results,emitted,max_active_frontier_items,queue_wait_ms,materialization_ms,dedupe_ms,scoring_ms,candidate_transform_ms,expansion_ms,commit_wait_ms"]
    (case report
      "summary"
      (do
        (println (if collect-stats?
                   (str header "," stats-header)
                   header))
        (doseq [task tasks
                scale scales
                worker-count workers]
          (println (csv-row (summarize task
                                       scale
                                       worker-count
                                       strategy
                                       warmups
                                       runs
                                       collect-stats?
                                       bench-opts)
                            collect-stats?))))

      "steps"
      (do
        (println step-header)
        (doseq [task tasks
                scale scales
                worker-count workers]
          (print-step-rows! task
                            scale
                            worker-count
                            strategy
                            warmups
                            runs
                            bench-opts))))))
