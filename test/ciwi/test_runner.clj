(ns ciwi.test-runner
  (:require [clojure.test :as test]
            [ciwi.core-test]
            [ciwi.dsl-test]
            [ciwi.enumerator-test]
            [ciwi.graph-test]
            [ciwi.mdl-test]
            [ciwi.operator-test]
            [ciwi.optimize-test]
            [ciwi.propagation-test]
            [ciwi.search-test]
            [ciwi.structure-test]
            [ciwi.value-test]))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (test/run-tests 'ciwi.core-test
                                             'ciwi.dsl-test
                                             'ciwi.enumerator-test
                                             'ciwi.graph-test
                                             'ciwi.mdl-test
                                             'ciwi.operator-test
                                             'ciwi.optimize-test
                                             'ciwi.propagation-test
                                             'ciwi.search-test
                                             'ciwi.structure-test
                                             'ciwi.value-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
