(ns ciwi.clerk
  (:gen-class)
  (:require [clojure.string :as str]
            [nextjournal.clerk :as clerk]))

(def default-notebook
  "notebooks/ciwi/notebook/core_machinery.clj")

(def default-port
  7777)

(defn notebook-path?
  [path]
  (str/starts-with? path "notebooks/"))

(defn parse-args
  [args]
  (loop [args args
         opts {:notebook default-notebook
               :browse? false
               :port default-port}]
    (if-let [arg (first args)]
      (case arg
        "--browse"
        (recur (rest args) (assoc opts :browse? true))

        "--port"
        (recur (nnext args)
               (assoc opts :port (Integer/parseInt (second args))))

        (recur (rest args) (assoc opts :notebook arg)))
      opts)))

(defn -main
  [& args]
  (let [{:keys [notebook browse? port]} (parse-args args)]
    (println "Starting Clerk on" (str "http://localhost:" port))
    (println "Showing" notebook)
    (clerk/serve! {:browse browse?
                   :port port
                   :watch-paths ["notebooks" "src"]
                   :show-filter-fn notebook-path?})
    (clerk/show! notebook)
    @(promise)))
