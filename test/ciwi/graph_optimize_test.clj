(ns ciwi.graph-optimize-test
  (:require [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.graph-optimize :as sut]
            [ciwi.operator :as op]
            [ciwi.optimize :as optimize]
            [ciwi.propagation :as propagation]
            [ciwi.value :as value]
            [clojure.test :refer [deftest is]])
  (:import [java.util Random]))

(defn- round3
  [x]
  (value/round-to-precision x 3))

(defn- gaussian-vector
  [^Random rng n scale]
  (mapv (fn [_]
          (round3 (* scale (.nextGaussian rng))))
        (range n)))

(defn- gaussian-matrix
  [^Random rng rows cols scale]
  (mapv (fn [_]
          (gaussian-vector rng cols scale))
        (range rows)))

(defn- l2-distance
  [left right]
  (Math/sqrt (reduce + 0.0
                     (map (fn [x y]
                            (let [d (- (double x) (double y))]
                              (* d d)))
                          left
                          right))))

(defn- matrix-regression-fixture
  []
  (let [rng (Random. 123)
        x-mat (dense/array (gaussian-matrix rng 1000 10 10.0))
        w-init (gaussian-vector rng 10 1.0)
        w-true (gaussian-vector rng 10 2.0)
        noise (gaussian-vector rng 1000 0.2)
        linear (dense/ravel (dense/dot x-mat (dense/array w-true)))
        y (dense/array
           (mapv (fn [pred eps]
                   (round3 (+ pred 0.85 eps)))
                 linear
                 noise))]
    {:x-mat x-mat
     :w-init w-init
     :w-true w-true
     :y y}))

(defn- rounded-dot
  [x-mat w]
  (let [pred (dense/dot x-mat (dense/array w))]
    (dense/array-like pred (mapv round3 (dense/ravel pred)))))

(defn- residual-init
  [{:keys [x-mat y w-init]}]
  (let [pred (dense/dot x-mat (dense/array w-init))]
    (dense/array-like pred
                      (mapv (fn [y pred]
                              (round3 (- y pred)))
                            (dense/ravel y)
                            (dense/ravel pred)))))

(defn- rounded-residual
  [y pred]
  (dense/array-like pred
                    (mapv (fn [y pred]
                            (round3 (- y pred)))
                          (dense/ravel y)
                          (dense/ravel pred))))

(defn- matrix-regression-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :root nil)
      (graph/add-value :pred nil)
      (graph/add-value :x nil)
      (graph/add-value :w nil)
      (graph/add-value :residual nil)
      (graph/set-roots [:root])
      (graph/add-operator :dot op/dot :pred [:x :w])
      (graph/add-operator :add op/add :root [:pred :residual])))

(defn- mem-entry
  [data opts]
  (propagation/entry false (value/value data opts)))

(defn- matrix-regression-memory
  [{:keys [x-mat y w-init] :as fixture}]
  {:root (mem-entry y {:permeable? false})
   :x (mem-entry x-mat {:permeable? false})
   :w (mem-entry w-init {:permeable? true})
   :residual (mem-entry (residual-init fixture) {:permeable? false})})

(deftest try-to-optimize-improves-matrix-regression-weight-leaf
  (let [fixture (matrix-regression-fixture)
        g (matrix-regression-graph)
        mem (matrix-regression-memory fixture)
        result (sut/try-to-optimize g
                                    mem
                                    {:section-ids [:root :x :w]})
        w-best (dense/ravel (value/datum (propagation/value-at (:memory result) :w)))
        x-after (value/datum (propagation/value-at (:memory result) :x))
        residual-after (value/datum (propagation/value-at (:memory result) :residual))]
    (is (optimize/finite? (:dl result)))
    (is (:improved? result))
    (is (dense/same-content? (:x-mat fixture) x-after))
    (is (dense/same-content?
         (rounded-residual (:y fixture) (rounded-dot (:x-mat fixture) w-best))
         residual-after))
    (is (< (l2-distance w-best (:w-true fixture))
           (l2-distance (:w-init fixture) (:w-true fixture))))))
