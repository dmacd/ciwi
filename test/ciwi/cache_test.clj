(ns ciwi.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [ciwi.cache :as sut]))

(deftest search-context-groups-shared-and-search-scoped-caches
  (let [value-dl-cache (atom {})
        value-content-cache (atom {})
        inverse-cache (atom {})
        context (sut/search-context {:shared {:value-dl-cache value-dl-cache}
                                     :search {:value-content-cache value-content-cache
                                              :inverse-cache inverse-cache}})]
    (is (identical? value-dl-cache (sut/value-dl-cache context)))
    (is (identical? value-content-cache (sut/value-content-cache context)))
    (is (identical? inverse-cache (sut/inverse-cache context)))))

(deftest scoring-context-uses-fresh-graph-cache-by-default
  (let [search-context (sut/search-context)
        left (sut/scoring-context search-context)
        right (sut/scoring-context search-context)]
    (testing "shared and search-scoped caches are preserved"
      (is (identical? (sut/value-dl-cache search-context)
                      (sut/value-dl-cache left)))
      (is (identical? (sut/value-content-cache search-context)
                      (sut/value-content-cache left)))
      (is (identical? (sut/inverse-cache search-context)
                      (sut/inverse-cache left))))
    (testing "node DL cache is graph-local unless supplied"
      (is (not (identical? (sut/node-dl-cache left)
                           (sut/node-dl-cache right)))))))

(deftest scoring-context-preserves-explicit-node-cache
  (let [node-dl-cache (atom {})
        context (sut/scoring-context {:graph {:node-dl-cache node-dl-cache}})]
    (is (identical? node-dl-cache (sut/node-dl-cache context)))))
