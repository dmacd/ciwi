(ns ciwi.compress
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.search :as search]))

(defn target-ids
  [g targets]
  (vec (or targets (graph/roots g))))

(defn compress-exhaustive
  ([g]
   (compress-exhaustive g {}))
  ([g opts]
   (let [result (search/exhaustive-converge g opts)]
     (assoc result
            :mode :exhaustive
            :selected (into {}
                            (map (fn [id]
                                   [id (mdl/selected-expression (:graph result) id)]))
                            (graph/roots (:graph result)))))))

(defn compress-bounded
  ([g targets]
   (compress-bounded g targets {}))
  ([g targets opts]
   (let [targets (target-ids g targets)
         result (search/bounded-converge g targets opts)]
     (assoc result
            :mode :bounded
            :targets targets
            :selected (into {}
                            (map (fn [id]
                                   [id (mdl/selected-expression (:graph result) id)]))
                            targets)))))

(defn same-compression?
  [left right targets]
  (and (= (:dl left) (:dl right))
       (= (select-keys (:selected left) targets)
          (select-keys (:selected right) targets))))
