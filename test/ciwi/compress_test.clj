(ns ciwi.compress-test
  (:require [ciwi.compress :as sut]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [clojure.test :refer [deftest is testing]]))

(defn one-target
  [x]
  (-> (graph/empty-graph)
      (graph/add-value :out x)))

(deftest exhaustive-compression-selects-minimal-range-bottleneck
  (let [g (one-target [0 1 2 3 4 5 6 7])
        before (mdl/graph-dl g)
        result (sut/compress-exhaustive g {:parallel? true})]
    (is (= :fixed-point (:stopped result)))
    (is (< (:dl result) before))
    (is (= [:brange 0 8] (get-in result [:selected :out])))
    (is (= [:brange] (mapv :reason (:history result))))))

(deftest affine-sequence-compresses-through-recursive-selected-children
  (let [g (one-target [2 5 8 11 14 17])
        exhaustive (sut/compress-exhaustive g {:parallel? false})
        bounded (sut/compress-bounded g [:out] {:parallel? true
                                                :re-eval-budget 64})]
    (is (= :fixed-point (:stopped exhaustive)))
    (is (= :fixed-point (:stopped bounded)))
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= [:add [:mult [:brange 0 6] 3] 2]
           (get-in exhaustive [:selected :out])))
    (is (= (get-in exhaustive [:selected :out])
           (get-in bounded [:selected :out])))
    (is (= [:affine-add :scale-mult :brange]
           (mapv :reason (:history exhaustive))))))

(deftest bounded-compression-over-target-set-matches-exhaustive-selected-solutions
  (let [g (-> (graph/empty-graph)
              (graph/add-value :range [0 1 2 3 4])
              (graph/add-value :repeat [:z :z :z :z])
              (graph/add-value :affine [10 12 14 16 18]))
        exhaustive (sut/compress-exhaustive g {:parallel? false})
        bounded (sut/compress-bounded g [:range :repeat :affine]
                                      {:parallel? true
                                       :re-eval-budget 64})]
    (testing "bounded local rewrites converge to exhaustive selections"
      (is (= (select-keys (:selected exhaustive) [:range :repeat :affine])
             (:selected bounded)))
      (is (= [:brange 0 5] (get-in bounded [:selected :range])))
      (is (= [:repeat :z 4] (get-in bounded [:selected :repeat])))
      (is (= [:add [:mult [:brange 0 5] 2] 10]
             (get-in bounded [:selected :affine]))))))
