(ns ciwi.library-test
  (:require [ciwi.library :as sut]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.search :as search]
            [ciwi.value :as value]
            [ciwi.graph :as graph]
            [clojure.test :refer [deftest is]]))

(defn- data-results
  [results]
  (mapv #(mapv value/datum %) results))

(defn- one-target-graph
  [x]
  (-> (graph/empty-graph)
      (graph/add-value :out x)))

(deftest loads-composite-definition-as-native-operator
  (let [library (sut/load-definitions
                 [{:kind :composite
                   :id :increment
                   :expr [:add [:input :x 0] 1]
                   :metadata {:origin :test}}])
        increment (get-in library [:operators :increment])]
    (is (= 42
           (value/datum (op/apply-op increment [(value/value 41)]))))
    (is (= [[41]]
           (data-results (op/invert-op increment
                                       (value/value 42)
                                       []
                                       []))))))

(deftest loaded-template-drives-local-rewrite-search
  (let [library (sut/load-definitions sut/builtin-definitions)
        g (one-target-graph [2 5 8 11 14 17])
        exhaustive (search/exhaustive-converge
                    g
                    {:parallel? false
                     :extra-templates (:templates library)})
        bounded (search/bounded-converge
                 g
                 [:out]
                 {:parallel? true
                  :re-eval-budget 4
                  :extra-templates (:templates library)})]
    (is (= [:linear-sequence] (mapv :reason (:history exhaustive))))
    (is (= [:linear-sequence 0 6 3 2]
           (mdl/selected-expression (:graph exhaustive) :out)))
    (is (= (:dl exhaustive) (:dl bounded)))
    (is (= (mdl/selected-expression (:graph exhaustive) :out)
           (mdl/selected-expression (:graph bounded) :out)))))

(deftest persists-and-loads-edn-definitions
  (let [defs [{:kind :composite
               :id :square-range
               :expr [:mult [:brange 0 [:input :n 1]]
                      [:brange 0 [:input :n 1]]]}
              {:kind :rewrite-template
               :id :square-range
               :operator :square-range
               :matcher {:kind :square-range}
               :children [:n]
               :reason :square-range}]
        file (java.io.File/createTempFile "ciwi-library" ".edn")]
    (try
      (sut/write-definitions! file defs)
      (let [library (sut/load-file file)
            g (one-target-graph [0 1 4 9 16 25])
            result (search/exhaustive-converge
                    g
                    {:parallel? false
                     :extra-templates (:templates library)})]
        (is (= [:square-range] (mapv :reason (:history result))))
        (is (= [:square-range 6]
               (mdl/selected-expression (:graph result) :out))))
      (finally
        (.delete file)))))
