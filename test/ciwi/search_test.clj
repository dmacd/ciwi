(ns ciwi.search-test
  (:require [ciwi.composite :as composite]
            [ciwi.dense :as dense]
            [ciwi.graph :as graph]
            [ciwi.library :as library]
            [ciwi.mdl :as mdl]
            [ciwi.rewrite :as rewrite]
            [ciwi.search :as sut]
            [clojure.test :refer [deftest is]]))

(defn one-target-graph
  [x]
  (-> (graph/empty-graph)
      (graph/add-value :out x)
      (graph/set-roots [:out])))

(defn recognizer-opts
  ([] (recognizer-opts {}))
  ([opts]
   (assoc opts :rewrite-operators [(rewrite/primitive-template-operator)])))

(deftest rewrite-search-has-no-default-recognizer-templates
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        result (sut/rewrite-search g [:out] {:parallel? false})]
    (is (empty? (:rewrite-operator-ids result)))
    (is (empty? (:candidates result)))
    (is (empty? (:trace result)))
    (is (zero? (get-in result [:resource :rewrite-operators-considered])))
    (is (zero? (get-in result [:resource :nodes-considered])))
    (is (zero? (get-in result [:resource :templates-considered])))))

(deftest exhaustive-rewrite-compresses-arithmetic-range
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        before (mdl/graph-dl g)
        {:keys [graph history stopped]} (sut/exhaustive-converge
                                          g
                                          (recognizer-opts {:parallel? true}))]
    (is (= :fixed-point stopped))
    (is (= [:brange] (mapv :reason history)))
    (is (< (mdl/graph-dl graph) before))
    (is (= [:out-brange] (mdl/selected-operators graph :out)))))

(deftest bounded-rewrites-converge-to-exhaustive-result
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        exhaustive (sut/exhaustive-converge
                    g
                    (recognizer-opts {:parallel? false}))
        bounded (sut/bounded-converge
                 g
                 [:out]
                 (recognizer-opts {:parallel? true
                                   :re-eval-budget 4}))]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mapv :reason (:history exhaustive))
           (mapv :reason (:history bounded))))
    (is (= (mdl/selected-operators (:graph exhaustive) :out)
           (mdl/selected-operators (:graph bounded) :out)))))

(deftest rewrite-search-returns-structured-resource-and-trace
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        result (sut/rewrite-search g [:out] (recognizer-opts {:parallel? false}))
        best (first (:candidates result))]
    (is (= :brange (:reason best)))
    (is (= :brange (:template-id best)))
    (is (= 1 (get-in result [:resource :nodes-considered])))
    (is (pos? (get-in result [:resource :templates-considered])))
    (is (pos? (get-in result [:resource :candidates-proposed])))
    (is (= (count (:candidates result))
           (get-in result [:resource :candidates-accepted])))
    (is (some #(and (= :template-proposal (:kind %))
                    (= :brange (:template-id %)))
              (:trace result)))))

(deftest primitive-search-does-not-invent-unconditioned-concat-splits
  (let [g (one-target-graph [1 2 3 4 5 6 7 8])
        result (sut/rewrite-search g [:out] (recognizer-opts {:parallel? false}))]
    (is (not-any? #(= :concat (:reason %)) (:candidates result)))
    (is (not-any? #(= :concat (:template-id %)) (:trace result)))))

(deftest primitive-search-does-not-invent-unconditioned-map-negate
  (let [g (one-target-graph [0 -1 -2 -3 -4])
        result (sut/rewrite-search g [:out] (recognizer-opts {:parallel? false}))]
    (is (not-any? #(= :map-negate (:reason %)) (:candidates result)))
    (is (not-any? #(= :map-negate (:template-id %)) (:trace result)))))

(deftest bounded-converge-records-step-and-terminal-resource
  (let [g (one-target-graph [0 1 2 3 4 5 6 7])
        result (sut/bounded-converge
                g
                [:out]
                (recognizer-opts {:parallel? false
                                  :re-eval-budget 4}))
        first-step (first (:steps result))]
    (is (= 1 (count (:history result))))
    (is (= 1 (count (:steps result))))
    (is (= :bounded (get-in first-step [:resource :mode])))
    (is (= 4 (get-in first-step [:resource :re-eval-budget])))
    (is (= 1 (get-in first-step [:resource :target-count])))
    (is (= :fixed-point (:stopped result)))
    (is (= 2 (get-in result [:resource :searches-run])))
    (is (= 1 (get-in result [:resource :applied-steps])))
    (is (some? (:terminal-search result)))
    (is (zero? (get-in result [:terminal-resource :candidates-accepted])))))

(deftest repeated-bounded-local-rewrites-reach-fixed-point
  (let [g (one-target-graph [0 1 2 3 4 5 6 7 8 9])
        exhaustive (sut/exhaustive-converge
                    g
                    (recognizer-opts {:parallel? false}))
        bounded (sut/bounded-converge
                 g
                 [:out]
                 (recognizer-opts {:parallel? true
                                   :re-eval-budget 8}))]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= :fixed-point (:stopped bounded)))
    (is (seq (:history bounded)))))


(deftest bounded-rewrites-over-target-set-converge-to-exhaustive-result
  (let [g (-> (graph/empty-graph)
              (graph/add-value :range [0 1 2 3 4 5])
              (graph/add-value :flat [9 9 9 9 9 9])
              (graph/set-roots [:range :flat]))
        exhaustive (sut/exhaustive-converge
                    g
                    (recognizer-opts {:parallel? false}))
        bounded (sut/bounded-converge
                 g
                 [:range :flat]
                 (recognizer-opts {:parallel? true
                                   :re-eval-budget 1}))]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mdl/selected-operators (:graph exhaustive) :range)
           (mdl/selected-operators (:graph bounded) :range)))
    (is (= (mdl/selected-operators (:graph exhaustive) :flat)
           (mdl/selected-operators (:graph bounded) :flat)))
    (is (= #{:brange} (set (map :reason (:history bounded)))))))


(deftest composed-rewrite-operators-participate-in-search
  (let [g (one-target-graph [2 5 8 11 14 17])
        operators [(rewrite/primitive-template-operator)
                   (rewrite/template-operator :builtin-composites
                                              (library/builtin-templates))]
        search-result (sut/rewrite-search g [:out] {:parallel? false
                                                       :rewrite-operators operators})
        candidates (:candidates search-result)
        best (first candidates)
        result (sut/exhaustive-converge g {:parallel? false
                                           :rewrite-operators operators})]
    (is (= [:primitive-templates :builtin-composites]
           (:rewrite-operator-ids search-result)))
    (is (= 2 (get-in search-result [:resource :rewrite-operators-considered])))
    (is (some #(and (= :rewrite-operator (:kind %))
                    (= :builtin-composites (:rewrite-operator-id %)))
              (:trace search-result)))
    (is (= :builtin-composites (:rewrite-operator-id best)))
    (is (= :linear-sequence (:reason best)))
    (is (= :fixed-point (:stopped result)))
    (is (= [:linear-sequence] (mapv :reason (:history result))))
    (is (= [:out-linear-sequence]
           (mdl/selected-operators (:graph result) :out)))
    (is (= [:linear-sequence 0 6 3 2]
           (mdl/selected-expression (:graph result) :out)))))

(deftest bounded-composite-rewrites-converge-to-exhaustive-result
  (let [g (one-target-graph [2 5 8 11 14 17])
        operators [(rewrite/primitive-template-operator)
                   (rewrite/template-operator :builtin-composites
                                              (library/builtin-templates))]
        exhaustive (sut/exhaustive-converge g {:parallel? false
                                               :rewrite-operators operators})
        bounded (sut/bounded-converge g [:out] {:parallel? true
                                                :re-eval-budget 4
                                                :rewrite-operators operators})]
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mapv :reason (:history exhaustive))
           (mapv :reason (:history bounded))))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))


(def square-range-op
  (composite/operator :square-range
                      [:mult [:brange 0 [:input :n 1]]
                       [:brange 0 [:input :n 1]]]))

(def square-range-template
  (rewrite/value-template
   :square-range
   (fn [g node-id xs _opts]
     (when (or (dense/ndarray? xs) (vector? xs))
       (let [values (if (dense/ndarray? xs) (dense/ravel xs) xs)
             n (count values)]
         (when (and (>= n 3)
                    (= values (mapv #(* % %) (range n))))
           (rewrite/candidate g node-id square-range-op [n] :square-range)))))))

(deftest injected-composite-template-compresses-square-range
  (let [g (one-target-graph [0 1 4 9 16 25])
        opts {:parallel? false
              :rewrite-operators [(rewrite/template-operator :square-range
                                                              [square-range-template])]}
        exhaustive (sut/exhaustive-converge g opts)
        bounded (sut/bounded-converge g [:out] (assoc opts :parallel? true
                                                      :re-eval-budget 1))]
    (is (= :fixed-point (:stopped exhaustive)))
    (is (= [:square-range] (mapv :reason (:history exhaustive))))
    (is (= [:square-range 6]
           (mdl/selected-expression (:graph exhaustive) :out)))
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))
