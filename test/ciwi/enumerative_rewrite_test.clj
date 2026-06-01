(ns ciwi.enumerative-rewrite-test
  (:require [ciwi.enumerative-rewrite :as enum]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.search :as search]
            [clojure.test :refer [deftest is]]))

(defn- one-target-graph
  [x]
  (-> (graph/empty-graph)
      (graph/add-value :out x)))

(defn- square-literals
  [data]
  (if (vector? data)
    [0 (count data)]
    [0 1 data]))

(def square-range-enumerator
  (enum/enumerative-template
   {:id :bounded-enum
    :reason :bounded-enum
    :operators [{:op :brange :arity 2}
                {:op :mult :arity 2}]
    :literal-values square-literals
    :max-depth 2
    :max-generated 200
    :max-pool-size 64}))

(deftest bounded-enumerator-finds-nested-local-rewrite
  (let [g (one-target-graph [0 1 4 9 16 25])
        exhaustive (search/exhaustive-converge
                    g
                    {:parallel? false
                     :extra-templates [square-range-enumerator]})
        bounded (search/bounded-converge
                 g
                 [:out]
                 {:parallel? true
                  :re-eval-budget 8
                  :extra-templates [square-range-enumerator]})]
    (is (= :fixed-point (:stopped exhaustive)))
    (is (= :bounded-enum (-> exhaustive :history first :reason)))
    (is (= [:mult [:brange 0 6] [:brange 0 6]]
           (mdl/selected-expression (:graph exhaustive) :out)))
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))

(deftest enumeration-depth-bound-limits-candidates
  (let [g (one-target-graph [0 1 4 9 16 25])
        shallow (enum/enumerative-template
                 {:id :shallow-enum
                  :reason :shallow-enum
                  :operators [{:op :brange :arity 2}
                              {:op :mult :arity 2}]
                  :literal-values square-literals
                  :max-depth 1
                  :max-generated 200})
        candidates (search/candidates g [:out] {:parallel? false
                                                :extra-templates [shallow]})]
    (is (not-any? #(= :shallow-enum (:reason %)) candidates))))
