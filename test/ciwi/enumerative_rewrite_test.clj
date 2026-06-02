(ns ciwi.enumerative-rewrite-test
  (:require [ciwi.enumerative-rewrite :as enum]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.rewrite :as rewrite]
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
    :beam-width 64}))

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


(defn- square-with-range-graph
  []
  (let [g (-> (graph/empty-graph)
              (graph/add-value :square [0 1 4 9 16 25])
              (graph/add-value :range [0 1 2 3 4 5]))]
    (first (graph/add-derived-option g :range op/brange [0 6]))))

(deftest bounded-enumerator-reuses-local-dag-nodes
  (let [g (square-with-range-graph)
        result (search/bounded-converge
                g
                [:square :range]
                {:parallel? false
                 :re-eval-budget 1
                 :extra-templates [square-range-enumerator]})
        first-candidate (first (:history result))
        selected-op-id (first (mdl/selected-operators (:graph result) :square))
        selected-op-node (graph/node (:graph result) selected-op-id)]
    (is (= :bounded-enum (:reason first-candidate)))
    (is (= [(rewrite/node-ref :range) (rewrite/node-ref :range)]
           (:child-refs first-candidate)))
    (is (= [:range :range]
           (:children selected-op-node)))
    (is (= [:mult [:brange 0 6] [:brange 0 6]]
           (mdl/selected-expression (:graph result) :square)))
    (is (= 2 (get-in first-candidate [:resource :max-depth])))
    (is (= 64 (get-in first-candidate [:resource :beam-width])))
    (is (= 1 (get-in first-candidate [:resource :seed-expressions])))
    (is (pos? (get-in first-candidate [:resource :generated-expressions])))))

(def cycle-guard-enumerator
  (enum/enumerative-template
   {:id :cycle-guard-enum
    :reason :cycle-guard-enum
    :operators [{:op :add :arity 2}]
    :literal-values (constantly [1])
    :max-depth 1
    :max-generated 16
    :beam-width 16}))

(defn- ancestor-reuse-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :root [1 2 3])
      (graph/add-value :child [2 3 4])
      (graph/add-value :one 1)
      (graph/add-operator :root-sub op/sub :root [:child :one])))

(deftest bounded-enumerator-does-not-reuse-ancestors
  (let [g (ancestor-reuse-graph)
        candidates (search/candidates
                    g
                    [:child]
                    {:parallel? false
                     :local-node-ids [:child :root]
                     :extra-templates [cycle-guard-enumerator]})]
    (is (not (rewrite/reusable-child-node? g :child :root)))
    (is (rewrite/reusable-child-node? g :root :child))
    (is (nil? (rewrite/candidate-from-refs
               g
               :child
               op/add
               [(rewrite/node-ref :root) (rewrite/value-ref 1)]
               :cycle)))
    (is (not-any? #(some #{(rewrite/node-ref :root)} (:child-refs %)) candidates))))
