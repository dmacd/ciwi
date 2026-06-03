(ns ciwi.graph-rewrite-test
  (:require [ciwi.graph :as graph]
            [ciwi.graph-rewrite :as graph-rewrite]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.rewrite :as rewrite]
            [ciwi.search :as search]
            [ciwi.value :as value]
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

(deftest graph-rewrite-finds-range-from-literal-operands
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        rewrite-op (graph-rewrite/graph-rewrite-operator
                    {:id :graph-edit
                     :operators [{:op :brange :arity 2}]
                     :literal-values [0 8]
                     :max-depth 1
                     :max-generated 16
                     :beam-width 16})
        result (search/exhaustive-converge g {:parallel? false
                                              :rewrite-operators [rewrite-op]})
        first-candidate (first (:history result))]
    (is (= :fixed-point (:stopped result)))
    (is (= :graph-edit (:rewrite-operator-id first-candidate)))
    (is (= :brange (:reason first-candidate)))
    (is (= [:brange 0 8]
           (mdl/selected-expression (:graph result) :out)))
    (is (pos? (get-in result [:resource :generated-edits])))
    (is (= :max-depth (get-in first-candidate [:resource :termination])))))

(deftest graph-rewrite-depth-bound-controls-nested-edits
  (let [g (one-target-graph [0 1 4 9 16 25])
        shallow (graph-rewrite/graph-rewrite-operator
                 {:id :shallow-graph-edit
                  :operators [{:op :brange :arity 2}
                              {:op :mult :arity 2}]
                  :literal-values square-literals
                  :max-depth 1
                  :max-generated 200
                  :beam-width 64})
        deep (graph-rewrite/graph-rewrite-operator
              {:id :deep-graph-edit
               :operators [{:op :brange :arity 2}
                           {:op :mult :arity 2}]
               :literal-values square-literals
               :max-depth 2
               :max-generated 200
               :beam-width 64})
        shallow-candidates (:candidates (search/rewrite-search
                                         g
                                         [:out]
                                         {:parallel? false
                                          :rewrite-operators [shallow]}))
        deep-result (search/exhaustive-converge g {:parallel? false
                                                   :rewrite-operators [deep]})]
    (is (empty? shallow-candidates))
    (is (= :deep-graph-edit (-> deep-result :history first :rewrite-operator-id)))
    (is (= :mult (-> deep-result :history first :reason)))
    (is (= [:mult [:brange 0 6] [:brange 0 6]]
           (mdl/selected-expression (:graph deep-result) :out)))
    (is (<= 2 (count (:history deep-result))))))

(defn- square-with-range-graph
  []
  (let [g (-> (graph/empty-graph)
              (graph/add-value :square [0 1 4 9 16 25])
              (graph/add-value :range [0 1 2 3 4 5]))]
    (first (graph/add-derived-option g :range op/brange [0 6]))))

(deftest graph-rewrite-reuses-local-dag-nodes
  (let [g (square-with-range-graph)
        rewrite-op (graph-rewrite/graph-rewrite-operator
                    {:id :graph-edit
                     :operators [{:op :mult :arity 2}]
                     :literal-values [0 1]
                     :max-depth 1
                     :max-generated 64
                     :beam-width 64})
        result (search/bounded-converge
                g
                [:square :range]
                {:parallel? false
                 :re-eval-budget 1
                 :rewrite-operators [rewrite-op]})
        first-candidate (first (:history result))
        selected-op-id (first (mdl/selected-operators (:graph result) :square))
        selected-op-node (graph/node (:graph result) selected-op-id)]
    (is (= :graph-edit (:rewrite-operator-id first-candidate)))
    (is (= [(rewrite/node-ref :range) (rewrite/node-ref :range)]
           (:child-refs first-candidate)))
    (is (= [:mult [:node :range] [:node :range]]
           (:edit-form first-candidate)))
    (is (= [:range :range]
           (:children selected-op-node)))
    (is (= [:mult [:brange 0 6] [:brange 0 6]]
           (mdl/selected-expression (:graph result) :square)))
    (is (= 1 (get-in first-candidate [:resource :node-operands])))
    (is (pos? (get-in result [:resource :generated-edits])))))

(defn- ancestor-reuse-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :root [1 2 3])
      (graph/add-value :child [2 3 4])
      (graph/add-value :one 1)
      (graph/add-operator :root-sub op/sub :root [:child :one])))

(deftest graph-rewrite-does-not-reuse-ancestors
  (let [g (ancestor-reuse-graph)
        rewrite-op (graph-rewrite/graph-rewrite-operator
                    {:id :cycle-guard-graph-edit
                     :operators [{:op :add :arity 2}]
                     :literal-values [1]
                     :max-depth 1
                     :max-generated 32
                     :beam-width 32})
        candidates (:candidates
                    (search/rewrite-search
                     g
                     [:child]
                     {:parallel? false
                      :local-node-ids [:child :root]
                      :rewrite-operators [rewrite-op]}))]
    (is (not (rewrite/reusable-child-node? g :child :root)))
    (is (not-any? #(some #{(rewrite/node-ref :root)} (:child-refs %)) candidates))))


(defn- item-edit-graph
  []
  (let [g (-> (graph/empty-graph)
              (graph/add-value :base ["-" "-" "-" "-" "-"])
              (graph/add-value :out ["-" "-" "-" "-" "x"]))]
    (first (graph/add-derived-option g :base op/repeat ["-" 5]))))

(deftest graph-rewrite-enumerates-local-setitem-edits-with-node-reuse
  (let [g (item-edit-graph)
        rewrite-op (graph-rewrite/graph-rewrite-operator
                    {:id :item-edit
                     :operators [{:op :setitem :arity 3}]
                     :literal-values [4 "x"]
                     :max-depth 1
                     :max-generated 200
                     :beam-width 64})
        exhaustive (search/exhaustive-converge g {:parallel? false
                                                  :rewrite-operators [rewrite-op]})
        bounded (search/bounded-converge g [:out :base]
                                         {:parallel? true
                                          :re-eval-budget 1
                                          :rewrite-operators [rewrite-op]})
        first-candidate (first (:history bounded))]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= :fixed-point (:stopped bounded)))
    (is (= [:setitem]
           (mapv :reason (:history bounded))))
    (is (= [(rewrite/node-ref :base)
            (rewrite/value-ref 4)
            (rewrite/value-ref "x")]
           (:child-refs first-candidate)))
    (is (= [:setitem [:repeat "-" 5] 4 "x"]
           (mdl/selected-expression (:graph bounded) :out)))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))


(defn- generated-mask-edit-graph
  []
  (let [g0 (-> (graph/empty-graph)
               (graph/add-value :scores [0 1 2 3])
               (graph/add-value :base ["--------" "--------" "--------" "--------"])
               (graph/add-value :out ["xxxxxxxx" "xxxxxxxx" "--------" "--------"]))
        g1 (first (graph/add-derived-option g0 :scores op/brange [0 4]))]
    (first (graph/add-derived-option g1 :base op/repeat ["--------" 4]))))

(deftest graph-rewrite-compresses-generated-setitem-mask-locally
  (let [g (generated-mask-edit-graph)
        rewrite-op (graph-rewrite/graph-rewrite-operator
                    {:id :generated-mask-edit
                     :operators [{:op :lessthan :arity 2}
                                 {:op :setitem :arity 3}]
                     :literal-values [2 ["xxxxxxxx" "xxxxxxxx"]]
                     :max-depth 2
                     :max-generated 5000
                     :beam-width 128})
        exhaustive (search/exhaustive-converge g {:parallel? false
                                                  :local-node-ids [:out :scores :base]
                                                  :rewrite-operators [rewrite-op]
                                                  :max-steps 5})
        bounded (search/bounded-converge g [:out :scores :base]
                                         {:parallel? true
                                          :re-eval-budget 8
                                          :local-node-ids [:out :scores :base]
                                          :rewrite-operators [rewrite-op]
                                          :max-steps 5})]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= :fixed-point (:stopped bounded)))
    (is (= [:setitem :lessthan]
           (mapv :reason (:history bounded))))
    (is (= [:setitem [:repeat "--------" 4]
            [:lessthan [:brange 0 4] 2]
            ["xxxxxxxx" "xxxxxxxx"]]
           (mdl/selected-expression (:graph bounded) :out)))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))


(defn- length-derived-edit-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :scores (value/value [0 1 2 3] {:dummy? true}))
      (graph/add-value :n 4)
      (graph/add-value :base ["--------" "--------" "--------" "--------"])
      (graph/add-value :mask [true true false false])
      (graph/add-value :out ["xxxxxxxx" "xxxxxxxx" "--------" "--------"])))

(deftest graph-rewrite-compresses-length-derived-base-from-input
  (let [g (length-derived-edit-graph)
        rewrite-op (graph-rewrite/graph-rewrite-operator
                    {:id :length-derived-edit
                     :operators [{:op :len :arity 1}
                                 {:op :repeat :arity 2}
                                 {:op :lessthan :arity 2}
                                 {:op :setitem :arity 3}]
                     :literal-values ["--------" 2 ["xxxxxxxx" "xxxxxxxx"]]
                     :max-depth 1
                     :max-generated 1000
                     :beam-width 128})
        exhaustive (search/exhaustive-converge g {:parallel? false
                                                  :local-node-ids [:out :scores :n :base :mask]
                                                  :rewrite-operators [rewrite-op]
                                                  :max-steps 8})
        bounded (search/bounded-converge g [:out :scores :n :base :mask]
                                         {:parallel? true
                                          :re-eval-budget 8
                                          :local-node-ids [:out :scores :n :base :mask]
                                          :rewrite-operators [rewrite-op]
                                          :max-steps 8})]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= :fixed-point (:stopped bounded)))
    (is (= [:len :lessthan :repeat :setitem]
           (mapv :reason (:history bounded))))
    (is (= [:len [0 1 2 3]]
           (mdl/selected-expression (:graph bounded) :n)))
    (is (= [:setitem [:repeat "--------" [:len [0 1 2 3]]]
            [:lessthan [0 1 2 3] 2]
            ["xxxxxxxx" "xxxxxxxx"]]
           (mdl/selected-expression (:graph bounded) :out)))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))
