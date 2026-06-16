(ns ciwi.demos.house-test
  (:require [ciwi.demos.house :as sut]
            [ciwi.dense.core :as dense]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- close?
  [expected actual]
  (< (Math/abs (- (double expected) (double actual)))
     1.0e-9))

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
