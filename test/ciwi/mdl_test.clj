(ns ciwi.mdl-test
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as sut]
            [ciwi.operator :as op]
            [clojure.test :refer [deftest is]]))

(deftest chooses-shorter-operator-description
  (let [[g _] (-> (graph/empty-graph)
                  (graph/add-value :out [0 1 2 3 4])
                  (graph/add-derived-option :out op/brange [0 5]))
        result (sut/node-dl g :out)]
    (is (< (:dl result)
           (:dl (sut/node-dl (graph/add-value (graph/empty-graph) :out [0 1 2 3 4])
                             :out))))
    (is (= [:out-brange] (sut/selected-operators g :out)))))
