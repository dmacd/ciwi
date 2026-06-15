(ns ciwi.wunderbaum-observer-test
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum :as alice-wb]
            [ciwi.operator :as op]
            [clojure.test :refer [deftest is]]))

(deftest observer-receives-search-and-greedy-events
  (let [task (alice/compression-task [[0 1 2 3 4 5 6 7]]
                                     {:name "range"
                                      :threshold-rate 0.01})
        base-opts {:registry {:brange op/brange}
                   :max-popped 16
                   :max-yields 4
                   :worthy-dl 0}
        expected (alice-wb/run-greedy-task task base-opts)
        events (atom [])
        observed (alice-wb/run-greedy-task
                  task
                  (assoc base-opts
                         :observer #(swap! events conj %)
                         :observer-sample-rate 1))
        event-types (set (map :event @events))]
    (is (= (:selected expected) (:selected observed)))
    (is (contains? event-types :frontier-materialized))
    (is (contains? event-types :accepted-candidate))
    (is (contains? event-types :greedy-step-completed))))
