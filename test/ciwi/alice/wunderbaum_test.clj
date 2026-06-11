(ns ciwi.alice.wunderbaum-test
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum :as sut]
            [ciwi.operator :as op]
            [clojure.test :refer [deftest is testing]]))

(def python-alice-operator-ids
  [:map :fix :brange :add :mult :negate :concat :repeat
   :getitem :insert :cumsum :lessthan :equal])

(def python-sprinkled-indices
  [436 634 675 761 851 883 915 933 971 1270 1295 1397 1536 1604 1642
   1811 1889 1937 1996 2256 2264 2394 2750 2882 3119 3247 3294 3525
   3621 3652 3679 3692 3872 3994 4094 4289 4346 4348 4363 4403 4433
   4467 4473 4616 4645 4681 4745 4955 4963 5089 5217 5415 5442 5509
   5633 6278 6288 6366 6391 6482 6684 6744 6803 6823 6847 6909 6965
   6992 7113 7293 7403 7419 7536 7545 7561 7663 7669 7744 7757 7794
   7933 8041 8168 8224 8313 8314 8329 8411 8505 8528 8821 8884 8976
   9025 9189 9197 9385 9640 9654 9670])

(defn- run-python-scale-sequence-task
  [task opts]
  (sut/run-greedy-task
   task
   (merge {:registry alice/basic-operator-registry
           :operator-ids python-alice-operator-ids
           :max-dag-dl 35}
          opts)))

(defn- repeat-with-noise-task
  []
  (alice/compression-task [(vec (concat (repeat 100 45)
                                        [-1]
                                        (repeat 400 45)))]
                          {:name "repeat_with_noise"
                           :threshold-rate 0.90}))

(defn- insert-repeat3-task
  []
  (alice/compression-task [(vec (concat (repeat 100 45)
                                        (take 500 (cycle [87 62]))
                                        (repeat 610 164)))]
                          {:name "insert_repeat3"
                           :threshold-rate 0.93}))

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

(defn- close-to?
  [expected actual]
  (< (Math/abs (- (double expected) (double actual)))
     1.0e-6))

(defn- python-scale-sequence-cases
  []
  [{:name "simple_repeat"
    :target (vec (take 1000 (cycle [140 -50])))
    :expected [:insert
               [:cumsum [:insert [0] 0 (vec (repeat 499 2))]]
               140
               (vec (repeat 500 -50))]
    :expected-steps 3
    :threshold-rate 0.94
    :opts {:max-popped 200
           :max-yields 20}}
   {:name "insert_repeat"
    :target (vec (concat (repeat 100 45)
                          (repeat 250 87)))
    :expected [:insert
               [:cumsum (vec (concat [0] (repeat 99 1)))]
               45
               (vec (repeat 250 87))]
    :expected-steps 2
    :threshold-rate 0.92
    :opts {:max-popped 10000
           :max-yields 1000}}
   {:name "insert_repeat2"
    :target (vec (concat (repeat 10 45)
                          (repeat 25 87)
                          (repeat 610 164)))
    :expected [:insert
               [:concat (vec (range 10)) (vec (range 10 35))]
               [:insert (vec (range 10))
                45
                (vec (repeat 25 87))]
               (vec (repeat 610 164))]
    :expected-steps 3
    :threshold-rate 0.92
    :opts {:max-popped 10000
           :max-yields 1000}}
   {:name "repeat_with_noise"
    :target (first (:targets (repeat-with-noise-task)))
    :expected [:insert [100] -1 (vec (repeat 500 45))]
    :expected-steps 1
    :threshold-rate 0.90
    :opts {:max-popped 5000
           :max-yields 500}}
   {:name "simply_linear"
    :target (mapv #(- (* 6 %) 18) (range 1000))
    :expected [:cumsum [:insert [0] -18 (vec (repeat 999 6))]]
    :expected-steps 2
    :threshold-rate 0.97
    :opts {:max-popped 10000
           :max-yields 1000}}
   {:name "sprinkled"
    :target (sprinkled-target)
    :expected [:insert
               python-sprinkled-indices
               1
               (vec (repeat 9900 0))]
    :expected-steps 1
    :threshold-rate 0.75
    :opts {:max-popped 10000
           :max-yields 1000}}
   {:name "map_negate"
    :target (vec (map - (range 1000)))
    :expected [:cumsum [:insert [0] 0 (vec (repeat 999 -1))]]
    :expected-steps 2
    :threshold-rate 0.98
    :opts {:max-popped 10000
           :max-yields 1000}}
   {:name "increasing_runs"
    :target (increasing-runs-target)
    :expected [:insert
               [:cumsum
                [:cumsum
                 (vec (concat [0 2] (repeat 498 1)))]]
               64
               (vec (repeat 124750 123))]
    :expected-steps 3
    :threshold-rate 0.999
    :opts {:max-popped 10000
           :max-yields 1000}}])

(deftest alice-wunderbaum-requires-injected-registry
  (let [task (alice/compression-task [[0 1 2 3]]
                                     {:name "range"})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires an injected operator registry"
         (sut/run-greedy-task task {:max-popped 8})))))

(deftest declarations-are-filtered-to-the-injected-registry
  (let [declarations (sut/declarations-for-registry {:repeat op/repeat})]
    (is (seq declarations))
    (is (= #{:repeat}
           (set (map :op declarations))))))

(deftest alice-rate-values-are-fractions
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Rate values must be fractions"
       (alice/compression-task [[0 1 2]]
                               {:threshold-rate 90.0})))
  (let [task (alice/compression-task [[0 1 2 3]]
                                     {:threshold-rate 0.01})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Rate values must be fractions"
         (sut/run-greedy-task task {:registry {:brange op/brange}
                                    :min-compression-rate 1.5
                                    :max-popped 8})))))

(deftest alice-leaf-selection-policy-is-explicit
  (is (= #{:python-test-parity :largest-dl}
         sut/leaf-selection-policies))
  (let [task (alice/compression-task [[0 1 2 3]]
                                     {:threshold-rate 0.01})
        result (sut/run-greedy-task task {:registry {:brange op/brange}
                                          :leaf-selection-policy :largest-dl
                                          :max-popped 16
                                          :max-yields 4
                                          :worthy-dl 0})]
    (is (= :largest-dl
           (get-in result [:resource :leaf-selection-policy])))))

(deftest alice-wunderbaum-compresses-arithmetic-range
  (let [task (alice/compression-task [[0 1 2 3 4 5 6 7]]
                                     {:name "range"
                                      :threshold-rate 0.01})
        result (sut/run-greedy-task task {:registry {:brange op/brange}
                                          :max-popped 16
                                          :max-yields 4
                                          :worthy-dl 0})]
    (is (:meets-threshold? result))
    (is (= [:brange 0 8]
           (get-in result [:selected :target0])))
    (is (= #{:brange}
           (set (map :op (sut/declarations-for-registry {:brange op/brange})))))))

(deftest alice-wunderbaum-compresses-motif-repeat
  (let [target (vec (take 20 (cycle [140 -50])))
        task (alice/compression-task [target]
                                     {:name "repeat"
                                      :threshold-rate 0.01})
        result (sut/run-greedy-task task {:registry {:repeat op/repeat}
                                          :max-popped 32
                                          :max-yields 8
                                          :worthy-dl 0})]
    (is (:meets-threshold? result))
    (is (= [:repeat 10 [140 -50]]
           (get-in result [:selected :target0])))))

(deftest alice-wunderbaum-parallel-compresses-motif-repeat
  (let [target (vec (take 20 (cycle [140 -50])))
        task (alice/compression-task [target]
                                     {:name "repeat"
                                      :threshold-rate 0.01})
        result (sut/run-greedy-task task {:registry {:repeat op/repeat}
                                          :parallelism 2
                                          :max-popped 32
                                          :max-yields 8
                                          :worthy-dl 0})]
    (is (:meets-threshold? result))
    (is (= [:repeat 10 [140 -50]]
           (get-in result [:selected :target0])))))

(deftest alice-wunderbaum-compresses-python-scale-sequence-rows
  (doseq [{:keys [name target expected expected-steps threshold-rate opts]}
          (python-scale-sequence-cases)]
    (testing name
      (let [task (alice/compression-task [target]
                                         {:name name
                                          :threshold-rate threshold-rate})
            result (run-python-scale-sequence-task task opts)]
        (is (:meets-threshold? result))
        (is (>= (:compression-rate result) threshold-rate))
        (is (= expected
               (get-in result [:selected :target0])))
        (is (= :greedy-task
               (get-in result [:resource :mode])))
        (is (= expected-steps
               (count (:steps result))))
        (is (= (count (:steps result))
               (get-in result [:resource :candidates-consumed])))))))

(deftest alice-wunderbaum-parallel-completes-python-scale-sequence-rows
  (doseq [{:keys [name target threshold-rate opts]}
          (python-scale-sequence-cases)]
    (testing name
      (let [task (alice/compression-task [target]
                                         {:name name
                                          :threshold-rate threshold-rate})
            result (run-python-scale-sequence-task task
                                                   (assoc opts
                                                          :num-workers 8))]
        (is (= :greedy-task
               (get-in result [:resource :mode])))
        (is (contains? #{:threshold-reached
                         :leaf-below-worthy
                         :no-worthy-leaves
                         :exhausted}
                       (get-in result [:resource :stop-reason])))
        (is (number? (:compression-rate result)))
        (is (<= 0.0 (:compression-rate result)))
        (is (<= (:dl result) (:initial-dl result)))
        (is (pos? (count (:steps result))))))))

(deftest alice-wunderbaum-repeat-with-noise-step-reaches-task-threshold
  (let [opts {:registry alice/basic-operator-registry
              :operator-ids python-alice-operator-ids
              :max-dag-dl 35
              :max-popped 5000
              :max-yields 500}
        task (repeat-with-noise-task)
        step (sut/run-compression-step task opts)
        task-result (sut/run-greedy-task task opts)]
    (is (>= (:compression-rate step) 0.01))
    (is (:meets-threshold? step))
    (is (:meets-threshold? task-result))
    (is (= (get-in step [:resource :candidates-consumed])
           (get-in task-result [:resource :candidates-consumed])))
    (is (= (:selected step)
           (:selected task-result)))))

(deftest alice-wunderbaum-insert-repeat3-reaches-python-fourth-step
  (let [result (run-python-scale-sequence-task
                (insert-repeat3-task)
                {:max-popped 10000
                 :max-yields 1000
                 :max-steps 4})
        fourth-step (nth (:steps result) 3)
        selected (:selected fourth-step)]
    (is (= :max-steps
           (get-in result [:resource :stop-reason])))
    (is (= 4 (count (:steps result))))
    (is (= 4 (get-in result [:resource :candidates-consumed])))
    (is (close-to? 0.8638075946321129
                   (:compression-rate result)))
    (is (= [0 1 0] (:path fourth-step)))
    (is (close-to? 1161.7371134088858 (:dl fourth-step)))
    (is (= :insert (first selected)))
    (is (= :cumsum (get-in selected [1 0])))
    (is (= :insert (get-in selected [1 1 0])))))
