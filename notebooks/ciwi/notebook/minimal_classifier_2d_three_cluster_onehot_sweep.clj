(ns ciwi.notebook.minimal-classifier-2d-three-cluster-onehot-sweep
  (:require [ciwi.notebook.minimal-classifier-2d-three-cluster-utils :as u
             :refer [cell]]))

;; # Minimal 2D Three-Cluster One-Hot Sweep
;;
;; Cursive workflow:
;; 1. Start a REPL with the `:dev` alias.
;; 2. Load this namespace.
;; 3. Evaluate the `(cell ...)` form in each comment block to run that cell and
;;    show the result inline at the cell location.
;;
;; The support code lives in
;; `ciwi.notebook.minimal-classifier-2d-three-cluster-utils` so this file stays
;; close to the Python notebook's cell-level call pattern.

;; Cell 1: configure sweep defaults.

(def sweep-config
  {:separations [4.0 8.0 16.0]
   :n-train-per-class-grid [64 128]
   :n-test-per-class 5
   :n-repeats 1
   :max-test-points nil
   :seed 0
   :free-value-mode "zero"
   :store-graphs? true
   :num-workers 4
   :outer-workers 8
   :threshold-rate 1.0
   :min-compression-rate 0.01
   :max-dag-dl 26
   :tie-tol 1.0e-9})

(def render-dir
  "tmp/minimal_classifier_2d_three_cluster_onehot_sweep")

;; Cell 2: progress/cache state. Re-evaluate to clear cached results.

(comment
  (cell
    (def condition-graph-cache (atom {}))
    (def sweep-job nil)
    (def sweep-result nil)
    {:condition-graph-cache condition-graph-cache
     :sweep-job sweep-job
     :sweep-result sweep-result}))

;; Cell 3: preview the unit-circle cluster geometry and generated sample data.

(comment
  (cell
    (def preview-separation
      (let [separations (:separations sweep-config)]
        (* 2.0 (nth separations (quot (count separations) 2)))))
    (def preview-n-per-class 32)
    (def preview-data
      (u/make-3cluster-data preview-separation
                            :n-per-class preview-n-per-class
                            :seed (:seed sweep-config)))
    (u/stack-view
     [(u/table-view (u/preview-rows (:x preview-data) (:y preview-data))
                    {:title "preview rows"
                     :key "preview-rows"})
      (u/cluster-preview-view (:x preview-data)
                              (:y preview-data)
                              preview-separation)]
     {:title "preview"
      :key "preview"})))

;; Cell 4: start the recompression sweep and cache successful candidate results.
;;
;; Re-evaluate the progress cell while the job is running to refresh the inline
;; progress snapshot.

(comment
  (cell
    (def sweep-job
      (u/start-accuracy-sweep!
       (assoc sweep-config
              :condition-graph-cache condition-graph-cache)))
    (u/progress-view sweep-job)))

(comment
  (cell
    (u/progress-view sweep-job)))

(comment
  (cell
    (u/cancel-sweep! sweep-job)
    (u/progress-view sweep-job)))

;; Cell 4b: collect the async sweep result after the progress view reports done.

(comment
  (cell
    (def sweep-result @(:result sweep-job))
    (reset! condition-graph-cache (:condition-graph-cache sweep-result))
    (u/stack-view
     [(u/table-view (take 8 (:scores sweep-result))
                    {:title "candidate scores head"
                     :key "scores-head"})
      (u/table-view (take 8 (:predictions sweep-result))
                    {:title "predictions head"
                     :key "predictions-head"})]
     {:title "sweep result"
      :key "sweep-result"})))

;; Blocking alternative to Cell 4 and Cell 4b.

;(comment
;  (cell
;    (def sweep-result
;      (u/run-accuracy-sweep!
;       (assoc sweep-config
;              :condition-graph-cache condition-graph-cache)))
;    (reset! condition-graph-cache (:condition-graph-cache sweep-result))
;    (u/stack-view
;     [(u/table-view (take 8 (:scores sweep-result))
;                    {:title "candidate scores head"
;                     :key "scores-head-blocking"})
;      (u/table-view (take 8 (:predictions sweep-result))
;                    {:title "predictions head"
;                     :key "predictions-head-blocking"})]
;     {:title "sweep result"
;      :key "sweep-result-blocking"})))

;; Cell 5: display raw candidate scores and per-test predictions.

(comment
  (cell
    (u/stack-view
     [(u/table-view (:scores sweep-result)
                    {:title "candidate scores"
                     :key "candidate-scores"})
      (u/table-view (:predictions sweep-result)
                    {:title "predictions"
                     :key "predictions"})]
     {:title "raw sweep tables"
      :key "raw-sweep-tables"})))

;; Cell 6: summarize sweep metrics by separation and training sample count.

(comment
  (cell
    (def accuracy-summary
      (u/summarize-accuracy-sweep (:predictions sweep-result)
                                  (:scores sweep-result)))
    (u/table-view accuracy-summary
                  {:title "accuracy summary"
                   :key "accuracy-summary"})))

;; Cell 7: plot the primary IC accuracy heatmap and accuracy curves.

(comment
  (cell
    (u/stack-view
     [(u/summary-heatmap accuracy-summary
                         :accuracy
                         {:title "IC recompression accuracy"
                          :key "accuracy-heatmap"
                          :vmin 0.0
                          :vmax 1.0})
      (u/accuracy-curves accuracy-summary)]
     {:title "accuracy plots"
      :key "accuracy-plots"})))

;; Cell 8: plot diagnostic heatmaps for baseline accuracy and compression
;; outcomes.

(comment
  (cell
    (def diagnostic-plots
      (u/diagnostic-heatmaps accuracy-summary))
    (u/stack-view diagnostic-plots
                  {:title "diagnostic heatmaps"
                   :key "diagnostic-heatmaps"})))

;; Cell 9: inspect cached solution graphs by selecting a sweep grid condition.

(comment
  (cell
    (u/stack-view
     [(u/table-view (u/cached-condition-options @condition-graph-cache)
                    {:title "cached conditions"
                     :key "cached-conditions"})
      (u/render-cached-condition!
       @condition-graph-cache
       {:separation 8.0
        :n-train-per-class 64
        :repeat 0
        :mode :winning
        :render-dir (str render-dir "/cached_solution_graphs")})]
     {:title "cached graph explorer"
      :key "cached-graph-explorer"})))

(comment
  (cell
    (u/render-cached-condition!
     @condition-graph-cache
     {:separation 8.0
      :n-train-per-class 64
      :repeat 0
      :mode :true
      :render-dir (str render-dir "/cached_solution_graphs")})))

(comment
  (cell
    (u/render-cached-condition!
     @condition-graph-cache
     {:separation 8.0
      :n-train-per-class 64
      :repeat 0
      :mode :all
      :render-dir (str render-dir "/cached_solution_graphs")})))
