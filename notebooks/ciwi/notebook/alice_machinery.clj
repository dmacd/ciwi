(ns ciwi.notebook.alice-machinery
  (:require [ciwi.alice :as alice]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [clojure.pprint :as pprint]
            [clojure.set :as set]))

;; # Alice Machinery Testbed
;;
;; This file is meant to be opened directly in Cursive and evaluated against a
;; normal project REPL. It is intentionally just a Clojure namespace: helper
;; defs live at the top level, and the runnable "cells" live in `(comment ...)`
;; forms so loading the namespace does not kick off expensive searches.
;;
;; Start a REPL with the `:dev` alias, load this file, then put the cursor after
;; any form in the comment blocks and evaluate it with Cursive.

(def default-comparison-opts
  {:bounded-opts {:parallel? true
                  :re-eval-budget 64}})

(def deep-comparison-opts
  {:bounded-opts {:parallel? true
                  :re-eval-budget 256}
   :exhaustive-opts {:parallel? false}})

(defn pp
  [x]
  (binding [pprint/*print-right-margin* 100]
    (pprint/pprint x))
  x)

;; ## Cases
;;
;; These mirror the important Alice tests, but are ordinary data maps so they
;; are easy to inspect and edit from the REPL.

(defn increasing-runs
  [n]
  (vec (mapcat (fn [x]
                 (concat (repeat x 123) [64]))
               (range n))))

(def supported-sequence-cases
  [{:name "range"
    :target [0 1 2 3 4 5 6 7]
    :threshold-rate 1.0
    :exact [:brange 0 8]}
   {:name "simple_repeat"
    :target (vec (take 10 (cycle [140 -50])))
    :threshold-rate 1.0
    :exact [:repeat 5 [140 -50]]}
   {:name "constant-repeat"
    :target (vec (repeat 12 :z))
    :threshold-rate 1.0
    :exact [:repeat 12 [:z]]}
   {:name "insert_repeat"
    :target (vec (concat (repeat 4 45)
                         (repeat 6 87)))
    :threshold-rate 1.0
    :exact [:insert [:brange 0 4] 45 [:repeat 6 [87]]]}
   {:name "simply_linear"
    :target [2 5 8 11 14 17]
    :threshold-rate 1.0
    :exact [:add [:mult [:brange 0 6] 3] 2]}
   {:name "map_negate_equivalent"
    :target [0 -1 -2 -3 -4 -5]
    :threshold-rate 1.0
    :exact [:mult [:brange 0 6] -1]}
   {:name "cumsum"
    :target [0 1 3 6 10 15]
    :threshold-rate 1.0
    :exact [:cumsum [:brange 0 6]]}])

(def mirrored-sequence-cases
  [{:name "simple_repeat_long"
    :target (vec (take 20 (cycle [140 -50])))
    :required #{:repeat}
    :exact [:repeat 10 [140 -50]]}
   {:name "insert_repeat_long"
    :target (vec (concat (repeat 10 45)
                         (repeat 25 87)))
    :required #{:insert :repeat}
    :exact [:insert [:brange 0 10] 45 [:repeat 25 [87]]]}
   {:name "insert_repeat2"
    :target (vec (concat (repeat 10 45)
                         (repeat 25 87)
                         (repeat 61 164)))
    :required #{:insert :repeat}
    :forbidden #{:cumsum}
    :exact [:insert [:brange 0 35]
            [:insert [:brange 0 10] 45 [:repeat 25 [87]]]
            [:repeat 61 [164]]]}
   {:name "insert_repeat3"
    :target (vec (concat (repeat 10 45)
                         (take 50 (cycle [87 62]))
                         (repeat 61 164)))
    :required #{:insert :repeat}
    :forbidden #{:cumsum}}
   {:name "repeat_with_noise"
    :target (vec (concat (repeat 20 45)
                         [-1]
                         (repeat 40 45)))
    :required #{:insert :repeat}
    :exact [:insert [20] -1 [:repeat 60 [45]]]}
   {:name "sprinkled"
    :target (assoc (vec (repeat 40 0)) 3 1 17 1 31 1)
    :required #{:insert :repeat}}
   {:name "increasing_runs"
    :target (increasing-runs 9)
    :required #{:insert :repeat}}
   {:name "map_negate"
    :target (vec (map - (range 20)))
    :required #{:brange :mult}
    :exact [:mult [:brange 0 20] -1]}])

(def cases
  (vec (concat supported-sequence-cases mirrored-sequence-cases)))

(def cases-by-name
  (into {} (map (juxt :name identity)) cases))

(defn case-names
  []
  (mapv :name cases))

(defn case-data
  [case-or-name]
  (cond
    (string? case-or-name) (or (get cases-by-name case-or-name)
                               (throw (ex-info "Unknown Alice notebook case"
                                               {:name case-or-name
                                                :available (case-names)})))
    (keyword? case-or-name) (case-data (name case-or-name))
    (map? case-or-name) case-or-name
    :else (throw (ex-info "Expected case name or case map"
                          {:case-or-name case-or-name}))))

;; ## Task Construction
;;
;; Alice runs against `CompressionTask` values. The helpers here let you go from
;; a named case to the exact task and result maps the production code uses.

(defn task
  ([case-or-name]
   (task case-or-name {}))
  ([case-or-name opts]
   (let [{:keys [name target threshold-rate free-values solutions metadata]
          :or {threshold-rate 1.0
               free-values []
               solutions {}
               metadata {}}} (case-data case-or-name)]
     (alice/compression-task [target]
                             (merge {:name name
                                     :threshold-rate threshold-rate
                                     :free-values free-values
                                     :solutions solutions
                                     :metadata metadata}
                                    opts)))))

(defn run
  "Run one case in one mode. `mode` is `:exhaustive` or `:bounded`."
  ([case-or-name]
   (run case-or-name :exhaustive {}))
  ([case-or-name mode opts]
   (alice/run-task (task case-or-name)
                   {:mode mode
                    :opts opts})))

(defn compare-case
  ([case-or-name]
   (compare-case case-or-name default-comparison-opts))
  ([case-or-name opts]
   (alice/run-task-comparison (task case-or-name) opts)))

(defn compare-all
  ([]
   (compare-all default-comparison-opts))
  ([opts]
   (mapv #(compare-case % opts) cases)))

;; ## Inspection Helpers
;;
;; These flatten the nested result maps into row-ish data, which is easier to
;; inspect in a REPL inline result, Cursive value viewer, or pretty print.

(defn expression-operator-ids
  [expr]
  (cond
    (and (vector? expr)
         (seq expr)
         (contains? alice/basic-operator-registry (first expr)))
    (apply set/union
           #{(first expr)}
           (map expression-operator-ids (rest expr)))

    (sequential? expr)
    (apply set/union #{} (map expression-operator-ids expr))

    (contains? alice/basic-operator-registry expr)
    #{expr}

    :else
    #{}))

(defn selected-operator-ids
  [comparison]
  (apply set/union
         #{}
         (for [mode [:exhaustive :bounded]
               expr (vals (get-in comparison [mode :selected]))]
           (expression-operator-ids expr))))

(defn selected
  [comparison]
  {:exhaustive (get-in comparison [:exhaustive :selected :target0])
   :bounded (get-in comparison [:bounded :selected :target0])})

(defn candidate-row
  [candidate]
  (-> candidate
      (select-keys [:node-id :rewrite-operator-id :template-id :reason :op
                    :child-refs :before :after :delta :expression :edit-form])
      (update :op #(some-> % :id))))

(defn history-rows
  [result-or-summary]
  (mapv candidate-row
        (get-in result-or-summary [:result :history] (:history result-or-summary))))

(defn step-row
  [step]
  {:step (:step step)
   :reason (get-in step [:candidate :reason])
   :rewrite-operator-id (get-in step [:candidate :rewrite-operator-id])
   :node-id (get-in step [:candidate :node-id])
   :dl-before (:dl-before step)
   :dl-after (:dl-after step)
   :delta (get-in step [:candidate :delta])
   :mode (get-in step [:resource :mode])
   :candidates-accepted (get-in step [:resource :candidates-accepted])})

(defn step-rows
  [result-or-summary]
  (mapv step-row
        (get-in result-or-summary [:result :steps] (:steps result-or-summary))))

(defn graph-rows
  [g]
  (->> (:nodes g)
       (sort-by (comp str key))
       (mapv (fn [[id n]]
               (case (:kind n)
                 :value {:id id
                         :kind :value
                         :data (get-in n [:value :data])
                         :parents (:parents n)
                         :options (:options n)}
                 :operator {:id id
                            :kind :operator
                            :op (get-in n [:operator :id])
                            :parent (:parent n)
                            :children (:children n)})))))

(defn graph-state
  [result-or-summary]
  (graph-rows (get-in result-or-summary [:result :graph] (:graph result-or-summary))))

(defn target-dl
  [result-or-summary target-id]
  (mdl/node-dl (get-in result-or-summary [:result :graph] (:graph result-or-summary))
               target-id))

(defn summary
  [comparison]
  (let [{:keys [required forbidden exact]} (case-data (:task-name comparison))
        selected (selected comparison)
        selected-ops (selected-operator-ids comparison)]
    {:task-name (:task-name comparison)
     :target-preview (let [target (:target (case-data (:task-name comparison)))]
                       (if (and (sequential? target) (> (count target) 24))
                         {:count (count target)
                          :head (vec (take 12 target))
                          :tail (vec (take-last 12 target))}
                         target))
     :selected selected
     :selected-operator-ids selected-ops
     :required required
     :required-present? (if required
                          (set/subset? required selected-ops)
                          true)
     :forbidden forbidden
     :forbidden-absent? (if (seq forbidden)
                          (empty? (set/intersection forbidden selected-ops))
                          true)
     :exact exact
     :exact-match? (if exact
                     (= exact (:exhaustive selected))
                     true)
     :same-selected? (:same-selected? comparison)
     :same-dl? (:same-dl? comparison)
     :meets-threshold? (:meets-threshold? comparison)
     :exhaustive-rate (get-in comparison [:exhaustive :compression-rate])
     :bounded-rate (get-in comparison [:bounded :compression-rate])
     :exhaustive-dl (get-in comparison [:exhaustive :dl])
     :bounded-dl (get-in comparison [:bounded :dl])}))

(defn inspect
  ([case-or-name]
   (inspect case-or-name default-comparison-opts))
  ([case-or-name opts]
   (let [comparison (compare-case case-or-name opts)]
     {:summary (summary comparison)
      :exhaustive-steps (step-rows (:exhaustive comparison))
      :bounded-steps (step-rows (:bounded comparison))
      :exhaustive-history (history-rows (:exhaustive comparison))
      :bounded-history (history-rows (:bounded comparison))
      :exhaustive-resource (get-in comparison [:exhaustive :resource])
      :bounded-resource (get-in comparison [:bounded :resource])
      :comparison comparison})))

(defn failing-summaries
  ([]
   (failing-summaries (compare-all)))
  ([comparisons]
   (->> comparisons
        (map summary)
        (remove (fn [{:keys [same-selected? same-dl? meets-threshold?
                             required-present? forbidden-absent? exact-match?]}]
                  (and same-selected?
                       same-dl?
                       meets-threshold?
                       required-present?
                       forbidden-absent?
                       exact-match?)))
        vec)))

;; ## REPL Cells
;;
;; Evaluate these forms one at a time in Cursive. Keep the intermediate defs if
;; you want to drill into raw comparison maps with the value inspector.

(comment
  (case-names)

  (pp (case-data "range"))

  (pp (case-data "insert_repeat2"))

  (def range-comparison
    (compare-case "range"))

  (pp (summary range-comparison))

  (pp (selected range-comparison))

  (pp (step-rows (:exhaustive range-comparison)))

  (pp (graph-state (:exhaustive range-comparison)))

  (def noisy-comparison
    (compare-case "repeat_with_noise" deep-comparison-opts))

  (pp (summary noisy-comparison))

  (pp (step-rows (:exhaustive noisy-comparison)))

  (pp (step-rows (:bounded noisy-comparison)))

  (pp (history-rows (:bounded noisy-comparison)))

  (pp (target-dl (:exhaustive noisy-comparison) :target0))

  (def all-comparisons
    (compare-all deep-comparison-opts))

  (pp (mapv summary all-comparisons))

  (pp (failing-summaries all-comparisons))

  ;; Make an ad hoc case without touching the test file.
  (def my-case
    {:name "scratch"
     :target [10 12 14 16 18]
     :threshold-rate 1.0})

  (pp (inspect my-case)))


  (tagged-literal
    'cursive/html
    {:html  "<svg height='120' width='120'>
               <circle cx='60' cy='60' r='50' fill='none' stroke='red'/>
             </svg>"
     :title "My plot"
     :key   "my-plot"})