(ns ciwi.alice-legacy-test
  (:require [ciwi.alice :as alice]
            [ciwi.alice-legacy :as legacy]
            [clojure.test :refer [deftest is testing]]))

(defn- run-comparison
  ([task]
   (legacy/run-task-comparison task {:bounded-opts {:parallel? true
                                                    :re-eval-budget 64}}))
  ([task opts]
   (legacy/run-task-comparison task opts)))

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

(def legacy-local-baseline-cases
  [{:name "simple_repeat"
    :status :legacy-local-baseline
    :length 1000
    :target-fn simple-repeat-target
    :python-threshold-rate 0.94
    :ciwi-threshold-rate 0.01
    :python-serial-ms 1061
    :recognizer-ciwi-ms 87
    :required #{:repeat}
    :recognizer-solution [:repeat 500 [140 -50]]}
   {:name "insert_repeat"
    :status :legacy-local-baseline
    :length 350
    :target-fn insert-repeat-target
    :python-threshold-rate 0.92
    :ciwi-threshold-rate 0.01
    :python-serial-ms 51
    :recognizer-ciwi-ms 18
    :required #{:insert :repeat}
    :recognizer-solution [:insert [:brange 0 100] 45 [:repeat 250 [87]]]}
   {:name "insert_repeat2"
    :status :legacy-local-baseline
    :length 645
    :target-fn insert-repeat2-target
    :python-threshold-rate 0.92
    :ciwi-threshold-rate 0.01
    :python-serial-ms 44
    :recognizer-ciwi-ms 23
    :required #{:insert :repeat}
    :forbidden #{:cumsum}
    :recognizer-solution [:insert [:brange 0 35]
                          [:insert [:brange 0 10] 45 [:repeat 25 [87]]]
                          [:repeat 610 [164]]]}
   {:name "insert_repeat3"
    :status :legacy-local-baseline
    :length 1210
    :target-fn insert-repeat3-target
    :python-threshold-rate 0.93
    :ciwi-threshold-rate 0.01
    :python-serial-ms 10677
    :recognizer-ciwi-ms 105
    :required #{:insert :repeat}
    :forbidden #{:cumsum}}
   {:name "repeat_with_noise"
    :status :legacy-local-baseline
    :length 501
    :target-fn repeat-with-noise-target
    :python-threshold-rate 0.90
    :ciwi-threshold-rate 0.01
    :python-serial-ms 6
    :recognizer-ciwi-ms 16
    :required #{:insert :repeat}
    :recognizer-solution [:insert [100] -1 [:repeat 500 [45]]]}
   {:name "simply_linear"
    :status :legacy-local-baseline
    :length 1000
    :target-fn simply-linear-target
    :python-threshold-rate 0.97
    :ciwi-threshold-rate 0.01
    :python-serial-ms 12
    :recognizer-ciwi-ms 122
    :required #{:brange :mult :add}
    :recognizer-solution [:add [:mult [:brange 0 1000] 6] -18]}
   {:name "sprinkled"
    :status :legacy-local-baseline
    :length 10000
    :target-fn sprinkled-target
    :python-threshold-rate 0.75
    :ciwi-threshold-rate 0.01
    :python-serial-ms 6
    :recognizer-ciwi-ms 249
    :required #{:insert :repeat}
    :performance-note "Prior runtime was roughly 84s due to CIWI-only unconditioned concat split enumeration; current runtime is still slower than Python."}
   {:name "increasing_runs"
    :status :legacy-local-baseline
    :length 125250
    :target-fn increasing-runs-target
    :python-threshold-rate 0.999
    :python-serial-ms 88
    :recognizer-ciwi-ms 2705
    :required #{:insert :repeat}
    :performance-note "Current CIWI run completes, but remains slower and less compressed than Python because marker indices stay raw."}
   {:name "map_negate"
    :status :legacy-local-baseline
    :length 1000
    :target-fn map-negate-target
    :python-threshold-rate 0.98
    :ciwi-threshold-rate 0.01
    :python-serial-ms 12
    :recognizer-ciwi-ms 66
    :required #{:brange :mult}
    :recognizer-solution [:mult [:brange 0 1000] -1]}])

(defn- task-from-baseline-case
  [{:keys [name target-fn ciwi-threshold-rate python-threshold-rate]}]
  (alice/compression-task [(target-fn)]
                          {:name name
                           :threshold-rate (or ciwi-threshold-rate 0.01)
                           :metadata {:python-threshold-rate python-threshold-rate}}))

(deftest alice-basic-operator-basis-matches-python-test-alice
  (is (= [:map :fix :brange :add :mult :negate :concat :repeat
          :getitem :insert :cumsum :lessthan :equal]
         alice/basic-operator-ids))
  (is (= (set alice/basic-operator-ids)
         (set (keys alice/basic-operator-registry)))))

(deftest alice-legacy-local-baseline-matrix-is-explicit
  (is (= ["simple_repeat" "insert_repeat" "insert_repeat2" "insert_repeat3"
          "repeat_with_noise" "simply_linear" "sprinkled" "increasing_runs"
          "map_negate"]
         (mapv :name legacy-local-baseline-cases)))
  (is (= #{:legacy-local-baseline}
         (set (map :status legacy-local-baseline-cases))))
  (doseq [{:keys [name status length target-fn python-threshold-rate
                  python-serial-ms recognizer-ciwi-ms performance-note]}
          legacy-local-baseline-cases]
    (testing name
      (is (= length (count (target-fn))))
      (is (= :legacy-local-baseline status))
      (is (number? python-threshold-rate))
      (is (number? python-serial-ms))
      (is (number? recognizer-ciwi-ms))
      (when (seq performance-note)
        (is (string? performance-note))))))

(deftest alice-legacy-default-search-does-not-use-recognizer-templates
  (let [case (first legacy-local-baseline-cases)
        task (task-from-baseline-case case)
        result (run-comparison task {:bounded-opts {:parallel? true
                                                    :re-eval-budget 256}
                                     :exhaustive-opts {:parallel? false}})
        expected-target ((:target-fn case))]
    (is (:same-selected? result))
    (is (:same-dl? result))
    (is (false? (:meets-threshold? result)))
    (is (= expected-target (get-in result [:exhaustive :selected :target0])))
    (is (= expected-target (get-in result [:bounded :selected :target0])))
    (is (empty? (get-in result [:exhaustive :result :history])))
    (is (empty? (get-in result [:bounded :result :history])))
    (is (zero? (get-in result [:exhaustive :resource :rewrite-operators-considered])))
    (is (zero? (get-in result [:bounded :resource :rewrite-operators-considered])))))


(deftest alice-legacy-domain-runs-task-comparisons
  (let [domain-cases (->> legacy-local-baseline-cases
                          (filter (comp #{"simple_repeat" "map_negate"} :name)))
        domain (alice/task-domain
                "python-sequence-legacy-local-baseline-subset"
                (mapv task-from-baseline-case domain-cases)
                {:opts {:bounded-opts {:parallel? false
                                        :re-eval-budget 256}}})
        result (legacy/run-domain domain)]
    (is (= "python-sequence-legacy-local-baseline-subset" (:domain-name result)))
    (is (= ["simple_repeat" "map_negate"]
           (mapv :task-name (:results result))))
    (is (every? :same-selected? (:results result)))
    (is (not-any? :meets-threshold? (:results result)))
    (is (every? #(zero? (get-in % [:exhaustive :resource :rewrite-operators-considered]))
                (:results result)))
    (is (every? #(zero? (get-in % [:bounded :resource :rewrite-operators-considered]))
                (:results result)))))
