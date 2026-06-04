(ns ciwi.conditions-test
  (:require [ciwi.conditions :as sut]
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
