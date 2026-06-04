(ns ciwi.dsl-test
  (:require [ciwi.dsl :as sut]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [clojure.test :refer [deftest is]]))

(deftest clojure-data-builds-executable-graph
  (let [{:keys [graph root]} (sut/from-expr [:add 3 4])]
    (is (= 7 (get-in graph [:nodes root :value :data])))
    (is (= 1 (count (graph/operator-ids graph))))
    (is (= 2 (count (graph/leaves graph root))))))

(deftest nested-data-builds-compressible-graph
  (let [{:keys [graph root]} (sut/from-expr [:concat [:brange 0 3]
                                             [:repeat 2 [:x]]])]
    (is (= [0 1 2 :x :x] (get-in graph [:nodes root :value :data])))
    (is (= 3 (count (graph/operator-ids graph))))
    (is (= [:concat :brange :repeat]
           (mapv #(get-in graph [:nodes % :operator :id])
                 (mdl/selected-operators graph root))))))

(deftest literal-wrapper-disambiguates-operator-looking-data
  (let [{:keys [graph root]} (sut/from-expr (sut/literal [:add 1 2]))]
    (is (= [:add 1 2] (get-in graph [:nodes root :value :data])))
    (is (empty? (graph/operator-ids graph)))))
