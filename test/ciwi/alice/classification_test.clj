(ns ciwi.alice.classification-test
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum :as alice-wb]
            [ciwi.fixtures.iris-debug :as iris-debug]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(def ^:private classifier-operator-dl
  (Math/ceil (value/jelias 2)))

(def ^:private classifier-ops-with-counts
  [{:op :setitem
    :input-specs [:array-number :array-bool :array-int]
    :output-spec :array-int
    :count 0
    :dl classifier-operator-dl}
   {:op :lessthan
    :input-specs [:array-float :float]
    :output-spec :array-bool
    :count 0
    :dl classifier-operator-dl}])

(def ^:private classifier-solution-prefixes
  #{[:lessthan :leaf :leaf]
    [:setitem :leaf [:lessthan :leaf :leaf] :leaf]})

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
   (some classifier-solution-prefixes
         (map #(op-shape (:graph summary) %)
              (graph/operator-ids (:graph summary))))))

(defn- expr-shape
  [expr]
  (if (and (vector? expr)
           (keyword? (first expr)))
    (into [(first expr)] (map expr-shape (rest expr)))
    :leaf))

(defn- classifier-opts
  []
  {:registry {:setitem op/setitem
              :lessthan op/lessthan}
   :operator-ids [:setitem :lessthan]
   :ops-with-counts classifier-ops-with-counts
   :max-dag-dl 20
   :max-popped 2000
   :max-yields 20
   :optimize-candidates? true})

(defn- classifier-task
  [{:keys [target factor threshold]} with-solution?]
  (alice/compression-task
   [target factor]
   {:name "iris_debug_case"
    :threshold-rate 0.01
    :free-values [threshold]
    :solutions (if with-solution?
                 {0 solution-prefix?}
                 {})}))

(deftest iris-classifier-compression-step-finds-python-setitem-lessthan-solution
  (let [{:keys [target factor threshold]} (iris-debug/fixture)
        result (alice-wb/compression-step-candidate
                target
                [(value/value factor {:permeable? false})
                 (value/value threshold {:permeable? true})]
                (assoc (classifier-opts)
                       :candidate-predicate solution-prefix?))
        candidate (:candidate result)
        shapes (when candidate
                 (set (map #(op-shape (:graph candidate) %)
                           (graph/operator-ids (:graph candidate)))))
        threshold-best (when candidate
                         (value/datum
                          (propagation/value-at (:memory candidate) :target2)))]
    (is candidate)
    (is (>= (:compression-rate result) 0.01))
    (is (contains? shapes [:setitem :leaf [:lessthan :leaf :leaf] :leaf]))
    (is (number? threshold-best))
    (is (= [:setitem :leaf [:lessthan :leaf :leaf] :leaf]
           (expr-shape (:selected result))))))

(deftest iris-classifier-greedy-single-factor-with-solution-reaches-threshold
  (let [fixture (iris-debug/fixture)
        result (alice-wb/run-greedy-task
                (classifier-task fixture true)
                (classifier-opts))]
    (is (:meets-threshold? result))
    (is (>= (:compression-rate result) 0.01))
    (is (= 1 (count (:steps result))))
    (is (= :python-test-parity
           (get-in result [:resource :leaf-selection-policy])))
    (is (= [:setitem :leaf [:lessthan :leaf :leaf] :leaf]
           (expr-shape (get-in result [:selected :target0]))))
    (is (= :leaf
           (expr-shape (get-in result [:selected :target1]))))))

(deftest iris-classifier-greedy-single-factor-without-solution-reaches-threshold
  (let [fixture (iris-debug/fixture)
        result (alice-wb/run-greedy-task
                (classifier-task fixture false)
                (classifier-opts))]
    (is (:meets-threshold? result))
    (is (>= (:compression-rate result) 0.01))
    (is (= 1 (count (:steps result))))
    (is (= :python-test-parity
           (get-in result [:resource :leaf-selection-policy])))
    (is (= [:setitem :leaf [:lessthan :leaf :leaf] :leaf]
           (expr-shape (get-in result [:selected :target0]))))
    (is (= :leaf
           (expr-shape (get-in result [:selected :target1]))))))
