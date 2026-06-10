(ns ciwi.alice
  (:require [ciwi.fix :as fix]
            [ciwi.operator :as op]
            [ciwi.value :as value]))

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
  (let [coerce-task-data (fn [x]
                           (value/datum (value/value x)))]
    (->CompressionTask name
                       (mapv coerce-task-data targets)
                       threshold-rate
                       (mapv coerce-task-data free-values)
                       solutions
                       metadata)))

(defn task-domain
  [name tasks & [{:keys [opts metadata]
                  :or {opts {}
                       metadata {}}}]]
  (->TaskDomain name (vec tasks) opts metadata))

(defn compression-rate
  [initial-dl compressed-dl]
  (if (pos? initial-dl)
    (* 100.0 (- 1.0 (/ compressed-dl initial-dl)))
    0.0))
