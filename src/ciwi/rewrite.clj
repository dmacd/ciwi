(ns ciwi.rewrite
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.value :as value]))

(defprotocol RewriteTemplate
  (template-id [template])
  (propose [template g node-id data opts]))

(defrecord ValueTemplate [id propose-fn]
  RewriteTemplate
  (template-id [_]
    id)
  (propose [_ g node-id data opts]
    (propose-fn g node-id data opts)))

(defn proposal
  "Build a structured rewrite proposal result."
  ([]
   (proposal []))
  ([candidates]
   (proposal candidates {} []))
  ([candidates resource trace]
   {:candidates (vec (remove nil? candidates))
    :resource (or resource {})
    :trace (vec trace)}))

(defn candidate-proposal
  [candidate]
  (proposal (cond-> [] candidate (conj candidate))))

(defn value-template
  "Create a local value-node rewrite template.

  `propose-fn` receives `g`, `node-id`, the node's plain data, and local search
  opts. It returns a structured proposal result.
  "
  [id propose-fn]
  (->ValueTemplate id propose-fn))

(defn- node-data
  [g node-id]
  (some-> (graph/node g node-id) :value :data))

(defn neg-delta?
  [candidate]
  (neg? (:delta candidate)))

(defn value-ref
  [x]
  {:kind :value
   :value x})

(defn node-ref
  [node-id]
  {:kind :node
   :node-id node-id})

(defn reusable-child-node?
  "True when `child-id` can be reused under `parent-id` without creating a cycle."
  [g parent-id child-id]
  (and (graph/value-node? (graph/node g child-id))
       (not (some #{parent-id}
                  (graph/walk g
                              child-id
                              {:above? false
                               :below? true
                               :include-self? true})))))

(defn- usable-child-ref?
  [g parent-id ref]
  (case (:kind ref)
    :value true
    :node (reusable-child-node? g parent-id (:node-id ref))
    false))

(defn- ref-dl
  [g ref]
  (case (:kind ref)
    :value (value/desc-len (value/value (:value ref)))
    :node (:dl (mdl/node-dl g (:node-id ref)))))

(defn- refs-dl
  [g operator child-refs]
  (+ (:dl operator)
     (reduce + 0.0 (map #(ref-dl g %) child-refs))))

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

(defn candidate-from-refs
  "Build a scored rewrite candidate from normalized child refs."
  ([g node-id operator child-refs reason]
   (candidate-from-refs g node-id operator child-refs reason nil))
  ([g node-id operator child-refs reason predicted-after]
   (let [child-refs (vec child-refs)]
     (when (every? #(usable-child-ref? g node-id %) child-refs)
       (let [before (:dl (mdl/node-dl g node-id))
             after (or predicted-after (refs-dl g operator child-refs))
             delta (- after before)]
         {:node-id node-id
          :op operator
          :child-refs child-refs
          :before before
          :after after
          :delta delta
          :reason reason})))))

(defn candidate
  "Build a scored rewrite candidate from materialized child values."
  ([g node-id operator children reason]
   (candidate g node-id operator children reason nil))
  ([g node-id operator children reason predicted-after]
   (candidate-from-refs g
                        node-id
                        operator
                        (mapv value-ref children)
                        reason
                        predicted-after)))

(defn- arithmetic-range?
  [xs]
  (and (vector? xs)
       (seq xs)
       (every? integer? xs)
       (= xs (vec (range (first xs) (+ (first xs) (count xs)))))))

(defn- brange-candidate
  [g node-id xs _opts]
  (candidate-proposal
   (when (and (arithmetic-range? xs)
              (>= (count xs) 2))
     (candidate g node-id op/brange [(first xs) (count xs)] :brange))))

(defn- repeat-candidate
  [g node-id xs _opts]
  (candidate-proposal
   (when (and (vector? xs)
              (>= (count xs) 2)
              (apply = xs))
     (candidate g node-id op/repeat [(first xs) (count xs)] :repeat))))

(defn- concat-candidates
  [g node-id xs _opts]
  (proposal
   (when (and (vector? xs)
              (>= (count xs) 4))
     (for [split (range 1 (count xs))]
       (candidate g node-id op/concat [(subvec xs 0 split)
                                       (subvec xs split)]
                  :concat)))))

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
  [g node-id xs _opts]
  (candidate-proposal
   (when-let [{:keys [start step n base]} (affine-sequence xs)]
     (when (and (zero? start)
                (not= 1 step)
                (not (zero? step)))
       (candidate g node-id op/mult [base step] :scale-mult (scale-mult-dl step n))))))

(defn- affine-candidate
  [g node-id xs _opts]
  (candidate-proposal
   (when-let [{:keys [start step n scaled]} (affine-sequence xs)]
     (when-not (or (zero? start)
                   (arithmetic-range? xs))
       (candidate g node-id op/add [scaled start] :affine-add (affine-add-dl start step n))))))

(defn- primitive-templates
  []
  [(value-template :brange brange-candidate)
   (value-template :repeat repeat-candidate)
   (value-template :scale-mult scale-mult-candidate)
   (value-template :affine-add affine-candidate)
   (value-template :concat concat-candidates)])

(defn- ensure-template
  [x]
  (if (satisfies? RewriteTemplate x)
    x
    (throw (ex-info "Expected RewriteTemplate" {:template x}))))

(defn- configured-templates
  [{:keys [extra-templates]}]
  (cond-> (vec (primitive-templates))
    (seq extra-templates) (into (map ensure-template extra-templates))))

(defn- proposal-result?
  [result]
  (and (map? result)
       (vector? (:candidates result))
       (map? (:resource result))
       (vector? (:trace result))))

(defn- require-proposal-result!
  [template result]
  (when-not (proposal-result? result)
    (throw (ex-info "RewriteTemplate must return a proposal result"
                    {:template-id (template-id template)
                     :result result})))
  result)

(defn- template-result
  [g node-id data opts template]
  (let [id (template-id template)
        result (require-proposal-result!
                template
                (propose template g node-id data opts))
        proposed (vec (remove nil? (:candidates result)))
        accepted (->> proposed
                      (filter neg-delta?)
                      (mapv #(assoc % :template-id id)))]
    {:template-id id
     :candidates accepted
     :resource {:templates-considered 1
                :candidates-proposed (count proposed)
                :candidates-accepted (count accepted)
                :candidates-rejected (- (count proposed) (count accepted))}
     :trace [{:kind :template-proposal
              :node-id node-id
              :template-id id
              :candidate-count (count proposed)
              :accepted-count (count accepted)
              :resource (:resource result)
              :trace (:trace result)}]}))

(defn- sum-resource
  [results k]
  (reduce + 0 (map #(get-in % [:resource k] 0) results)))

(defn proposal-for-node
  ([g node-id]
   (proposal-for-node g node-id {}))
  ([g node-id opts]
   (let [n (graph/node g node-id)
         data (node-data g node-id)]
     (if-not (and (graph/value-node? n) (some? data))
       {:node-id node-id
        :candidates []
        :resource {:nodes-considered 0
                   :templates-considered 0
                   :candidates-proposed 0
                   :candidates-accepted 0
                   :candidates-rejected 0}
        :trace []}
       (let [results (mapv #(template-result g node-id data opts %)
                           (configured-templates opts))
             candidates (->> results
                             (mapcat :candidates)
                             (sort-by (juxt :after :delta (comp str :reason)))
                             vec)]
         {:node-id node-id
          :candidates candidates
          :resource {:nodes-considered 1
                     :templates-considered (sum-resource results :templates-considered)
                     :candidates-proposed (sum-resource results :candidates-proposed)
                     :candidates-accepted (count candidates)
                     :candidates-rejected (sum-resource results :candidates-rejected)}
          :trace (vec (mapcat :trace results))})))))

(defn- validate-node-ref!
  [g parent-id node-id]
  (when-not (graph/value-node? (graph/node g node-id))
    (throw (ex-info "Rewrite child node ref must point to a value node"
                    {:parent-id parent-id
                     :node-id node-id})))
  (when-not (reusable-child-node? g parent-id node-id)
    (throw (ex-info "Rewrite child node ref would create a cycle"
                    {:parent-id parent-id
                     :node-id node-id})))
  node-id)

(defn- add-option-from-refs
  [g parent-id op child-refs]
  (let [op-id (graph/unique-id g (keyword (str (name parent-id) "-" (name (:id op)))))
        [g child-ids]
        (reduce (fn [[acc ids] [idx ref]]
                  (case (:kind ref)
                    :node
                    [acc (conj ids (validate-node-ref! acc parent-id (:node-id ref)))]

                    :value
                    (let [child-id (graph/unique-id acc
                                                    (keyword (str (name op-id) "-arg" idx)))]
                      [(graph/add-value acc child-id (:value ref))
                       (conj ids child-id)])))
                [g []]
                (map-indexed vector child-refs))]
    [(graph/add-operator g op-id op parent-id child-ids) op-id]))

(defn apply-candidate
  [g {:keys [node-id op child-refs]}]
  (first (add-option-from-refs g node-id op child-refs)))
