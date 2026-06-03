(ns ciwi.search
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.rewrite :as rewrite]))

(defn- sum-resource
  [results k]
  (reduce + 0 (map #(get-in % [:resource k] 0) results)))

(defn- ensure-rewrite-operator
  [x]
  (if (satisfies? rewrite/RewriteOperator x)
    x
    (throw (ex-info "Expected RewriteOperator" {:rewrite-operator x}))))

(defn- configured-rewrite-operators
  [{:keys [rewrite-operators]}]
  (if (seq rewrite-operators)
    (mapv ensure-rewrite-operator rewrite-operators)
    [(rewrite/primitive-template-operator)]))

(defn rewrite-search
  "Run composed rewrite operators over local value nodes."
  [g node-ids opts]
  (let [requested-node-ids (vec node-ids)
        items (rewrite/value-node-ids g requested-node-ids)
        opts (assoc (merge {:parallel? true} opts)
                    :local-node-ids
                    (or (:local-node-ids opts) items))
        operators (configured-rewrite-operators opts)
        operator-results (mapv #(rewrite/run-rewrite % g items opts) operators)
        candidates (->> operator-results
                        (mapcat :candidates)
                        (sort-by (juxt :after :delta (comp str :node-id) (comp str :reason)))
                        vec)
        trace (vec (mapcat :trace operator-results))]
    {:node-ids items
     :requested-node-ids requested-node-ids
     :rewrite-operator-ids (mapv rewrite/rewrite-operator-id operators)
     :candidates candidates
     :resource {:rewrite-operators-considered (count operators)
                :parallel? (boolean (:parallel? opts))
                :nodes-requested (count requested-node-ids)
                :nodes-considered (sum-resource operator-results :nodes-considered)
                :local-node-count (count (:local-node-ids opts))
                :templates-considered (sum-resource operator-results :templates-considered)
                :candidates-proposed (sum-resource operator-results :candidates-proposed)
                :candidates-accepted (count candidates)
                :candidates-rejected (sum-resource operator-results :candidates-rejected)
                :generated-expressions (sum-resource operator-results :generated-expressions)
                :generated-edits (sum-resource operator-results :generated-edits)}
     :trace trace}))

(defn- step-from-search
  [g mode search-result resource-extra]
  (let [candidate (first (:candidates search-result))]
    (cond-> {:candidate candidate
             :search search-result
             :resource (merge (:resource search-result)
                              {:mode mode
                               :applied? (boolean candidate)}
                              resource-extra)
             :trace (:trace search-result)}
      candidate (assoc :graph (rewrite/apply-candidate g candidate)))))

(defn exhaustive-step
  [g opts]
  (step-from-search g :exhaustive (rewrite-search g (graph/value-ids g) opts) {}))

(defn bounded-step
  [g target-ids {:keys [re-eval-budget] :as opts
                 :or {re-eval-budget 32}}]
  (let [neighborhoods (mapv (fn [target-id]
                              {:target-id target-id
                               :node-ids (vec (graph/neighborhood g target-id re-eval-budget))})
                            target-ids)
        node-ids (->> neighborhoods
                      (mapcat :node-ids)
                      distinct
                      vec)
        search-result (rewrite-search g node-ids opts)
        resource-extra {:target-count (count target-ids)
                        :re-eval-budget re-eval-budget
                        :neighborhood-count (count neighborhoods)
                        :neighborhood-node-visits (reduce + 0 (map (comp count :node-ids)
                                                                   neighborhoods))
                        :candidate-node-count (count node-ids)}
        step (step-from-search g :bounded search-result resource-extra)]
    (-> step
        (assoc :neighborhoods neighborhoods)
        (update :trace into [{:kind :bounded-neighborhoods
                              :target-ids (vec target-ids)
                              :re-eval-budget re-eval-budget
                              :neighborhoods neighborhoods}]))))

(def ^:private aggregate-resource-keys
  [:rewrite-operators-considered
   :nodes-requested
   :nodes-considered
   :local-node-count
   :templates-considered
   :candidates-proposed
   :candidates-accepted
   :candidates-rejected
   :generated-expressions
   :generated-edits
   :neighborhood-node-visits])

(defn- aggregate-resources
  [resources]
  (into {}
        (for [k aggregate-resource-keys]
          [k (reduce + 0 (map #(get % k 0) resources))])))

(defn- convergence-resource
  [steps terminal-step stopped]
  (let [resources (cond-> (mapv :resource steps)
                    terminal-step (conj (:resource terminal-step)))]
    (assoc (aggregate-resources resources)
           :stopped stopped
           :applied-steps (count steps)
           :searches-run (count resources))))

(defn- applied-step
  [n current {:keys [candidate graph] :as step}]
  {:step n
   :candidate candidate
   :search (:search step)
   :resource (:resource step)
   :trace (:trace step)
   :neighborhoods (:neighborhoods step)
   :dl-before (mdl/graph-dl current)
   :dl-after (mdl/graph-dl graph)})

(defn- result
  [current history steps stopped terminal-step]
  (cond-> {:graph current
           :history history
           :steps steps
           :trace (vec (mapcat :trace steps))
           :resource (convergence-resource steps terminal-step stopped)
           :dl (mdl/graph-dl current)
           :stopped stopped}
    terminal-step (assoc :terminal-search (:search terminal-step)
                         :terminal-resource (:resource terminal-step))))

(defn converge
  [g step-fn {:keys [max-steps]
              :or {max-steps 100}
              :as opts}]
  (loop [current g
         history []
         steps []
         n 0]
    (if (>= n max-steps)
      (result current history steps :max-steps nil)
      (let [step (step-fn current opts)]
        (if-let [candidate (:candidate step)]
          (recur (:graph step)
                 (conj history candidate)
                 (conj steps (applied-step n current step))
                 (inc n))
          (result current history steps :fixed-point step))))))

(defn exhaustive-converge
  [g opts]
  (converge g exhaustive-step opts))

(defn bounded-converge
  [g target-ids opts]
  (converge g #(bounded-step % target-ids %2) opts))
