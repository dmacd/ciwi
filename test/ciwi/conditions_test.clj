(ns ciwi.conditions-test
  (:require [ciwi.composite :as composite]
            [ciwi.conditions :as sut]
            [ciwi.dsl :as dsl]
            [clojure.test :refer [deftest is testing]]))

(deftest combines-child-conditions-with-offsets
  (is (= [[0 1 4] [0 1 5] [2 4] [2 5]]
         (sut/condition-combinations [[[0 1] [2]]
                                      [[0] [1]]]
                                     [0 4]))))

(deftest filters-redundant-conditions-like-python-william
  (doseq [[input expected]
          '(([()] [()])
            ([() (1)] [() (1)])
            ([(1) ()] [() (1)])
            ([(0 2) (1) ()] [() (1) (0 2)])
            ([(0 2) () (3)] [() (3) (0 2)])
            ([(0) (0 2)] [(0)])
            ([(0 1) (0)] [(0)])
            ([(3) (0 2 3) (1 3)] [(3)])
            ([(0 2) (2) (0 1 2)] [(2)])
            ([(2) (0 2) (2)] [(2)])
            ([(2) (1 2) (1)] [(1) (2)])
            ([(3) (0 1)] [(3) (0 1)])
            ([(1 2) (2 3)] [(1 2) (2 3)])
            ([(0) (3) (4)] [(0) (3) (4)])
            ([(2) (1 2 4 5) (5)] [(2) (5)]))]
    (testing (str input)
      (is (= (mapv vec expected)
             (sut/filter-redundant (mapv vec input) 4))))))

(deftest removes-redundant-conditions-like-python-william
  (is (= [[0] [1]]
         (sut/remove-redundant-conditions [[] [0] [1]])))
  (is (= [[0] [1 3 2]]
         (sut/remove-redundant-conditions [[1 2] [0] [1 3 2] [] [1]]))))

(deftest extracts-tree-conditions-from-clojure-graphs
  (let [{add-g :graph add-root :root} (dsl/from-expr [:add 1 2])
        {neg-g :graph neg-root :root} (dsl/from-expr [:negate 1])
        {cat-g :graph cat-root :root} (dsl/from-expr [:concat [:brange 0 3]
                                                      [:repeat 2 [:z]]])]
    (is (= [[0] [1]]
           (sut/get-conditions add-g add-root)))
    (is (= [[]]
           (sut/get-conditions neg-g neg-root)))
    (is (= [[0 1] [2 3]]
           (sut/get-conditions cat-g cat-root)))))

(def ^:private python-condition-fixtures
  [{:name "co0"
    :expr [:insert [:input :indices [1]]
           [:input :content 2]
           [:input :rest [3 4]]]
    :expected [[] [0] [1]]}
   {:name "co1"
    :expr [:add [:mult [:input :a 2]
                 [:input :b 3]]
           [:input :c 5]]
    :expected [[0 1] [0 2] [1 2]]}
   {:name "co2"
    :expr [:insert [:trange [:input :idx-start 1]
                    [:input :idx-stop 7]
                    [:input :idx-step 2]]
           [:trange [:input :content-start 3]
            [:input :content-stop 12]
            [:input :content-step 3]]
           [:trange [:input :rest-start 15]
            [:input :rest-stop 23]
            [:input :rest-step 2]]]
    :expected [[] [0 1 2] [3 4 5]]}
   {:name "co3"
    :expr [:sub [:mult [:input :a 3]
                 [:input :b 4]]
           [:add [:input :c 5]
            [:negate [:input :d 2]]]]
    :expected [[0 1 2] [0 1 3] [0 2 3] [1 2 3]]}
   {:name "co4"
    :expr [:add [:negate [:input :x 3]]
           [:sub [:input :y 12]
            [:input :z 5]]]
    :expected [[0 1] [0 2] [1 2]]}
   {:name "dag0"
    :expr [:mult [:input :x 15]
           [:input :x 15]]
    :expected [[0]]}
   {:name "dag1"
    :expr [:add [:mult [:input :x 5]
                 [:input :x 5]]
           [:input :x 5]]
    :expected [[0]]}
   {:name "dag2"
    :expr [:add [:mult [:input :x 5]
                 [:input :x 5]]
           [:input :y 7]]
    :expected [[0]]}
   {:name "dag3"
    :expr [:add [:mult [:input :x 5]
                 [:input :y 7]]
           [:mult [:input :x 5]
            [:input :y 7]]]
    :expected [[0 1]]}])

(deftest python-composite-condition-golden-cases
  (doseq [{:keys [name expr expected]} python-condition-fixtures]
    (testing name
      (let [cop (composite/operator (keyword name) expr)]
        (is (= expected (:conditions cop)))))))
