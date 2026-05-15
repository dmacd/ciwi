(ns ciwi.value-test
  (:require [ciwi.value :as sut]
            [clojure.test :refer [deftest is]]))

(deftest value-wraps-plain-data
  (let [v (sut/value 42)]
    (is (= 42 (:data v)))
    (is (:permeable? v))
    (is (false? (:dummy? v)))
    (is (= 2.0 (sut/desc-len v)))))
