(ns ciwi.iris-classification-test
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum :as alice-wb]
            [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.graph-optimize :as graph-optimize]
            [ciwi.operator :as op]
            [ciwi.optimize :as optimize]
            [ciwi.propagation :as propagation]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(def ^:private sepal-length
  [5.1 4.9 4.7 4.6 5.0 5.4 4.6 5.0 4.4 4.9
   5.4 4.8 4.8 4.3 5.8 5.7 5.4 5.1 5.7 5.1
   5.4 5.1 4.6 5.1 4.8 5.0 5.0 5.2 5.2 4.7
   4.8 5.4 5.2 5.5 4.9 5.0 5.5 4.9 4.4 5.1
   5.0 4.5 4.4 5.0 5.1 4.8 5.1 4.6 5.3 5.0
   7.0 6.4 6.9 5.5 6.5 5.7 6.3 4.9 6.6 5.2
   5.0 5.9 6.0 6.1 5.6 6.7 5.6 5.8 6.2 5.6
   5.9 6.1 6.3 6.1 6.4 6.6 6.8 6.7 6.0 5.7
   5.5 5.5 5.8 6.0 5.4 6.0 6.7 6.3 5.6 5.5
   5.5 6.1 5.8 5.0 5.6 5.7 5.7 6.2 5.1 5.7
   6.3 5.8 7.1 6.3 6.5 7.6 4.9 7.3 6.7 7.2
   6.5 6.4 6.8 5.7 5.8 6.4 6.5 7.7 7.7 6.0
   6.9 5.6 7.7 6.3 6.7 7.2 6.2 6.1 6.4 7.2
   7.4 7.9 6.4 6.3 6.1 7.7 6.3 6.4 6.0 6.9
   6.7 6.9 5.8 6.8 6.7 6.7 6.3 6.5 6.2 5.9])

(def ^:private random-state-0-permutation
  [114 62 33 107 7 100 40 86 76 71 134 51 73 54 63
   37 78 90 45 16 121 66 24 8 126 22 44 97 93 26
   137 84 27 127 132 59 18 83 61 92 112 2 141 43 10
   60 116 144 119 108 69 135 56 80 123 133 106 146 50 147
   85 30 101 94 64 89 91 125 48 13 111 95 20 15 52
   3 149 98 6 68 109 96 12 102 120 104 128 46 11 110
   124 41 148 1 113 139 42 4 129 17 38 5 53 143 105
   0 34 28 55 75 35 23 74 31 118 57 131 65 32 138
   14 122 19 29 130 49 136 99 82 79 115 145 72 77 25
   81 140 142 39 58 88 70 87 36 21 9 103 67 117 47])

(defn- iris-fixture
  []
  {:target (dense/array (mapv #(quot % 50) random-state-0-permutation))
   :factor (dense/array (mapv sepal-length random-state-0-permutation))
   :threshold 4.8})

(defn- mem-entry
  [data opts]
  (propagation/entry false (value/value data opts)))

(defn- iris-classification-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :root nil)
      (graph/add-value :rest nil)
      (graph/add-value :mask nil)
      (graph/add-value :factor nil)
      (graph/add-value :threshold nil)
      (graph/add-value :selection nil)
      (graph/set-roots [:root])
      (graph/add-operator :lessthan op/lessthan :mask [:factor :threshold])
      (graph/add-operator :setitem op/setitem :root [:rest :mask :selection])))

(defn- iris-classification-memory
  [{:keys [target factor threshold]}]
  {:root (mem-entry target {:name "target"
                            :permeable? false})
   :factor (mem-entry factor {:name "factor"
                              :permeable? false})
   :threshold (mem-entry threshold {:name "threshold"
                                    :permeable? true})})

(defn- initial-iris-classification-memory
  [g fixture]
  (first (propagation/propagate g
                                (iris-classification-memory fixture)
                                {:partial? false
                                 :unique? true})))

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

(deftest try-to-optimize-scores-iris-threshold-classifier
  (let [fixture (iris-fixture)
        g (iris-classification-graph)
        mem (initial-iris-classification-memory g fixture)
        result (graph-optimize/try-to-optimize
                g
                mem
                {:section-ids [:root :factor :threshold]})
        threshold-opt (value/datum
                       (propagation/value-at (:memory result) :threshold))]
    (is (some? (value/datum (propagation/value-at mem :rest))))
    (is (some? (value/datum (propagation/value-at mem :selection))))
    (is (optimize/finite? (:dl result)))
    (is (number? threshold-opt))))

(deftest compression-step-finds-setitem-lessthan-solution
  (let [{:keys [target factor threshold]} (iris-fixture)
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

(deftest greedy-single-factor-with-solution-reaches-threshold
  (let [fixture (iris-fixture)
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

(deftest greedy-single-factor-without-solution-reaches-threshold
  (let [fixture (iris-fixture)
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
