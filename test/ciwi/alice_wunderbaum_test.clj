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
                           :threshold-rate 90.0}))

(defn- insert-repeat3-task
  []
  (alice/compression-task [(vec (concat (repeat 100 45)
                                        (take 500 (cycle [87 62]))
                                        (repeat 610 164)))]
                          {:name "insert_repeat3"
                           :threshold-rate 93.0}))

(defn- close-to?
  [expected actual]
  (< (Math/abs (- (double expected) (double actual)))
     1.0e-6))

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

(deftest alice-wunderbaum-compresses-arithmetic-range
  (let [task (alice/compression-task [[0 1 2 3 4 5 6 7]]
                                     {:name "range"
                                      :threshold-rate 1.0})
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
                                      :threshold-rate 1.0})
        result (sut/run-greedy-task task {:registry {:repeat op/repeat}
                                          :max-popped 32
                                          :max-yields 8
                                          :worthy-dl 0})]
    (is (:meets-threshold? result))
    (is (= [:repeat 10 [140 -50]]
           (get-in result [:selected :target0])))))

(deftest alice-wunderbaum-compresses-python-scale-sequence-rows
  (doseq [{:keys [name target expected threshold-rate opts]}
          [{:name "simple_repeat"
            :target (vec (take 1000 (cycle [140 -50])))
            :expected [:insert
                       [:cumsum [:insert [0] 0 (vec (repeat 499 2))]]
                       140
                       (vec (repeat 500 -50))]
            :threshold-rate 94.0
            :opts {:max-popped 200
                   :max-yields 20}}
           {:name "insert_repeat"
            :target (vec (concat (repeat 100 45)
                                  (repeat 250 87)))
            :expected [:insert
                       [:cumsum (vec (concat [0] (repeat 99 1)))]
                       45
                       (vec (repeat 250 87))]
            :threshold-rate 92.0
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
            :threshold-rate 92.0
            :opts {:max-popped 10000
                   :max-yields 1000}}
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
        (is (= :greedy-task
               (get-in result [:resource :mode])))
        (is (= (case name
                 "simple_repeat" 3
                 "insert_repeat" 2
                 "insert_repeat2" 3
                 "simply_linear" 2
                 1)
               (count (:steps result))))
        (is (= (count (:steps result))
               (get-in result [:resource :candidates-consumed])))))))

(deftest alice-wunderbaum-repeat-with-noise-step-reaches-task-threshold
  (let [opts {:registry alice/basic-operator-registry
              :operator-ids python-alice-operator-ids
              :max-dag-dl 35
              :max-popped 5000
              :max-yields 500}
        task (repeat-with-noise-task)
        step (sut/run-compression-step task opts)
        task-result (sut/run-greedy-task task opts)]
    (is (>= (:compression-rate step) 1.0))
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
    (is (close-to? 86.38075946321129
                   (:compression-rate result)))
    (is (= [0 1 0] (:path fourth-step)))
    (is (close-to? 1161.7371134088858 (:dl fourth-step)))
    (is (= :insert (first selected)))
    (is (= :cumsum (get-in selected [1 0])))
    (is (= :insert (get-in selected [1 1 0])))))
