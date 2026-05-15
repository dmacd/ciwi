(ns ciwi.core
  (:require [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as propagation]))

(defn ready?
  "Returns true when the Clojure prototype environment is runnable."
  []
  true)

(defn add-example
  "Build and run a tiny WILLIAM-style graph: out = x + y."
  [x y]
  (let [g (-> (graph/empty-graph)
              (graph/add-value :out nil)
              (graph/add-value :x nil)
              (graph/add-value :y nil)
              (graph/add-operator :add-out op/add :out [:x :y]))
        mem (propagation/memory {:x x :y y})]
    (-> (propagation/propagate g mem)
        first
        (propagation/value-at :out)
        :data)))

(defn -main
  [& _args]
  (println "ciwi Clojure prototype environment is ready"))
