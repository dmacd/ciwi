(ns ciwi.delayed-builder-test
  (:require [ciwi.delayed-builder :as sut]
            [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
            [ciwi.test-helpers :as h]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(defn- one-value-graph
  [node-id data]
  (-> (graph/empty-graph)
      (graph/add-value node-id data)))

(defn- memory
  [& pairs]
  (into {}
        (map (fn [[node-id data]]
               [node-id (propagation/entry data)]))
        pairs))

(defn- value-at
  [mem node-id]
  (h/plain-missing (value/datum (:value (get mem node-id)))))

(defn- data-at
  [graph node-id]
  (h/plain-missing (graph/value-data graph node-id)))

(defn- approx-vector=
  [left right]
  (let [right (h/plain-missing right)]
    (and (= (count left) (count right))
       (every? (fn [[x y]]
                 (< (abs (- (double x) (double y))) 1.0e-9))
               (map vector left right)))))

(defn- contains-identical?
  [needle x]
  (boolean
   (some #(identical? needle %)
         (tree-seq #(and (coll? %)
                         (not (dense/ndarray? %)))
                   seq
                   x))))

(defn- delayed-brange-build
  [data]
  (let [g (graph/set-roots (one-value-graph :d data) [:d])
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:d data])
                              :conditioned-nodes [:d]
                              :condition-key [:array-int]})
        element (sut/graph-element op/brange
                                   [-1]
                                   {:arity 2
                                    :input-specs [:int :int]
                                    :output-spec :array-int
                                    :dl 8.000000773956048})]
    (first (sut/delayed-dag-build info {[:array-int] [element]} #{}))))

(deftest python-delayed-dag-build-simple-and-mult-fixtures
  ;; Python's "with_mult" test uses the same first Array[int] element as the
  ;; simple test. In the current Python Wunderbaum element order that element is
  ;; output-conditioned BRange, so both fixtures materialize brange(start, stop).
  (doseq [{:keys [name data expected-args]}
          [{:name "simple"
            :data [1 2 3]
            :expected-args [1 4]}
           {:name "with_mult"
            :data [2 3 4]
            :expected-args [2 5]}]]
    (let [result (delayed-brange-build data)
          g' (:graph result)
          op-node (graph/node g' (:operator-id result))
          [start-id stop-id] (:children op-node)]
      (is (some? result) name)
      (is (= :d (:root result)) name)
      (is (= :brange (get-in op-node [:operator :id])) name)
      (is (= [:d] (graph/roots g')) name)
      (is (= data (data-at g' :d)) name)
      (is (= expected-args
             [(data-at g' start-id)
              (data-at g' stop-id)])
          name)
      (is (= expected-args
             [(value-at (:memory result) start-id)
              (value-at (:memory result) stop-id)])
          name))))

(deftest delayed-dag-build-forward-attachment-smoke-test
  (let [g (one-value-graph :d [2 3 4])
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:d [2 3 4]])
                              :conditioned-nodes [:d]
                              :condition-key [:array]})
        element (sut/graph-element op/negate [0] {:arity 1})
        [result] (sut/delayed-dag-build info {[:array] [element]} #{})]
    (is (map? result))
    (is (= [-2 -3 -4]
           (data-at (:graph result) (:root result))))
    (is (seq (graph/leaves (:graph result) (:root result))))
    (is (every? #(contains? (:memory result) %)
                (graph/leaves (:graph result) (:root result))))))

(deftest delayed-dag-build-supports-same-node-for-both-inputs
  (let [d [1.0 -2.2 3.0]
        g (one-value-graph :d d)
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:d d])
                              :conditioned-nodes [:d :d]
                              :condition-key [:array :array]})
        element (sut/graph-element op/mult [0 1] {:arity 2})
        [result] (sut/delayed-dag-build info {[:array :array] [element]} #{})
        g' (:graph result)
        op-node (graph/node g' (:operator-id result))]
    (is (approx-vector= [1.0 4.84 9.0]
                        (data-at g' (:root result))))
    (is (= [:d :d]
           (:children op-node)))
    (is (= [(:operator-id result)]
           (:parents (graph/node g' :d))))))

(deftest delayed-dag-build-can-expand-a-conditioned-output-through-inverse
  (let [g (-> (graph/empty-graph)
              (graph/add-value :out 8)
              (graph/add-value :known 5))
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:out 8] [:known 5])
                              :conditioned-nodes [:out :known]
                              :condition-key [:output :known]})
        element (sut/graph-element op/add [-1 0] {:arity 2})
        [result] (sut/delayed-dag-build info {[:output :known] [element]} #{})
        g' (:graph result)
        op-node (graph/node g' (:operator-id result))
        generated-id (second (:children op-node))]
    (is (= :out (:root result)))
    (is (= [:known generated-id]
           (:children op-node)))
    (is (= 3
           (data-at g' generated-id)))
    (is (= 3
           (value-at (:memory result) generated-id)))))

(deftest delayed-dag-build-skips-non-executable-inverses
  (let [bad-inverse (op/operator
                     {:id :bad-inverse
                      :conditions [[]]
                      :call (fn [[x]]
                              x)
                      :inverse (fn [_output _cond-inputs _cond]
                                 (throw (UnsupportedOperationException.
                                         "invalid inverse probe")))})
        g (one-value-graph :out 7)
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:out 7])
                              :conditioned-nodes [:out]
                              :condition-key [:output]})
        element (sut/graph-element bad-inverse [-1] {:arity 1})]
    (is (empty? (sut/delayed-dag-build info {[:output] [element]} #{})))))

(deftest delayed-dag-build-rejects-inverse-values-with-wrong-spec
  (let [g (one-value-graph :out [0 1 0])
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:out [0 1 0]])
                              :conditioned-nodes [:out]
                              :condition-key [:array-int]})
        element (sut/graph-element op/getitem
                                   [-1]
                                   {:arity 2
                                    :input-specs [:array-int :array-bool]
                                    :output-spec :array-int})]
    (is (empty? (sut/delayed-dag-build info {[:array-int] [element]} #{})))))

(deftest delayed-dag-build-skips-generated-values-already-in-memory
  (let [reuse-existing (op/operator
                        {:id :reuse-existing
                         :conditions [[]]
                         :call (fn [[_x]] 8)
                         :inverse (fn [_output _cond-inputs cond]
                                    (when (empty? cond)
                                      [[5]]))})
        g (-> (graph/empty-graph)
              (graph/add-value :out 8)
              (graph/add-value :free 5))
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:out 8] [:free 5])
                              :conditioned-nodes [:out]
                              :condition-key [:int]})
        element (sut/graph-element reuse-existing
                                   [-1]
                                   {:arity 1
                                    :input-specs [:int]
                                    :output-spec :int})]
    (is (empty? (sut/delayed-dag-build info {[:int] [element]} #{})))))

(deftest delayed-dag-build-skips-forward-values-already-in-memory
  (let [identity-op (op/operator
                     {:id :identity
                      :call (fn [[x]] x)})
        g (one-value-graph :d 8)
        info (sut/build-info {:dl 8.0
                              :graph g
                              :memory (memory [:d 8])
                              :conditioned-nodes [:d]
                              :condition-key [:int]})
        element (sut/graph-element identity-op
                                   [0]
                                   {:arity 1
                                    :input-specs [:int]
                                    :output-spec :int})]
    (is (empty? (sut/delayed-dag-build info {[:int] [element]} #{})))))

(deftest result-key-fingerprints-dense-values-without-retaining-raw-arrays
  (let [arr1 (dense/from-flat [1.0 2.0 3.0 4.0] [2 2] {:dtype :float64})
        arr2 (dense/from-flat [1.0 2.0 3.0 4.0] [2 2] {:dtype :float64})
        graph1 (-> (one-value-graph :d arr1)
                   (graph/set-roots [:d]))
        graph2 (-> (one-value-graph :d arr2)
                   (graph/set-roots [:d]))
        key1 (sut/result-key {:graph graph1
                              :memory (memory [:d arr1])})
        key2 (sut/result-key {:graph graph2
                              :memory (memory [:d arr2])})]
    (is (= key1 key2))
    (is (not (contains-identical? arr1 key1)))))

(deftest build-info-ordering-uses-description-length
  (let [info1 (sut/build-info {:dl 5.0})
        info2 (sut/build-info {:dl 10.0})
        info3 (sut/build-info {:dl 5.0
                               :element-index 1})]
    (is (neg? (sut/compare-build-info info1 info2)))
    (is (not (neg? (sut/compare-build-info info2 info1))))
    (is (sut/same-build-rank? info1 info3))
    (is (not (sut/same-build-rank? info1 info2)))))
