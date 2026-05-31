(ns ciwi.rewrite
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.value :as value]))

(defn- node-data
  [g node-id]
  (some-> (graph/node g node-id) :value :data))

(defn neg-delta?
  [candidate]
  (neg? (:delta candidate)))

(defn- candidate
  [g node-id operator children reason]
  (let [before (:dl (mdl/node-dl g node-id))
        after (+ (:dl operator)
                 (reduce + 0.0 (map (comp value/desc-len value/value) children)))
        delta (- after before)]
    {:node-id node-id
     :op operator
     :children (vec children)
     :before before
     :after after
     :delta delta
     :reason reason}))

(defn- arithmetic-range?
  [xs]
  (and (vector? xs)
       (seq xs)
       (every? integer? xs)
       (= xs (vec (range (first xs) (+ (first xs) (count xs)))))))

(defn- brange-candidate
  [g node-id xs]
  (when (and (arithmetic-range? xs)
             (>= (count xs) 2))
    (candidate g node-id op/brange [(first xs) (count xs)] :brange)))

(defn- repeat-candidate
  [g node-id xs]
  (when (and (vector? xs)
             (>= (count xs) 2)
             (apply = xs))
    (candidate g node-id op/repeat [(first xs) (count xs)] :repeat)))

(defn- concat-candidates
  [g node-id xs]
  (when (and (vector? xs)
             (>= (count xs) 4))
    (for [split (range 1 (count xs))]
      (candidate g node-id op/concat [(subvec xs 0 split)
                                      (subvec xs split)]
                 :concat))))

(defn candidates-for-node
  [g node-id]
  (let [n (graph/node g node-id)
        data (node-data g node-id)]
    (if-not (and (graph/value-node? n) (some? data))
      []
      (->> (concat [(brange-candidate g node-id data)
                    (repeat-candidate g node-id data)]
                   (concat-candidates g node-id data))
           (remove nil?)
           (filter neg-delta?)
           (sort-by (juxt :after :delta (comp str :reason)))
           vec))))

(defn apply-candidate
  [g {:keys [node-id op children]}]
  (first (graph/add-derived-option g node-id op children)))
