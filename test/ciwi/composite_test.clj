(ns ciwi.composite-test
  (:require [ciwi.composite :as sut]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]]))

(defn- data-results
  [results]
  (mapv #(mapv value/datum %) results))

(deftest composite-operator-calls-through-graph-propagation
  (let [cop (sut/operator :mul-plus [:add [:mult 0 1] 2])]
    (is (= [[0 1] [0 2] [1 2]]
           (:conditions cop)))
    (is (= 37
           (value/datum (op/apply-op cop [(value/value 5)
                                          (value/value 7)
                                          (value/value 2)]))))
    (is (= [[2]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 5) (value/value 7)]
                                       [0 1]))))))

(deftest composite-operator-captures-constant-leaves
  (let [cop (sut/operator :mul-plus-two
                          [:add [:mult 0 1] 2]
                          {:constant-indices #{2}})]
    (is (= [[0] [1]]
           (:conditions cop)))
    (is (= 37
           (value/datum (op/apply-op cop [(value/value 5)
                                          (value/value 7)]))))
    (is (= [[7]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 5)]
                                       [0]))))
    (is (= [[5]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 7)]
                                       [1]))))))


(deftest composite-template-inputs-can-share-graph-leaves
  (let [square (sut/operator :square
                             [:mult [:input :x 2]
                              [:input :x 2]])
        square-plus-y (sut/operator :square-plus-y
                                    [:add [:mult [:input :x 2]
                                           [:input :x 2]]
                                     [:input :y 3]])]
    (is (= 25
           (value/datum (op/apply-op square [(value/value 5)]))))
    (is (= 32
           (value/datum (op/apply-op square-plus-y [(value/value 5)
                                                   (value/value 7)]))))
    (is (= [[0]]
           (:conditions square-plus-y)))
    (is (= [[7]]
           (data-results (op/invert-op square-plus-y
                                       (value/value 32)
                                       [(value/value 5)]
                                       [0]))))))

(deftest composite-template-literals-are-captured-as-constants
  (let [inc-op (sut/operator :increment [:add [:input :x 0] 1])]
    (is (= [[]]
           (:conditions inc-op)))
    (is (= 42
           (value/datum (op/apply-op inc-op [(value/value 41)]))))
    (is (= [[41]]
           (data-results (op/invert-op inc-op
                                       (value/value 42)
                                       []
                                       []))))))


(deftest dag-shaped-composites-match-python-execution-cases
  (let [dag0 (sut/operator :dag0
                           [:mult [:input :x 2]
                            [:input :x 2]])
        dag1 (sut/operator :dag1
                           [:add [:mult [:input :x 2]
                                  [:input :x 2]]
                            [:input :x 2]])
        dag2 (sut/operator :dag2
                           [:add [:mult [:input :x 2]
                                  [:input :x 2]]
                            [:input :y 3]])
        dag3 (sut/operator :dag3
                           [:add [:mult [:input :x 2]
                                  [:input :y 3]]
                            [:mult [:input :x 2]
                             [:input :y 3]]])]
    (is (= 225
           (value/datum (op/apply-op dag0 [(value/value 15)]))))
    (is (= 30
           (value/datum (op/apply-op dag1 [(value/value 5)]))))
    (is (= 32
           (value/datum (op/apply-op dag2 [(value/value 5)
                                           (value/value 7)]))))
    (is (= 70
           (value/datum (op/apply-op dag3 [(value/value 5)
                                           (value/value 7)]))))
    (is (= [[7]]
           (data-results (op/invert-op dag2
                                       (value/value 32)
                                       [(value/value 5)]
                                       [0]))))))

(deftest composite-commutativity-is-inferred-symbolically
  (let [plus (sut/operator :plus [:add [:input :x 0] [:input :y 0]])
        times (sut/operator :times [:mult [:input :x 0] [:input :y 0]])
        minus (sut/operator :minus [:sub [:input :x 0] [:input :y 0]])
        square-plus-y (sut/operator :square-plus-y
                                    [:add [:mult [:input :x 0]
                                           [:input :x 0]]
                                     [:input :y 0]])]
    (is (:commutative? plus))
    (is (:commutative? times))
    (is (not (:commutative? minus)))
    (is (not (:commutative? square-plus-y)))))


(deftest composite-inverts-nested-arithmetic-with-captured-constants
  (let [offset-product (sut/operator :offset-product
                                     [:add [:mult [:input :x 2]
                                            [:input :y 3]]
                                      5])
        sub-chain (sut/operator :sub-chain
                                [:sub [:add [:input :x 0]
                                       [:input :y 0]]
                                 [:input :z 0]])]
    (is (= [[6]]
           (data-results (op/invert-op offset-product
                                       (value/value 35)
                                       [(value/value 5)]
                                       [0]))))
    (is (= [[7]]
           (data-results (op/invert-op sub-chain
                                       (value/value 10)
                                       [(value/value 4) (value/value 1)]
                                       [0 2]))))))

(deftest composite-inverts-through-negated-intermediate-values
  (let [neg-shift (sut/operator :neg-shift
                                [:add [:negate [:input :x 0]]
                                 [:input :y 0]])]
    (is (= [[-10]]
           (data-results (op/invert-op neg-shift
                                       (value/value 13)
                                       [(value/value 3)]
                                       [1]))))
    (is (= [[10]]
           (data-results (op/invert-op neg-shift
                                       (value/value 13)
                                       [(value/value -3)]
                                       [0]))))))

(deftest composite-inversion-returns-no-results-for-unsatisfied-or-invalid-local-equations
  (let [product (sut/operator :product
                              [:mult [:input :x 0]
                               [:input :y 0]])
        square-plus-y (sut/operator :square-plus-y
                                    [:add [:mult [:input :x 2]
                                           [:input :x 2]]
                                     [:input :y 3]])]
    (is (empty? (data-results (op/invert-op product
                                            (value/value 10)
                                            [(value/value 0)]
                                            [1]))))
    (is (empty? (data-results (op/invert-op square-plus-y
                                            (value/value 32)
                                            [(value/value 7)]
                                            [1]))))))


(deftest composite-item-operators-call-and-invert-through-propagation
  (let [pick (sut/operator :pick-selected
                           [:getitem [:input :xs [3 5 2]]
                            [:input :mask [true false true]]])
        patch (sut/operator :patch-selected
                            [:setitem [:input :xs [342 6 8 252]]
                             [:input :mask [false true true false]]
                             [:input :items [78 34]]])]
    (is (= [3 2]
           (value/datum (op/apply-op pick [(value/value [3 5 2])
                                           (value/value [true false true])]))))
    (is (= [[] [1]]
           (:conditions pick)))
    (is (= [[[2.0 nil nil 3.0]]]
           (data-results (op/invert-op pick
                                       (value/value [2.0 3.0])
                                       [(value/value [true false false true])]
                                       [1]))))
    (is (= [[[2.0 3.0] [0 1]]]
           (data-results (op/invert-op pick
                                       (value/value [2.0 3.0])
                                       []
                                       []))))
    (is (= [342 78 34 252]
           (value/datum (op/apply-op patch [(value/value [342 6 8 252])
                                            (value/value [false true true false])
                                            (value/value [78 34])]))))
    (is (= [[0] [1]]
           (:conditions patch)))
    (is (= [[[342 nil nil 252] [78 34]]]
           (data-results (op/invert-op patch
                                       (value/value [342 78 34 252])
                                       [(value/value [false true true false])]
                                       [1]))))))


(deftest composite-generated-mask-feeds-setitem
  (let [patch (sut/operator :threshold-patch
                            [:setitem [:input :base ["-" "-" "-" "-"]]
                             [:lessthan [:input :scores [0 1 2 3]]
                              [:input :threshold 2]]
                             [:input :items ["x" "x"]]])]
    (is (= [[1 2]]
           (:conditions patch)))
    (is (= ["x" "x" "-" "-"]
           (value/datum (op/apply-op patch [(value/value ["-" "-" "-" "-"])
                                            (value/value [0 1 2 3])
                                            (value/value 2)
                                            (value/value ["x" "x"])]))))
    (is (= [[["" "" "-" "-"] ["x" "x"]]]
           (data-results (op/invert-op patch
                                       (value/value ["x" "x" "-" "-"])
                                       [(value/value [0 1 2 3])
                                        (value/value 2)]
                                       [1 2]))))))
