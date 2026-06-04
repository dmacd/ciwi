(ns ciwi.alice-test
  (:require [ciwi.alice :as sut]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]))

(defn- run-comparison
  ([task]
   (sut/run-task-comparison task {:bounded-opts {:parallel? true
                                                 :re-eval-budget 64}}))
  ([task opts]
   (sut/run-task-comparison task opts)))

(defn- expression-operator-ids
  [expr]
  (cond
    (and (vector? expr)
         (seq expr)
         (contains? sut/basic-operator-registry (first expr)))
    (apply set/union
           #{(first expr)}
           (map expression-operator-ids (rest expr)))

    (sequential? expr)
    (apply set/union #{} (map expression-operator-ids expr))

    (contains? sut/basic-operator-registry expr)
    #{expr}

    :else
    #{}))

(defn- selected-operator-ids
  [comparison]
  (apply set/union
         #{}
         (for [mode [:exhaustive :bounded]
               expr (vals (get-in comparison [mode :selected]))]
           (expression-operator-ids expr))))

(deftest alice-basic-operator-basis-matches-python-test-alice
  (is (= [:map :fix :brange :add :mult :negate :concat :repeat
          :getitem :insert :cumsum :lessthan :equal]
         sut/basic-operator-ids))
  (is (= (set sut/basic-operator-ids)
         (set (keys sut/basic-operator-registry)))))

(deftest alice-task-compresses-supported-sequence-patterns
  (let [tasks [(sut/compression-task [[0 1 2 3 4 5 6 7]]
                                     {:name "range"
                                      :threshold-rate 1.0})
               (sut/compression-task [(vec (take 10 (cycle [140 -50])))]
                                     {:name "simple_repeat"
                                      :threshold-rate 1.0})
               (sut/compression-task [(vec (repeat 12 :z))]
                                     {:name "constant-repeat"
                                      :threshold-rate 1.0})
               (sut/compression-task [(vec (concat (repeat 4 45)
                                                   (repeat 6 87)))]
                                     {:name "insert_repeat"
                                      :threshold-rate 1.0})
               (sut/compression-task [[2 5 8 11 14 17]]
                                     {:name "simply_linear"
                                      :threshold-rate 1.0})
               (sut/compression-task [[0 -1 -2 -3 -4 -5]]
                                     {:name "map_negate_equivalent"
                                      :threshold-rate 1.0})
               (sut/compression-task [[0 1 3 6 10 15]]
                                     {:name "cumsum"
                                      :threshold-rate 1.0})]
        results (mapv run-comparison tasks)]
    (is (every? :same-selected? results))
    (is (every? :same-dl? results))
    (is (every? :meets-threshold? results))
    (is (every? #(set/subset? (selected-operator-ids %)
                              (set sut/basic-operator-ids))
                results))
    (is (= [:brange 0 8]
           (get-in results [0 :exhaustive :selected :target0])))
    (is (= [:repeat 5 [140 -50]]
           (get-in results [1 :exhaustive :selected :target0])))
    (is (= [:repeat 12 [:z]]
           (get-in results [2 :exhaustive :selected :target0])))
    (is (= [:insert [:brange 0 4] 45 [:repeat 6 [87]]]
           (get-in results [3 :exhaustive :selected :target0])))
    (is (= [:add [:mult [:brange 0 6] 3] 2]
           (get-in results [4 :exhaustive :selected :target0])))
    (is (= [:mult [:brange 0 6] -1]
           (get-in results [5 :exhaustive :selected :target0])))
    (is (= [:cumsum [:brange 0 6]]
           (get-in results [6 :exhaustive :selected :target0])))))


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
