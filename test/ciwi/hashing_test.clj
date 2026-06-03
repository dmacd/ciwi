(ns ciwi.hashing-test
  (:require [ciwi.hashing :as sut]
            [clojure.test :refer [deftest is]]))

(defrecord SampleRecord [name value])

(deftest sort-anything-orders-native-values-stably
  (is (= [nil 10 "z" '() [] {}]
         (sut/sort-anything [{} 10 [] "z" nil '()])))
  (is (= [false true 0 1]
         (sut/sort-anything [1 true 0 false])))
  (is (= [[1 2] [1 2 3] [1 [2 3]]]
         (sut/sort-anything [[1 [2 3]] [1 2 3] [1 2]])))
  (is (= [{:ids [1 2]}
          {:ids #{1 2}}
          {:ids #{1 2 3}}]
         (sut/sort-anything [{:ids #{1 2 3}}
                             {:ids #{1 2}}
                             {:ids [1 2]}]))))


(deftest stable-key-distinguishes-records-from-plain-maps
  (let [record (->SampleRecord "test" 1)
        plain {:name "test" :value 1}]
    (is (not= (sut/stable-key record)
              (sut/stable-key plain)))
    (is (= 98
           (nth (sut/stable-key record) 2)))
    (is (= 10
           (nth (sut/stable-key plain) 2)))))


(deftest unique-hash-is-deterministic-and-order-invariant
  (let [value {:outer [{:inner #{1 2}}]
               :status true}
        reordered {:status true
                   :outer [{:inner #{2 1}}]}
        hash (sut/unique-hash value)]
    (is (pos? hash))
    (is (= hash (sut/unique-hash value)))
    (is (= hash (sut/unique-hash reordered)))
    (is (= (sut/unique-hash #{1 2 3})
           (sut/unique-hash #{3 2 1})))
    (is (= (sut/unique-hash {:a 1 :b 2})
           (sut/unique-hash {:b 2 :a 1})))))


(deftest unique-hash-preserves-type-and-depth-distinctions
  (is (not= (sut/unique-hash "1")
            (sut/unique-hash 1)))
  (is (not= (sut/unique-hash [])
            (sut/unique-hash {})))
  (is (not= (sut/unique-hash [])
            (sut/unique-hash '())))
  (is (not= (sut/unique-hash [[1]])
            (sut/unique-hash [1])))
  (is (not= (sut/unique-hash {:level1 {:level2 {:level3 100}}})
            (sut/unique-hash {:level1 {:level2 [100]}}))))
