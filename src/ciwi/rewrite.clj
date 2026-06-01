(ns ciwi.rewrite
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.value :as value]))

(defprotocol RewriteTemplate
  (template-id [template])
  (propose [template g node-id data]))

(defrecord ValueTemplate [id propose-fn]
  RewriteTemplate
  (template-id [_]
    id)
  (propose [_ g node-id data]
    (propose-fn g node-id data)))

(defn value-template
  "Create a local value-node rewrite template.

  `propose-fn` receives `g`, `node-id`, and the node's plain data, and returns a
  candidate, a sequence of candidates, or nil.
  "
  [id propose-fn]
  (->ValueTemplate id propose-fn))

(defn- node-data
  [g node-id]
  (some-> (graph/node g node-id) :value :data))

(defn neg-delta?
  [candidate]
  (neg? (:delta candidate)))

(defn- raw-children-dl
  [operator children]
  (+ (:dl operator)
     (reduce + 0.0 (map (comp value/desc-len value/value) children))))

(defn- brange-dl
  [start n]
  (raw-children-dl op/brange [start n]))

(defn- scale-mult-dl
  [step n]
  (+ (:dl op/mult)
     (brange-dl 0 n)
     (value/desc-len (value/value step))))

(defn- affine-add-dl
  [start step n]
  (+ (:dl op/add)
     (scale-mult-dl step n)
     (value/desc-len (value/value start))))

(defn candidate
  "Build a scored rewrite candidate for adding `operator` under `node-id`."
  ([g node-id operator children reason]
   (candidate g node-id operator children reason nil))
  ([g node-id operator children reason predicted-after]
   (let [before (:dl (mdl/node-dl g node-id))
         after (or predicted-after (raw-children-dl operator children))
         delta (- after before)]
     {:node-id node-id
      :op operator
      :children (vec children)
      :before before
      :after after
      :delta delta
      :reason reason})))

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

(defn- affine-sequence
  [xs]
  (when (and (vector? xs)
             (>= (count xs) 3)
             (every? number? xs))
    (let [start (first xs)
          step (- (second xs) start)]
      (when (= xs (mapv #(+ start (* step %)) (range (count xs))))
        {:start start
         :step step
         :n (count xs)
         :base (vec (range (count xs)))
         :scaled (mapv #(* step %) (range (count xs)))}))))

(defn- scale-mult-candidate
  [g node-id xs]
  (when-let [{:keys [start step n base]} (affine-sequence xs)]
    (when (and (zero? start)
               (not= 1 step)
               (not (zero? step)))
      (candidate g node-id op/mult [base step] :scale-mult (scale-mult-dl step n)))))

(defn- affine-candidate
  [g node-id xs]
  (when-let [{:keys [start step n scaled]} (affine-sequence xs)]
    (when-not (or (zero? start)
                  (arithmetic-range? xs))
      (candidate g node-id op/add [scaled start] :affine-add (affine-add-dl start step n)))))

(defn- primitive-templates
  []
  [(value-template :brange brange-candidate)
   (value-template :repeat repeat-candidate)
   (value-template :scale-mult scale-mult-candidate)
   (value-template :affine-add affine-candidate)
   (value-template :concat concat-candidates)])

(defn- ensure-template
  [x]
  (cond
    (satisfies? RewriteTemplate x) x
    (fn? x) (value-template :anonymous x)
    :else (throw (ex-info "Expected RewriteTemplate or function" {:template x}))))

(defn- configured-templates
  [{:keys [extra-templates]}]
  (cond-> (vec (primitive-templates))
    (seq extra-templates) (into (map ensure-template extra-templates))))

(defn- result-candidates
  [result]
  (cond
    (nil? result) []
    (map? result) [result]
    (sequential? result) result
    :else [result]))

(defn candidates-for-node
  ([g node-id]
   (candidates-for-node g node-id {}))
  ([g node-id opts]
   (let [n (graph/node g node-id)
         data (node-data g node-id)]
     (if-not (and (graph/value-node? n) (some? data))
       []
       (->> (configured-templates opts)
            (mapcat (fn [template]
                      (result-candidates (propose template g node-id data))))
            (remove nil?)
            (filter neg-delta?)
            (sort-by (juxt :after :delta (comp str :reason)))
            vec)))))

(defn apply-candidate
  [g {:keys [node-id op children]}]
  (first (graph/add-derived-option g node-id op children)))
