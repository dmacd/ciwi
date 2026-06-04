(ns ciwi.alice-test
  (:require [ciwi.alice :as sut]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]))

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

(defn- increasing-runs
  [n]
  (vec (mapcat (fn [x]
                 (concat (repeat x 123) [64]))
               (range n))))

(def mirrored-sequence-cases
  [{:name "simple_repeat"
    :target (vec (take 20 (cycle [140 -50])))
    :required #{:repeat}
    :exact [:repeat 10 [140 -50]]}
   {:name "insert_repeat"
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

(deftest alice-mirrors-python-sequence-task-compression-subset
  (doseq [{:keys [name target required forbidden exact]} mirrored-sequence-cases]
    (testing name
      (let [task (sut/compression-task [target]
                                       {:name name
                                        :threshold-rate 1.0})
            result (run-comparison task {:bounded-opts {:parallel? true
                                                        :re-eval-budget 256}
                                         :exhaustive-opts {:parallel? false}})
            selected (get-in result [:exhaustive :selected :target0])
            selected-ops (selected-operator-ids result)]
        (is (:same-selected? result))
        (is (:same-dl? result))
        (is (:meets-threshold? result))
        (is (set/subset? selected-ops (set sut/basic-operator-ids)))
        (is (set/subset? required selected-ops))
        (when (seq forbidden)
          (is (empty? (set/intersection forbidden selected-ops))))
        (when exact
          (is (= exact selected)))))))


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
