(ns ciwi.notebook.alice-machinery
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum :as alice-wunderbaum]
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

(def default-run-opts
  {:registry alice/basic-operator-registry
   :operator-ids alice/basic-operator-ids
   :max-dag-dl 35
   :max-popped 5000
   :max-yields 500
   ;; The real Python-scale parity rows use the production default worthy DL.
   ;; Setting this to zero makes the small notebook examples compressible.
   :worthy-dl 0})

(def python-scale-run-opts
  (dissoc default-run-opts :worthy-dl))

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
;; Alice/Wunderbaum runs against `CompressionTask` values. The helpers here let
;; you go from a named case to the exact task and greedy result maps the
;; production parity path uses.

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

(defn run-case
  ([case-or-name]
   (run-case case-or-name {}))
  ([case-or-name opts]
   (let [case-data (case-data case-or-name)]
     (assoc (alice-wunderbaum/run-greedy-task
             (task case-data)
             (merge default-run-opts opts))
            :case case-data))))

(defn run-step
  ([case-or-name]
   (run-step case-or-name {}))
  ([case-or-name opts]
   (let [case-data (case-data case-or-name)]
     (assoc (alice-wunderbaum/run-compression-step
             (task case-data)
             (merge default-run-opts opts))
            :case case-data))))

(defn run-all
  ([]
   (run-all {}))
  ([opts]
   (mapv #(run-case % opts) cases)))

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

(defn result-operator-ids
  [result]
  (apply set/union
         #{}
         (for [expr (vals (:selected result))]
           (expression-operator-ids expr))))

(defn selected
  [result]
  (get-in result [:selected :target0]))

(defn step-row
  [idx step]
  {:step idx
   :target-id (:target-id step)
   :path (:path step)
   :initial-dl (:initial-dl step)
   :dl (:dl step)
   :compression-rate (:compression-rate step)
   :selected (:selected step)
   :candidates-consumed (:candidates-consumed step)
   :stop-reason (:stop-reason step)})

(defn step-rows
  [result]
  (mapv step-row (range) (:steps result)))

(defn summary
  [result]
  (let [{:keys [required forbidden exact] :as case-data}
        (or (:case result) (case-data (:task-name result)))
        selected (selected result)
        selected-ops (result-operator-ids result)]
    {:task-name (:task-name result)
     :target-preview (let [target (:target case-data)]
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
                     (= exact selected)
                     true)
     :meets-threshold? (:meets-threshold? result)
     :compression-rate (:compression-rate result)
     :initial-dl (:initial-dl result)
     :dl (:dl result)
     :steps (count (:steps result))
     :candidates-consumed (get-in result [:resource :candidates-consumed])
     :stop-reason (get-in result [:resource :stop-reason])}))

(defn inspect
  ([case-or-name]
   (inspect case-or-name {}))
  ([case-or-name opts]
   (let [result (run-case case-or-name opts)]
     {:summary (summary result)
      :steps (step-rows result)
      :resource (:resource result)
      :result result})))

(defn failing-summaries
  ([]
   (failing-summaries (run-all)))
  ([results]
   (->> results
        (map summary)
        (remove (fn [{:keys [meets-threshold? required-present?
                             forbidden-absent? exact-match?]}]
                  (and meets-threshold?
                       required-present?
                       forbidden-absent?
                       exact-match?)))
        vec)))

;; ## REPL Cells
;;
;; Evaluate these forms one at a time in Cursive. Keep the intermediate defs if
;; you want to drill into raw result maps with the value inspector.

(comment
  (case-names)

  (pp (case-data "range"))

  (pp (case-data "insert_repeat2"))

  (def range-result
    (run-case "range"))

  (pp (summary range-result))

  (pp (selected range-result))

  (pp (step-rows range-result))

  (def noisy-result
    (run-case "repeat_with_noise"))

  (pp (summary noisy-result))

  (pp (step-rows noisy-result))

  (def noisy-step
    (run-step "repeat_with_noise"))

  (pp (summary noisy-step))

  (pp (step-rows noisy-step))

  (def all-results
    (run-all))

  (pp (mapv summary all-results))

  (pp (failing-summaries all-results))

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
