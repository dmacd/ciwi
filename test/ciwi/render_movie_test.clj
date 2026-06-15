(ns ciwi.render-movie-test
  (:require [ciwi.render.movie :as sut]
            [clojure.test :refer [deftest is]]))

(deftest frame-paths-are-stable
  (is (= "frame-000012.png"
         (sut/frame-name 12)))
  (is (.endsWith (sut/frame-path "/tmp/ciwi-frames" 7)
                 "/tmp/ciwi-frames/frame-000007.png")))
