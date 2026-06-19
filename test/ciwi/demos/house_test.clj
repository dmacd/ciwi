(ns ciwi.demos.house-test
  (:require [ciwi.demos.house :as sut]
            [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.graph-optimize :as graph-optimize]
            [ciwi.operator :as operator]
            [ciwi.optimize :as optimize]
            [ciwi.propagation :as propagation]
            [ciwi.value :as value]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- close?
  [expected actual]
  (< (Math/abs (- (double expected) (double actual)))
     1.0e-9))

(defn- l2-distance
  [left right]
  (Math/sqrt
   (reduce + 0.0
           (map (fn [x y]
                  (let [d (- (double x) (double y))]
                    (* d d)))
                left
                right))))

(defn- mem-entry
  [data opts]
  (propagation/entry false (value/value data opts)))

(deftest random-state-normals-match-numpy-prefix
  (let [normals (sut/standard-normal-seq 42 3)]
    (is (close? 0.4967141530112327 (nth normals 0)))
    (is (close? -0.13826430117118466 (nth normals 1)))
    (is (close? 0.6476885381006925 (nth normals 2)))))

(deftest geometry-primitives-render-small-fixtures
  (let [border (sut/line [1 1] [1 3])
        filled (sut/fill border)
        colored (sut/dye [10.0 20.0 30.0] filled)
        image (sut/draw colored {:shape [5 5 3]
                                 :background [0.0 0.0 0.0]})
        flat (dense/ravel image)]
    (is (= [[1 1] [1 2] [1 3]] border))
    (is (= [[1 1] [1 2] [1 3]] filled))
    (is (= [3 5] (dense/shape colored)))
    (is (= [5 5 3] (dense/shape image)))
    (is (= [10.0 20.0 30.0]
           (subvec flat (* (+ (* 1 5) 2) 3)
                   (+ (* (+ (* 1 5) 2) 3) 3))))))

(deftest dye-inverse-can-infer-uniform-color-from-known-points
  (let [points [[0 1] [1 0]]
        colored (sut/dye [10.0 20.0 30.0] points)
        inverses (operator/invert-op sut/dye-operator colored [points] [1])
        inferred (value/datum (ffirst inverses))]
    (is (= [10.0 20.0 30.0] (vec (dense/ravel inferred))))))

(deftest dye-inverse-rejects-nonuniform-color-for-known-points
  (let [colored (dense/from-flat [0.0 1.0 10.0 20.0 30.0
                                  1.0 0.0 11.0 20.0 30.0]
                                 [2 5]
                                 {:dtype :float64})]
    (is (empty? (operator/invert-op sut/dye-operator
                                    colored
                                    [[[0 1] [1 0]]]
                                    [1])))))

(deftest draw-inverse-round-trips-non-background-pixels
  (let [opts {:shape [2 2 3]
              :background [0.0 0.0 0.0]}
        colored (sut/dye [10.0 20.0 30.0] [[0 1] [1 0]])
        image (sut/draw colored opts)
        inverses (operator/invert-op (sut/draw-operator opts) image [] [])
        inferred (value/datum (ffirst inverses))]
    (is (= [2 5] (dense/shape inferred)))
    (is (= (dense/ravel image)
           (dense/ravel (sut/draw inferred opts))))))

(deftest house-fixture-has-python-shape-and-seeded-noise
  (let [base (sut/base-house-image)
        image (sut/house-image)]
    (is (= [50 50 3] (dense/shape image)))
    (is (= [255.0 100.0 100.0]
           (subvec (dense/ravel base) 0 3)))
    (is (= [264.93 97.23 112.95]
           (subvec (dense/ravel image) 0 3)))))

(deftest house-image-png-rendering-writes-file
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "ciwi-house-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        path (.getPath (io/file dir "house.png"))
        written (sut/write-image-png! (sut/house-image) path)]
    (is (= path written))
    (is (.exists (io/file path)))))

(deftest guided-prefix-search-follows-canonical-house-steps
  (let [result (sut/run-guided-compression {:max-yields 4
                                            :max-popped 500
                                            :collect-prefixes? true
                                            :prefix-limit 4})]
    (is (nil? (:candidate result)))
    (is (= :exhausted (:stop-reason result)))
    (is (= 4 (:candidates-consumed result)))
    (is (= [:line-top
            :line-roof-right
            :line-roof-left
            :concat-roof12]
           (:prefix-steps result)))
    (is (= 4 (count (:prefixes result))))))

(deftest guided-prefix-image-previews-change-with-discovered-geometry
  (let [result (sut/run-guided-compression {:max-yields 2
                                            :max-popped 250
                                            :collect-prefixes? true
                                            :prefix-limit 2})
        first-image (sut/prefix-preview-image (first (:prefixes result)))
        second-image (sut/prefix-preview-image (second (:prefixes result)))]
    (is (not= (dense/ravel first-image)
              (dense/ravel second-image)))))

(deftest guided-house-compression-reaches-threshold
  (let [result (sut/run-guided-compression {:max-yields 18})]
    (is (= :threshold-reached (:stop-reason result)))
    (is (= 18 (:candidates-consumed result)))
    (is (= [:line-top
            :line-roof-right
            :line-roof-left
            :concat-roof12
            :concat-roof123
            :fill-roof
            :dye-roof
            :line-body-right
            :line-body-bottom
            :line-body-left
            :concat-body12
            :concat-body123
            :concat-body1234
            :fill-body
            :dye-body
            :concat-colored
            :draw-base
            :add-target]
           (:prefix-steps result)))
    (is (< 0.10 (:compression-rate result)))
    (is (:candidate result))
    (is (not (contains? result :selected)))))

(deftest unguided-house-options-remove-solution-guide
  (let [opts (sut/unguided-options)]
    (is (nil? (:candidate-predicate opts)))
    (is (nil? (:frontier-predicate opts)))
    (is (nil? (:preferred-node-fn opts)))
    (is (= 1000 (:max-node-tuples opts)))))

(deftest free-values-can-include-learned-color-without-house-colors
  (let [free (sut/free-values {:include-house-colors? false
                               :learned-colors [[128.0 129.0 130.0]]})
        colors (filter #(= :color (:spec %)) free)
        points (filter #(= :point (:spec %)) free)]
    (is (= 1 (count colors)))
    (is (= [128.0 129.0 130.0]
           (dense/ravel (:data (first colors)))))
    (is (:permeable? (first colors)))
    (is (= 5 (count points)))))

(deftest color-leaf-can-be-optimized-through-dye-and-residual
  (let [draw-opts {:shape [2 2 3]
                   :background [0.0 0.0 0.0]}
        points [[0 0] [0 1]]
        target-color [30.0 60.0 90.0]
        initial-color [200.0 200.0 200.0]
        target-image (sut/draw (sut/dye target-color points) draw-opts)
        initial-image (sut/draw (sut/dye initial-color points) draw-opts)
        residual (dense/subtract target-image initial-image)
        root-value (value/value target-image {:spec :rgb-image
                                              :permeable? false})
        color-value (value/value (dense/array initial-color {:dtype :float64})
                                 {:spec :color
                                  :permeable? true})
        points-value (value/value points {:spec :point-list
                                          :permeable? false})
        residual-value (value/value residual {:spec :rgb-image
                                              :permeable? false})
        g (-> (graph/empty-graph)
              (graph/add-value :root root-value)
              (graph/add-value :drawn nil)
              (graph/add-value :colored nil)
              (graph/add-value :color color-value)
              (graph/add-value :points points-value)
              (graph/add-value :residual residual-value)
              (graph/set-roots [:root])
              (graph/add-operator :dye sut/dye-operator :colored [:color :points])
              (graph/add-operator :draw (sut/draw-operator draw-opts) :drawn [:colored])
              (graph/add-operator :add sut/image-add-operator :root [:drawn :residual]))
        mem {:root (mem-entry target-image {:spec :rgb-image
                                            :permeable? false})
             :color (propagation/entry false color-value)
             :points (propagation/entry false points-value)
             :residual (propagation/entry false residual-value)}
        result (graph-optimize/try-to-optimize
                g
                mem
                {:root-id :root
                 :section-ids [:root :color :points]
                 :optimizer-fn #(optimize/adaptive-grid-search
                                  {:int-mask %
                                   :n-points 5
                                   :jointly-optimize? true
                                   :shrink 1.0
                                   :max-iters 12})})
        optimized-color (dense/ravel
                         (value/datum
                          (propagation/value-at (:memory result) :color)))]
    (is (= [{:node-id :color
             :start 0
             :end 3
             :kind :array
             :int? false}]
           (mapv #(dissoc % :template) (:slots result))))
    (is (:improved? result))
    (is (< (:dl result) (:initial-dl result)))
    (is (< (l2-distance optimized-color target-color)
           (l2-distance initial-color target-color)))))

(deftest bounded-unguided-house-baseline-yields-no-compression
  (let [result (sut/run-unguided-compression {:max-yields 10
                                              :max-popped 20000})]
    (is (= :exhausted (:stop-reason result)))
    (is (= 10 (:candidates-consumed result)))
    (is (nil? (:candidate result)))
    (is (:best result))
    (is (neg? (:compression-rate result)))))

(deftest unguided-house-runner-skips-optimizer-result-when-no-slots-exist
  (let [result (sut/run-unguided-compression {:max-yields 1
                                              :max-popped 200
                                              :optimize-candidates? true})
        best (:best result)]
    (is (= :exhausted (:stop-reason result)))
    (is best)
    (is (not (contains? best :optimizer-result)))))

(deftest unguided-house-can-collect-frontier-stats
  (let [result (sut/run-unguided-compression {:max-yields 3
                                              :max-popped 200
                                              :collect-wunderbaum-stats? true})
        stats (:wunderbaum-stats result)]
    (is (map? stats))
    (is (pos? (:frontier-considered stats)))
    (is (pos? (:frontier-popped stats)))
    (is (contains? (:frontier-considered-by-op stats) :line))))

(deftest unguided-house-runner-can-use-global-best-first
  (let [result (sut/run-unguided-compression {:max-yields 1
                                              :max-popped 8
                                              :parallelism 2
                                              :parallel-strategy :global-best-first
                                              :collect-wunderbaum-stats? true})
        stats (:wunderbaum-stats result)]
    (is (= :global-best-first (:strategy stats)))
    (is (= 2 (:worker-count stats)))))

(deftest guided-prefix-artifacts-write-stats-and-frames
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "ciwi-house-guided-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        result (sut/run-guided-compression {:max-yields 2
                                            :max-popped 250
                                            :collect-prefixes? true
                                            :prefix-limit 2})
        artifacts (sut/write-guided-artifacts! result (.getPath dir)
                                               {:movies? false})]
    (is (.exists (io/file (:stats-path artifacts))))
    (is (.exists (io/file (:readme-path artifacts))))
    (is (.exists (io/file (:graph-frame-dir artifacts) "frame-000000.png")))
    (is (.exists (io/file (:image-frame-dir artifacts) "frame-000000.png")))))
