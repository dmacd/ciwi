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
