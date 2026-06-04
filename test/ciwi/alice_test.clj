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

(defn- simple-repeat-target
  []
  (vec (take 1000 (cycle [140 -50]))))

(defn- insert-repeat-target
  []
  (vec (concat (repeat 100 45)
               (repeat 250 87))))

(defn- insert-repeat2-target
  []
  (vec (concat (repeat 10 45)
               (repeat 25 87)
               (repeat 610 164))))

(defn- insert-repeat3-target
  []
  (vec (concat (repeat 100 45)
               (take 500 (cycle [87 62]))
               (repeat 610 164))))

(defn- repeat-with-noise-target
  []
  (vec (concat (repeat 100 45)
               [-1]
               (repeat 400 45))))

(defn- simply-linear-target
  []
  (mapv #(- (* 6 %) 18) (range 1000)))

(def python-sprinkled-indices
  [436 634 675 761 851 883 915 933 971 1270 1295 1397 1536 1604 1642
   1811 1889 1937 1996 2256 2264 2394 2750 2882 3119 3247 3294 3525
   3621 3652 3679 3692 3872 3994 4094 4289 4346 4348 4363 4403 4433
   4467 4473 4616 4645 4681 4745 4955 4963 5089 5217 5415 5442 5509
   5633 6278 6288 6366 6391 6482 6684 6744 6803 6823 6847 6909 6965
   6992 7113 7293 7403 7419 7536 7545 7561 7663 7669 7744 7757 7794
   7933 8041 8168 8224 8313 8314 8329 8411 8505 8528 8821 8884 8976
   9025 9189 9197 9385 9640 9654 9670])

(defn- sprinkled-target
  []
  (reduce #(assoc %1 %2 1)
          (vec (repeat 10000 0))
          python-sprinkled-indices))

(defn- increasing-runs-target
  []
  (vec (mapcat (fn [x]
                 (concat (repeat x 123) [64]))
               (range 500))))

(defn- map-negate-target
  []
  (vec (map - (range 1000))))

(def python-sequence-parity-cases
  [{:name "simple_repeat"
    :status :covered
    :length 1000
    :target-fn simple-repeat-target
    :python-threshold-rate 94.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 1061
    :observed-ciwi-ms 87
    :required #{:repeat}
    :exact [:repeat 500 [140 -50]]}
   {:name "insert_repeat"
    :status :covered
    :length 350
    :target-fn insert-repeat-target
    :python-threshold-rate 92.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 51
    :observed-ciwi-ms 18
    :required #{:insert :repeat}
    :exact [:insert [:brange 0 100] 45 [:repeat 250 [87]]]}
   {:name "insert_repeat2"
    :status :covered
    :length 645
    :target-fn insert-repeat2-target
    :python-threshold-rate 92.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 44
    :observed-ciwi-ms 23
    :required #{:insert :repeat}
    :forbidden #{:cumsum}
    :exact [:insert [:brange 0 35]
            [:insert [:brange 0 10] 45 [:repeat 25 [87]]]
            [:repeat 610 [164]]]}
   {:name "insert_repeat3"
    :status :covered
    :length 1210
    :target-fn insert-repeat3-target
    :python-threshold-rate 93.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 10677
    :observed-ciwi-ms 105
    :required #{:insert :repeat}
    :forbidden #{:cumsum}}
   {:name "repeat_with_noise"
    :status :covered
    :length 501
    :target-fn repeat-with-noise-target
    :python-threshold-rate 90.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 6
    :observed-ciwi-ms 16
    :required #{:insert :repeat}
    :exact [:insert [100] -1 [:repeat 500 [45]]]}
   {:name "simply_linear"
    :status :covered
    :length 1000
    :target-fn simply-linear-target
    :python-threshold-rate 97.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 12
    :observed-ciwi-ms 122
    :required #{:brange :mult :add}
    :exact [:add [:mult [:brange 0 1000] 6] -18]}
   {:name "sprinkled"
    :status :covered
    :length 10000
    :target-fn sprinkled-target
    :python-threshold-rate 75.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 6
    :observed-ciwi-ms 249
    :required #{:insert :repeat}
    :performance-note "Prior runtime was roughly 84s due to CIWI-only unconditioned concat split enumeration; current runtime is still slower than Python."}
   {:name "increasing_runs"
    :status :performance-gap
    :length 125250
    :target-fn increasing-runs-target
    :python-threshold-rate 99.9
    :python-serial-ms 88
    :observed-ciwi-ms 2705
    :required #{:insert :repeat}
    :performance-note "Current CIWI run completes, but remains slower and less compressed than Python because marker indices stay raw."}
   {:name "map_negate"
    :status :covered
    :length 1000
    :target-fn map-negate-target
    :python-threshold-rate 98.0
    :ciwi-threshold-rate 1.0
    :python-serial-ms 12
    :observed-ciwi-ms 66
    :required #{:brange :mult}
    :exact [:mult [:brange 0 1000] -1]}])

(defn- covered-parity-case?
  [case]
  (= :covered (:status case)))

(defn- task-from-parity-case
  [{:keys [name target-fn ciwi-threshold-rate python-threshold-rate]}]
  (sut/compression-task [(target-fn)]
                        {:name name
                         :threshold-rate (or ciwi-threshold-rate 1.0)
                         :metadata {:python-threshold-rate python-threshold-rate}}))

(deftest alice-basic-operator-basis-matches-python-test-alice
  (is (= [:map :fix :brange :add :mult :negate :concat :repeat
          :getitem :insert :cumsum :lessthan :equal]
         sut/basic-operator-ids))
  (is (= (set sut/basic-operator-ids)
         (set (keys sut/basic-operator-registry)))))

(deftest alice-python-sequence-parity-matrix-is-explicit
  (is (= ["simple_repeat" "insert_repeat" "insert_repeat2" "insert_repeat3"
          "repeat_with_noise" "simply_linear" "sprinkled" "increasing_runs"
          "map_negate"]
         (mapv :name python-sequence-parity-cases)))
  (is (= #{:covered :performance-gap}
         (set (map :status python-sequence-parity-cases))))
  (doseq [{:keys [name status length target-fn python-threshold-rate
                  performance-note]} python-sequence-parity-cases]
    (testing name
      (is (= length (count (target-fn))))
      (is (number? python-threshold-rate))
      (when (= :performance-gap status)
        (is (seq performance-note))))))

(deftest alice-mirrors-python-sequence-task-compression
  (doseq [{:keys [name required forbidden exact] :as case}
          (filter covered-parity-case? python-sequence-parity-cases)]
    (testing name
      (let [task (task-from-parity-case case)
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
  (let [domain-cases (->> python-sequence-parity-cases
                          (filter (comp #{"simple_repeat" "map_negate"} :name)))
        domain (sut/task-domain
                "python-sequence-covered-subset"
                (mapv task-from-parity-case domain-cases)
                {:opts {:bounded-opts {:parallel? false
                                        :re-eval-budget 256}}})
        result (sut/run-domain domain)]
    (is (= "python-sequence-covered-subset" (:domain-name result)))
    (is (= ["simple_repeat" "map_negate"]
           (mapv :task-name (:results result))))
    (is (every? :same-selected? (:results result)))
    (is (every? :meets-threshold? (:results result)))))
