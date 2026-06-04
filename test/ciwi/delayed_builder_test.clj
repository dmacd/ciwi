(ns ciwi.delayed-builder-test
  (:require [ciwi.delayed-builder :as sut]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]
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
  (value/datum (:value (get mem node-id))))

(defn- approx-vector=
  [left right]
  (and (= (count left) (count right))
       (every? (fn [[x y]]
                 (< (abs (- (double x) (double y))) 1.0e-9))
               (map vector left right))))

(deftest delayed-dag-build-simple
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
           (graph/value-data (:graph result) (:root result))))
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
                        (graph/value-data g' (:root result))))
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
           (graph/value-data g' generated-id)))
    (is (= 3
           (value-at (:memory result) generated-id)))))

(deftest build-info-ordering-uses-description-length
  (let [info1 (sut/build-info {:dl 5.0})
        info2 (sut/build-info {:dl 10.0})
        info3 (sut/build-info {:dl 5.0
                               :element-index 1})]
    (is (neg? (sut/compare-build-info info1 info2)))
    (is (not (neg? (sut/compare-build-info info2 info1))))
    (is (sut/same-build-rank? info1 info3))
    (is (not (sut/same-build-rank? info1 info2)))))
