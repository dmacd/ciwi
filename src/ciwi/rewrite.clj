(ns ciwi.rewrite
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.value :as value])
  (:import [java.util.concurrent Callable Executors TimeUnit]))

(defprotocol RewriteTemplate
  (template-id [template])
  (propose [template g node-id data opts]))

(defprotocol RewriteOperator
  (rewrite-operator-id [operator])
  (run-rewrite [operator g node-ids opts]))

(defrecord ValueTemplate [id propose-fn]
  RewriteTemplate
  (template-id [_]
    id)
  (propose [_ g node-id data opts]
    (propose-fn g node-id data opts)))

(defn value-template
  "Create a local value-node rewrite template."
  [id propose-fn]
  (->ValueTemplate id propose-fn))

(defn value-node-ids
  [g node-ids]
  (->> node-ids
       distinct
       (filter #(graph/value-node? (graph/node g %)))
       vec))

(defn parallel-mapv
  [f xs]
  (let [xs (vec xs)]
    (if (< (count xs) 2)
      (mapv f xs)
      (let [n-threads (max 1 (min (count xs)
                                  (.availableProcessors (Runtime/getRuntime))))
            pool (Executors/newFixedThreadPool n-threads)
            tasks (mapv (fn [x]
                          (reify Callable
                            (call [_]
                              (f x))))
                        xs)]
        (try
          (mapv #(.get %) (.invokeAll pool tasks))
          (finally
            (.shutdown pool)
            (.awaitTermination pool 5 TimeUnit/SECONDS)))))))

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
  (when (and (arithmetic-range? xs)
             (>= (count xs) 2))
    (candidate g node-id op/brange [(first xs) (count xs)] :brange)))

(defn- repeat-candidate
  [g node-id xs _opts]
  (when (and (vector? xs)
             (>= (count xs) 2)
             (apply = xs))
    (candidate g node-id op/repeat [(first xs) (count xs)] :repeat)))

(defn- concat-candidates
  [g node-id xs _opts]
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
  [g node-id xs _opts]
  (when-let [{:keys [start step n base]} (affine-sequence xs)]
    (when (and (zero? start)
               (not= 1 step)
               (not (zero? step)))
      (candidate g node-id op/mult [base step] :scale-mult (scale-mult-dl step n)))))

(defn- affine-candidate
  [g node-id xs _opts]
  (when-let [{:keys [start step n scaled]} (affine-sequence xs)]
    (when-not (or (zero? start)
                  (arithmetic-range? xs))
      (candidate g node-id op/add [scaled start] :affine-add (affine-add-dl start step n)))))

(defn primitive-templates
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

(defn- result-candidates
  [result]
  (cond
    (nil? result) []
    (map? result) [result]
    (sequential? result) result
    :else (throw (ex-info "RewriteTemplate returned invalid candidate result"
                          {:result result}))))

(defn- run-template
  [g node-id data opts rewrite-operator-id template]
  (let [template-id (template-id template)
        proposed (vec (remove nil? (result-candidates (propose template g node-id data opts))))
        accepted (->> proposed
                      (filter neg-delta?)
                      (mapv #(assoc %
                                    :rewrite-operator-id rewrite-operator-id
                                    :template-id template-id)))]
    {:template-id template-id
     :candidates accepted
     :resource {:templates-considered 1
                :candidates-proposed (count proposed)
                :candidates-accepted (count accepted)
                :candidates-rejected (- (count proposed) (count accepted))}
     :trace [{:kind :template-proposal
              :node-id node-id
              :rewrite-operator-id rewrite-operator-id
              :template-id template-id
              :candidate-count (count proposed)
              :accepted-count (count accepted)}]}))

(defn- sum-resource
  [results k]
  (reduce + 0 (map #(get-in % [:resource k] 0) results)))

(defn- template-node-result
  [g node-id opts rewrite-operator-id templates]
  (let [data (node-data g node-id)
        results (mapv #(run-template g node-id data opts rewrite-operator-id %)
                      templates)
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
     :trace (vec (mapcat :trace results))}))

(defrecord TemplateRewriteOperator [id templates]
  RewriteOperator
  (rewrite-operator-id [_]
    id)
  (run-rewrite [_ g node-ids opts]
    (let [items (value-node-ids g node-ids)
          templates (mapv ensure-template templates)
          node-fn #(template-node-result g % opts id templates)
          node-results (if (:parallel? opts)
                         (parallel-mapv node-fn items)
                         (mapv node-fn items))
          candidates (->> node-results
                          (mapcat :candidates)
                          (sort-by (juxt :after :delta (comp str :node-id) (comp str :reason)))
                          vec)
          resource {:rewrite-operators-considered 1
                    :nodes-considered (sum-resource node-results :nodes-considered)
                    :templates-considered (sum-resource node-results :templates-considered)
                    :candidates-proposed (sum-resource node-results :candidates-proposed)
                    :candidates-accepted (count candidates)
                    :candidates-rejected (sum-resource node-results :candidates-rejected)}]
      {:rewrite-operator-id id
       :node-ids items
       :candidates candidates
       :resource resource
       :trace (into [{:kind :rewrite-operator
                      :rewrite-operator-id id
                      :resource resource}]
                    (mapcat :trace node-results))})))

(defn template-operator
  ([templates]
   (template-operator :template-sweep templates))
  ([id templates]
   (->TemplateRewriteOperator id (mapv ensure-template templates))))

(defn primitive-template-operator
  []
  (template-operator :primitive-templates (primitive-templates)))

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
