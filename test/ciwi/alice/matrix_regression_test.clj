(ns ciwi.alice.matrix-regression-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [ciwi.alice.wunderbaum :as alice-wb]
            [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.value :as value]))

(def ^:private matrix-fixture-path
  "test/ciwi/fixtures/matrix_regression.edn")

(def ^:private matrix-solution-prefixes
  #{[:dot :leaf :leaf]
    [:add [:dot :leaf :leaf] :leaf]
    [:add :leaf [:dot :leaf :leaf]]})

(declare op-shape)

(defn- child-shape
  [g id]
  (let [n (graph/node g id)]
    (if-let [op-id (first (:options n))]
      (op-shape g op-id)
      :leaf)))

(defn- op-shape
  [g op-id]
  (let [n (graph/node g op-id)]
    (into [(:id (:operator n))]
          (map #(child-shape g %) (:children n)))))

(defn- solution-prefix?
  [summary]
  (boolean
   (some matrix-solution-prefixes
         (map #(op-shape (:graph summary) %)
              (graph/operator-ids (:graph summary))))))

(defn- matrix-fixture
  []
  (let [data (edn/read-string (slurp matrix-fixture-path))]
    {:x-mat (dense/array (:x-mat data))
     :w-init (:w-init data)
     :w-true (:w-true data)
     :y (dense/array (:y data))}))

(defn- close?
  [expected actual]
  (< (Math/abs (- (double expected) (double actual)))
     1.0e-6))

(deftest matrix-regression-compression-step-finds-python-dot-add-solution
  (let [{:keys [x-mat w-init y]} (matrix-fixture)
        expected-w [-1.2774670410156252
                    1.8242246093750003
                    -0.676953857421875
                    -4.369272460937499
                    -0.7129829101562499
                    1.9128486328125
                    3.338587646484375
                    -0.8092513427734378
                    3.4719315185546877
                    2.292829345703126]
        result (alice-wb/compression-step-candidate
                y
                [(value/value x-mat {:permeable? false})
                 (value/value w-init {:permeable? true})]
                {:registry {:dot op/dot
                            :add op/add}
                 :operator-ids [:dot :add]
                 :max-dag-dl 20
                 :max-popped 100
                 :max-yields 20
                 :optimize-candidates? true
                 :candidate-predicate solution-prefix?})
        candidate (:candidate result)
        shapes (when candidate
                 (set (map #(op-shape (:graph candidate) %)
                           (graph/operator-ids (:graph candidate)))))
        w-best (when candidate
                 (dense/ravel
                  (value/datum
                   (propagation/value-at (:memory candidate) :target2))))]
    (is candidate)
    (is (>= (:compression-rate result) 1.0))
    (is (contains? shapes [:add [:dot :leaf :leaf] :leaf]))
    (is (every? true? (map close? expected-w w-best)))))
