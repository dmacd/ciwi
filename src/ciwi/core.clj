(ns ciwi.core)

(defn ready?
  "Returns true when the Clojure prototype environment is runnable."
  []
  true)

(defn -main
  [& _args]
  (println "ciwi Clojure prototype environment is ready"))

