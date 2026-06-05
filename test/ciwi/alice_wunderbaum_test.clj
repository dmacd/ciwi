(ns ciwi.alice-wunderbaum-test
  (:require [ciwi.alice :as alice]
            [ciwi.alice-wunderbaum :as sut]
            [ciwi.operator :as op]
            [clojure.test :refer [deftest is testing]]))

(def python-alice-operator-ids
  [:map :fix :brange :add :mult :negate :concat :repeat
   :getitem :insert :cumsum :lessthan :equal])

(defn- run-python-scale-sequence-task
  [task opts]
  (sut/run-task-to-threshold
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
                           :threshold-rate 90.0}))

(deftest alice-wunderbaum-requires-injected-registry
  (let [task (alice/compression-task [[0 1 2 3]]
                                     {:name "range"})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires an injected operator registry"
         (sut/run-task task {:max-popped 8})))))

(deftest declarations-are-filtered-to-the-injected-registry
  (let [declarations (sut/declarations-for-registry {:repeat op/repeat})]
    (is (seq declarations))
    (is (= #{:repeat}
           (set (map :op declarations))))))

(deftest alice-wunderbaum-compresses-arithmetic-range
  (let [task (alice/compression-task [[0 1 2 3 4 5 6 7]]
                                     {:name "range"
                                      :threshold-rate 1.0})
        result (sut/run-task task {:registry {:brange op/brange}
                                   :max-popped 16
                                   :max-yields 4})]
    (is (:meets-threshold? result))
    (is (= [:brange 0 8]
           (get-in result [:selected :target0])))
    (is (= #{:brange}
           (set (map :op (sut/declarations-for-registry {:brange op/brange})))))))

(deftest alice-wunderbaum-compresses-motif-repeat
  (let [target (vec (take 20 (cycle [140 -50])))
        task (alice/compression-task [target]
                                     {:name "repeat"
                                      :threshold-rate 1.0})
        result (sut/run-task task {:registry {:repeat op/repeat}
                                   :max-popped 32
                                   :max-yields 8})]
    (is (:meets-threshold? result))
    (is (= [:repeat 10 [140 -50]]
           (get-in result [:selected :target0])))))

(deftest alice-wunderbaum-compresses-python-scale-sequence-rows
  (doseq [{:keys [name target expected threshold-rate opts]}
          [{:name "simple_repeat"
            :target (vec (take 1000 (cycle [140 -50])))
            :expected [:repeat 500 [140 -50]]
            :threshold-rate 94.0
            :opts {:max-popped 200
                   :max-yields 20}}
           {:name "repeat_with_noise"
            :target (first (:targets (repeat-with-noise-task)))
            :expected [:insert [100] -1 (vec (repeat 500 45))]
            :threshold-rate 90.0
            :opts {:max-popped 5000
                   :max-yields 500}}
           {:name "simply_linear"
            :target (mapv #(- (* 6 %) 18) (range 1000))
            :expected [:cumsum [:insert [0] -18 (vec (repeat 999 6))]]
            :threshold-rate 97.0
            :opts {:max-popped 10000
                   :max-yields 1000}}]]
    (testing name
      (let [task (alice/compression-task [target]
                                         {:name name
                                          :threshold-rate threshold-rate})
            result (run-python-scale-sequence-task task opts)]
        (is (:meets-threshold? result))
        (is (>= (:compression-rate result) threshold-rate))
        (is (= expected
               (get-in result [:selected :target0])))
        (is (= :first-threshold-candidate
               (get-in result [:resource :mode])))))))

(deftest alice-wunderbaum-repeat-with-noise-step-reaches-task-threshold
  (let [opts {:registry alice/basic-operator-registry
              :operator-ids python-alice-operator-ids
              :max-dag-dl 35
              :max-popped 5000
              :max-yields 500}
        task (repeat-with-noise-task)
        step (sut/run-compression-step task opts)
        task-threshold (sut/run-task-to-threshold task opts)]
    (is (>= (:compression-rate step) 1.0))
    (is (:meets-threshold? step))
    (is (:meets-threshold? task-threshold))
    (is (= (get-in step [:resource :candidates-consumed])
           (get-in task-threshold [:resource :candidates-consumed])))
    (is (= (:selected step)
           (:selected task-threshold)))))
