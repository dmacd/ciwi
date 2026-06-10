(ns ciwi.test-runner
  (:require [clojure.test :as test]
            [ciwi.alice.wunderbaum-test]
            [ciwi.alice-test]
            [ciwi.cache-test]
            [ciwi.compress-test]
            [ciwi.composite-test]
            [ciwi.conditions-test]
            [ciwi.dense-test]
            [ciwi.delayed-builder-test]
            [ciwi.dsl-test]
            [ciwi.enumerator-test]
            [ciwi.fix-test]
            [ciwi.graph-test]
            [ciwi.graph-rewrite-test]
            [ciwi.hashing-test]
            [ciwi.library-test]
            [ciwi.mdl-test]
            [ciwi.operator-test]
            [ciwi.optimize-test]
            [ciwi.propagation-test]
            [ciwi.search-test]
            [ciwi.structure-test]
            [ciwi.value-test]
            [ciwi.wunderbaum-test]))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (test/run-tests 'ciwi.alice-test
                                             'ciwi.alice.wunderbaum-test
                                             'ciwi.cache-test
                                             'ciwi.compress-test
                                             'ciwi.composite-test
                                             'ciwi.conditions-test
                                             'ciwi.dense-test
                                             'ciwi.delayed-builder-test
                                             'ciwi.dsl-test
                                             'ciwi.enumerator-test
                                             'ciwi.fix-test
                                             'ciwi.graph-test
                                             'ciwi.graph-rewrite-test
                                             'ciwi.hashing-test
                                             'ciwi.library-test
                                             'ciwi.mdl-test
                                             'ciwi.operator-test
                                             'ciwi.optimize-test
                                             'ciwi.propagation-test
                                             'ciwi.search-test
                                             'ciwi.structure-test
                                             'ciwi.value-test
                                             'ciwi.wunderbaum-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
