(ns ciwi.test-runner
  (:require [clojure.test :as test]
            [ciwi.core-test]))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (test/run-tests 'ciwi.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))

