(ns ciwi.mdl-test
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as sut]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is testing]]))

(defn- approx=
  [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1.0e-9))

(deftest chooses-shorter-operator-description
  (let [[g _] (-> (graph/empty-graph)
                  (graph/add-value :out [0 1 2 3 4])
                  (graph/add-derived-option :out op/brange [0 5]))
        result (sut/node-dl g :out)]
    (is (< (:dl result)
           (:dl (sut/node-dl (graph/add-value (graph/empty-graph) :out [0 1 2 3 4])
                             :out))))
    (is (= [:out-brange] (sut/selected-operators g :out)))))


(defn- with-dl
  [operator dl]
  (assoc operator :dl dl))

(def cheap-add (with-dl op/add 0.1))
(def cheap-sub (with-dl op/sub 0.1))
(def cheap-mult (with-dl op/mult 0.1))
(def expensive-add (with-dl op/add 1000.0))
(def expensive-concat (with-dl op/concat 1000.0))

(deftest raw-description-wins-when-every-option-is-more-expensive
  (let [[g op-id] (-> (graph/empty-graph)
                      (graph/add-value :out 3)
                      (graph/add-derived-option :out expensive-add [1 2]))
        result (sut/node-dl g :out)]
    (is (= :raw (get-in result [:choice :kind])))
    (is (empty? (sut/selected-operators g :out)))
    (is (= 3 (sut/selected-expression g :out)))
    (is (contains? (set (:options (graph/node g :out))) op-id))))

(deftest lower-dl-alternative-option-is-selected
  (let [g0 (-> (graph/empty-graph)
               (graph/add-value :out [0 1 2 3 4 5 6 7]))
        [g1 expensive-id] (graph/add-derived-option g0 :out expensive-concat
                                                    [[0 1 2 3] [4 5 6 7]])
        [g2 cheap-id] (graph/add-derived-option g1 :out op/brange [0 8])
        result (sut/node-dl g2 :out)]
    (is (= :operator (get-in result [:choice :kind])))
    (is (= cheap-id (get-in result [:choice :op-id])))
    (is (not= expensive-id (get-in result [:choice :op-id])))
    (is (= [cheap-id] (sut/selected-operators g2 :out)))
    (is (= [:brange 0 8] (sut/selected-expression g2 :out)))))

(deftest nested-selected-operators-are-collected-in-preorder
  (let [g0 (-> (graph/empty-graph)
               (graph/add-value :out [2 5 8 11 14 17])
               (graph/add-value :scaled [0 3 6 9 12 15])
               (graph/add-value :base [0 1 2 3 4 5])
               (graph/add-value :step 3)
               (graph/add-value :start 2))
        [g1 base-id] (graph/add-derived-option g0 :base op/brange [0 6])
        g2 (-> g1
               (graph/add-operator :scaled-mult cheap-mult :scaled [:base :step])
               (graph/add-operator :out-add cheap-add :out [:scaled :start]))]
    (is (= [:out-add :scaled-mult base-id]
           (sut/selected-operators g2 :out)))
    (is (= [:add [:mult [:brange 0 6] 3] 2]
           (sut/selected-expression g2 :out)))))

(deftest graph-dl-sums-independent-root-descriptions
  (let [[g _] (-> (graph/empty-graph)
                  (graph/add-value :range [0 1 2 3])
                  (graph/add-value :raw-large [100 200 300])
                  (graph/add-derived-option :range op/brange [0 4]))]
    (is (= (+ (:dl (sut/node-dl g :range))
              (:dl (sut/node-dl g :raw-large)))
           (sut/graph-dl g)))
    (is (= #{:range :raw-large}
           (set (graph/roots g))))))

(deftest graph-dl-charges-selected-shared-children-once
  (let [g0 (-> (graph/empty-graph)
               (graph/add-value :left 1000)
               (graph/add-value :right 1001)
               (graph/add-value :shared [0 1 2 3 4 5 6 7])
               (graph/add-value :a [0 1 2 3 4 5 6 7 1000])
               (graph/add-value :b [0 1 2 3 4 5 6 7 1001]))
        [g1 _] (graph/add-derived-option g0 :shared op/brange [0 8])
        g2 (-> g1
               (graph/add-operator :a-concat op/concat :a [:shared :left])
               (graph/add-operator :b-concat op/concat :b [:shared :right]))
        shared-dl (:dl (sut/node-dl g2 :shared))
        root-summed-dl (+ (:dl (sut/node-dl g2 :a))
                          (:dl (sut/node-dl g2 :b)))
        shared-graph-dl (+ (:dl (:operator (graph/node g2 :a-concat)))
                           (:dl (:operator (graph/node g2 :b-concat)))
                           shared-dl
                           (value/desc-len (value/value 1000))
                           (value/desc-len (value/value 1001)))]
    (testing "selected structure reuses the shared value node"
      (is (= [:concat [:brange 0 8] 1000]
             (sut/selected-expression g2 :a)))
      (is (= [:concat [:brange 0 8] 1001]
             (sut/selected-expression g2 :b))))
    (testing "graph-level MDL charges the selected shared node once"
      (is (approx= shared-graph-dl (sut/graph-dl g2)))
      (is (approx= (+ shared-graph-dl shared-dl) root-summed-dl)))))
