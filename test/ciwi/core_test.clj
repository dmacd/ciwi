(ns ciwi.core-test
  (:require [ciwi.core :as sut]
            [clojure.test :refer [deftest is]]))

(deftest ready-test
  (is (true? (sut/ready?))))

