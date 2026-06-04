(ns ciwi.alice-wunderbaum-test
  (:require [ciwi.alice :as alice]
            [ciwi.alice-wunderbaum :as sut]
            [ciwi.operator :as op]
            [clojure.test :refer [deftest is]]))

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
