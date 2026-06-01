(ns ciwi.search-test
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.search :as sut]
            [clojure.test :refer [deftest is]]))

(defn one-target-graph
  [x]
  (-> (graph/empty-graph)
      (graph/add-value :out x)))

(deftest exhaustive-rewrite-compresses-arithmetic-range
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        before (mdl/graph-dl g)
        {:keys [graph history stopped]} (sut/exhaustive-converge g {:parallel? true})]
    (is (= :fixed-point stopped))
    (is (= [:brange] (mapv :reason history)))
    (is (< (mdl/graph-dl graph) before))
    (is (= [:out-brange] (mdl/selected-operators graph :out)))))

(deftest bounded-rewrites-converge-to-exhaustive-result
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        exhaustive (sut/exhaustive-converge g {:parallel? false})
        bounded (sut/bounded-converge g [:out] {:parallel? true
                                                :re-eval-budget 4})]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mapv :reason (:history exhaustive))
           (mapv :reason (:history bounded))))
    (is (= (mdl/selected-operators (:graph exhaustive) :out)
           (mdl/selected-operators (:graph bounded) :out)))))

(deftest repeated-bounded-local-rewrites-reach-fixed-point
  (let [g (one-target-graph [0 1 2 3 4 5 6 7 8 9])
        exhaustive (sut/exhaustive-converge g {:parallel? false})
        bounded (sut/bounded-converge g [:out] {:parallel? true
                                                :re-eval-budget 8})]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= :fixed-point (:stopped bounded)))
    (is (seq (:history bounded)))))


(deftest bounded-rewrites-over-target-set-converge-to-exhaustive-result
  (let [g (-> (graph/empty-graph)
              (graph/add-value :range [0 1 2 3 4 5])
              (graph/add-value :flat [9 9 9 9 9 9]))
        exhaustive (sut/exhaustive-converge g {:parallel? false})
        bounded (sut/bounded-converge g [:range :flat] {:parallel? true
                                                        :re-eval-budget 1})]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mdl/selected-operators (:graph exhaustive) :range)
           (mdl/selected-operators (:graph bounded) :range)))
    (is (= (mdl/selected-operators (:graph exhaustive) :flat)
           (mdl/selected-operators (:graph bounded) :flat)))
    (is (= #{:brange :repeat} (set (map :reason (:history bounded)))))))


(deftest opt-in-composite-rewrites-participate-in-search
  (let [g (one-target-graph [2 5 8 11 14 17])
        candidates (sut/candidates g [:out] {:parallel? false
                                             :composite-templates? true})
        best (first candidates)
        result (sut/exhaustive-converge g {:parallel? false
                                           :composite-templates? true})]
    (is (= :linear-sequence (:reason best)))
    (is (= :fixed-point (:stopped result)))
    (is (= [:linear-sequence] (mapv :reason (:history result))))
    (is (= [:out-linear-sequence]
           (mdl/selected-operators (:graph result) :out)))
    (is (= [:linear-sequence 0 6 3 2]
           (mdl/selected-expression (:graph result) :out)))))

(deftest bounded-composite-rewrites-converge-to-exhaustive-result
  (let [g (one-target-graph [2 5 8 11 14 17])
        exhaustive (sut/exhaustive-converge g {:parallel? false
                                               :composite-templates? true})
        bounded (sut/bounded-converge g [:out] {:parallel? true
                                                :re-eval-budget 4
                                                :composite-templates? true})]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mapv :reason (:history exhaustive))
           (mapv :reason (:history bounded))))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))
