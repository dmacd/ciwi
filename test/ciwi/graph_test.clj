(ns ciwi.graph-test
  (:require [ciwi.graph :as sut]
            [ciwi.operator :as op]
            [clojure.test :refer [deftest is]]))

(defn add-graph
  []
  (-> (sut/empty-graph)
      (sut/add-value :out nil)
      (sut/add-value :x nil)
      (sut/add-value :y nil)
      (sut/add-operator :add-out op/add :out [:x :y])
      (sut/set-roots [:out])))

(deftest graph-keeps-william-bipartite-shape
  (let [g (add-graph)]
    (is (= [:out :x :y] (sut/value-ids g)))
    (is (= [:add-out] (sut/operator-ids g)))
    (is (= [:out] (sut/roots g)))
    (is (= [:x :y] (sut/leaves g :out)))
    (is (= [:add-out] (:options (sut/node g :out))))
    (is (= [:add-out] (:parents (sut/node g :x))))))
