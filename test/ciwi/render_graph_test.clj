(ns ciwi.render-graph-test
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.render.graph :as sut]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- add-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out 3)
      (graph/add-value :x 1)
      (graph/add-value :y 2)
      (graph/add-operator :add-out op/add :out [:x :y])
      (graph/set-roots [:out])))

(deftest graph-dot-rendering-is-deterministic
  (let [g (add-graph)
        left (sut/graph->dot g {:label "tiny"})
        right (sut/graph->dot g {:label "tiny"})]
    (is (= left right))
    (is (re-find #"digraph tree" left))
    (is (re-find #":out" left))
    (is (re-find #":add" left))
    (is (re-find #"section DL:" left))
    (is (re-find #"cluster_frontier_" left))
    (is (re-find #"arg0" left))))

(deftest graph-rendering-does-not-change-graph-scoring
  (let [g (add-graph)
        before (mdl/selected-expression g :out)]
    (sut/graph->dot g)
    (is (= before (mdl/selected-expression g :out)))))

(deftest graph-png-rendering-is-optional
  (let [g (add-graph)
        dir (.toFile (java.nio.file.Files/createTempDirectory
                      "ciwi-render-graph-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        png (.getPath (io/file dir "graph.png"))
        result (sut/render-png! g png {:label "tiny"})]
    (is (contains? #{:ok :unavailable} (:status result)))
    (is (.exists (io/file (:dot-path result))))
    (when (= :ok (:status result))
      (is (.exists (io/file png))))))
