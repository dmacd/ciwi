(ns ciwi.alice-test
  (:require [ciwi.alice :as sut]
            [ciwi.graph-rewrite :as graph-rewrite]
            [ciwi.mdl :as mdl]
            [ciwi.rewrite :as rewrite]
            [clojure.test :refer [deftest is testing]]))

(defn- run-comparison
  ([task]
   (sut/run-task-comparison task {:bounded-opts {:parallel? true
                                                 :re-eval-budget 64}}))
  ([task opts]
   (sut/run-task-comparison task opts)))

(deftest alice-task-compresses-supported-sequence-patterns
  (let [tasks [(sut/compression-task [[0 1 2 3 4 5 6 7]]
                                     {:name "range"
                                      :threshold-rate 1.0})
               (sut/compression-task [(vec (repeat 12 :z))]
                                     {:name "constant-repeat"
                                      :threshold-rate 1.0})
               (sut/compression-task [[2 5 8 11 14 17]]
                                     {:name "affine"
                                      :threshold-rate 1.0})
               (sut/compression-task [[0 -1 -2 -3 -4 -5]]
                                     {:name "negated-range"
                                      :threshold-rate 1.0})]
        results (mapv run-comparison tasks)]
    (is (every? :same-selected? results))
    (is (every? :same-dl? results))
    (is (every? :meets-threshold? results))
    (is (= [:brange 0 8]
           (get-in results [0 :exhaustive :selected :target0])))
    (is (= [:repeat :z 12]
           (get-in results [1 :exhaustive :selected :target0])))
    (is (= [:add [:mult [:brange 0 6] 3] 2]
           (get-in results [2 :exhaustive :selected :target0])))
    (is (= [:mult [:brange 0 6] -1]
           (get-in results [3 :exhaustive :selected :target0])))))


(defn- setitem-mask-rewrite-operator
  []
  (graph-rewrite/graph-rewrite-operator
   {:id :alice-setitem-mask
    :operators [{:op :lessthan :arity 2}
                {:op :setitem :arity 3}]
    :literal-values ["--------" 2 ["xxxxxxxx" "xxxxxxxx"]]
    :max-depth 1
    :max-generated 1000
    :beam-width 128}))

(deftest alice-task-compresses-local-setitem-mask-pattern
  (let [task (sut/compression-task
              [["xxxxxxxx" "xxxxxxxx" "--------" "--------"]
               [0 1 2 3]
               ["--------" "--------" "--------" "--------"]
               [true true false false]]
              {:name "setitem-mask"
               :threshold-rate 1.0})
        opts {:parallel? true
              :max-steps 8
              :re-eval-budget 64
              :rewrite-operators [(rewrite/primitive-template-operator)
                                  (setitem-mask-rewrite-operator)]}
        result (sut/run-task-comparison task {:exhaustive-opts opts
                                              :bounded-opts opts})]
    (is (:same-selected? result))
    (is (:same-dl? result))
    (is (:meets-threshold? result))
    (is (= [:setitem [:repeat "--------" 4]
            [:lessthan [:brange 0 4] 2]
            [:repeat "xxxxxxxx" 2]]
           (get-in result [:bounded :selected :target0])))
    (is (= [:lessthan [:brange 0 4] 2]
           (get-in result [:bounded :selected :target3])))
    (testing "task-level result exposes enough evidence for Alice parity checks"
      (is (pos? (get-in result [:bounded :compression-rate])))
      (is (= :fixed-point
             (get-in result [:bounded :result :stopped])))
      (is (= (get-in result [:bounded :dl])
             (mdl/graph-dl (get-in result [:bounded :result :graph])))))))


(deftest alice-domain-runs-task-comparisons
  (let [domain (sut/task-domain
                "supported-sequence-subset"
                [(sut/compression-task [[0 1 2 3 4]]
                                       {:name "range"
                                        :threshold-rate 1.0})
                 (sut/compression-task [[10 12 14 16 18]]
                                       {:name "affine"
                                        :threshold-rate 1.0})]
                {:opts {:bounded-opts {:parallel? false
                                        :re-eval-budget 64}}})
        result (sut/run-domain domain)]
    (is (= "supported-sequence-subset" (:domain-name result)))
    (is (= ["range" "affine"]
           (mapv :task-name (:results result))))
    (is (every? :same-selected? (:results result)))
    (is (every? :meets-threshold? (:results result)))))
