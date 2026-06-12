(ns ciwi.alice.wunderbaum.greedy
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum.context :as wb-context]
            [ciwi.alice.wunderbaum.render :as render]
            [ciwi.cache :as cache]
            [ciwi.value :as value]))

(defn- same-leaf?
  [left right]
  (and (= (:target-index left) (:target-index right))
       (= (:path left) (:path right))))

(defn- free-value-key
  [v]
  [(:spec v) (:data v)])

(defn- add-free-value
  ([state x]
   (add-free-value state x wb-context/free-value))
  ([[values seen] x value-fn]
   (let [v (value-fn x)
         k (free-value-key v)]
     (if (contains? seen k)
       [values seen]
       [(conj values v) (conj seen k)]))))

(defn- add-default-free-values
  [[values seen :as state]]
  (let [data (map :data values)
        int-one? (some #(and (integer? %) (= 1 %)) data)
        float? (some float? data)
        state (if int-one?
                state
                (add-free-value state 1))]
    (if float?
      state
      (add-free-value state 1.5))))

(defn- leaf-free-values
  [search-context target-trees leaf]
  (let [current-leaf-data (->> (map-indexed vector target-trees)
                               (mapcat (fn [[idx tree]]
                                         (render/target-tree-leaves
                                          idx
                                          (nth (wb-context/target-ids
                                                (count target-trees))
                                               idx)
                                          tree)))
                               (remove #(same-leaf? leaf %))
                               (map :data))
        state (reduce #(add-free-value %1 %2 wb-context/free-anchor-value)
                      [[] #{}]
                      current-leaf-data)
        state (reduce add-free-value state (:free-values search-context))
        [values _seen] (add-default-free-values state)]
    values))

(defn- task-leaves
  [target-trees]
  (let [ids (wb-context/target-ids (count target-trees))]
    (->> (map-indexed vector target-trees)
         (mapcat (fn [[idx tree]]
                   (render/target-tree-leaves idx (nth ids idx) tree)))
         vec)))

(def leaf-selection-policies
  "Greedy task leaf-selection policies.

  `:python-test-parity` mirrors Python GreedyAlice: step 0 uses task target
  order, later steps sort leaves by descending DL. `:largest-dl` always sorts
  by descending DL and is the less surprising policy for non-parity callers.
  "
  #{:python-test-parity
    :largest-dl})

(defn- require-leaf-selection-policy
  [policy]
  (when-not (contains? leaf-selection-policies policy)
    (throw (ex-info "Unknown Alice leaf-selection policy"
                    {:policy policy
                     :allowed leaf-selection-policies})))
  policy)

(defn- sorted-by-dl
  [leaves]
  (vec (sort-by (juxt #(- (:dl %)) :target-index :path) leaves)))

(defn- task-leaf-order
  [target-trees policy step-index]
  (let [leaves (task-leaves target-trees)]
    (case policy
      :python-test-parity
      (if (pos? step-index)
        (sorted-by-dl leaves)
        leaves)

      :largest-dl
      (sorted-by-dl leaves))))

(defn- native-solution-predicate
  [solution]
  (cond
    (nil? solution)
    nil

    (fn? solution)
    solution

    :else
    (throw (ex-info "Alice solution hints must be native candidate predicates"
                    {:solution solution}))))

(defn- combine-candidate-predicates
  [left right]
  (cond
    (and left right)
    (fn [summary]
      (and (left summary)
           (right summary)))

    left
    left

    right
    right))

(defn- step-candidate-opts
  [opts solution]
  (let [predicate (combine-candidate-predicates
                   (:candidate-predicate opts)
                   (native-solution-predicate solution))]
    (cond-> {}
      predicate
      (assoc :candidate-predicate predicate))))

(defn- with-step-stats
  [opts candidate-opts]
  (if (:collect-wunderbaum-stats? opts)
    (let [stats (atom {})]
      [(assoc candidate-opts :wunderbaum-stats-atom stats) stats])
    [candidate-opts nil]))

(defn- compress-leaf
  [search-context opts min-compression-rate target-trees leaf solution]
  (let [values (into [(wb-context/target-value (:data leaf))]
                     (leaf-free-values search-context target-trees leaf))
        [candidate-opts stats]
        (with-step-stats opts (step-candidate-opts opts solution))
        threshold-dl (* (:dl leaf)
                        (- 1.0 min-compression-rate))
        t0 (System/nanoTime)
        search (wb-context/first-candidate-at-rate
                (:dl leaf)
                min-compression-rate
                (wb-context/candidate-seq search-context
                                          values
                                          (assoc candidate-opts
                                                 :threshold-dl threshold-dl
                                                 :score-target-count 1)))
        elapsed-ms (/ (double (- (System/nanoTime) t0)) 1000000.0)]
    (if-let [candidate (:candidate search)]
      (let [replacement (render/candidate-tree search-context candidate :target0)]
        (cond-> {:leaf leaf
                 :candidate candidate
                 :replacement-tree replacement
                 :selected (render/tree-expr replacement)
                 :initial-dl (:dl leaf)
                 :dl (:dl replacement)
                 :compression-rate (alice/compression-rate (:dl leaf)
                                                           (:dl replacement))
                 :candidates-consumed (:candidates-consumed search)
                 :search-elapsed-ms elapsed-ms
                 :stop-reason (:stop-reason search)}
          stats (assoc :wunderbaum-stats @stats)))
      (cond-> {:leaf leaf
               :candidates-consumed (:candidates-consumed search)
               :search-elapsed-ms elapsed-ms
               :stop-reason (:stop-reason search)}
        stats (assoc :wunderbaum-stats @stats)))))

(defn- first-successful-compression
  [search-context opts min-compression-rate worthy-dl leaf-selection-policy
   target-trees step-index]
  (loop [leaves (task-leaf-order target-trees leaf-selection-policy step-index)
         consumed 0
         attempts []]
    (if-let [leaf (first leaves)]
      (if (< (:dl leaf) worthy-dl)
        {:replacement-tree nil
         :candidates-consumed consumed
         :attempts attempts
         :stop-reason :leaf-below-worthy}
        (let [solution (get (:solutions search-context) step-index)
              attempt (compress-leaf search-context
                                     opts
                                     min-compression-rate
                                     target-trees
                                     leaf
                                     solution)
              consumed (+ consumed (:candidates-consumed attempt))
              attempt (assoc attempt :candidates-consumed consumed)]
          (if (:replacement-tree attempt)
            (assoc attempt :attempts attempts)
            (recur (rest leaves)
                   consumed
                   (conj attempts (select-keys attempt
                                               [:leaf
                                                :candidates-consumed
                                                :stop-reason]))))))
      {:replacement-tree nil
       :candidates-consumed consumed
       :attempts attempts
       :stop-reason :no-worthy-leaves})))

(defn- apply-compression
  [target-trees {:keys [leaf replacement-tree]}]
  (update target-trees
          (:target-index leaf)
          render/replace-tree
          (:path leaf)
          replacement-tree))

(defn- record-step
  [{:keys [leaf selected initial-dl dl compression-rate candidates-consumed
           search-elapsed-ms stop-reason wunderbaum-stats]}]
  (cond-> {:target-id (:target-id leaf)
           :path (:path leaf)
           :initial-dl initial-dl
           :dl dl
           :compression-rate compression-rate
           :selected selected
           :candidates-consumed candidates-consumed
           :search-elapsed-ms search-elapsed-ms
           :stop-reason stop-reason}
    wunderbaum-stats (assoc :wunderbaum-stats wunderbaum-stats)))

(defn- nonpermeable-dl
  [value-dl-cache values]
  (reduce + 0.0
          (map #(value/desc-len-cached value-dl-cache %)
               (remove :permeable? values))))

(defn compression-step-candidate
  "Run Python `GreedyAlice.compression_step`-shaped candidate search.

  This searches one target value with explicit free values. It does not choose
  among task leaves; callers that want Python `run_task` behavior should use
  `run-compression-step` or `run-greedy-task`.
  "
  [target free-values opts]
  (let [target-value (wb-context/target-value target)
        free-values (mapv wb-context/free-value free-values)
        values (into [target-value] free-values)
        task (alice/compression-task []
                                     {:name "compression_step"})
        search-context (wb-context/task-search-context task opts)
        value-dl-cache (cache/value-dl-cache (:cache-context search-context))
        initial-dl (nonpermeable-dl value-dl-cache values)
        min-compression-rate (alice/require-rate-fraction
                              :min-compression-rate
                              (or (:min-compression-rate opts) 0.01))
        threshold-dl (* initial-dl (- 1.0 min-compression-rate))
        search (wb-context/first-candidate-at-rate
                initial-dl
                min-compression-rate
                (wb-context/candidate-seq search-context
                                          values
                                          {:threshold-dl threshold-dl}))]
    (if-let [candidate (:candidate search)]
      {:candidate candidate
       :initial-dl initial-dl
       :dl (:dl candidate)
       :compression-rate (:compression-rate search)
       :candidates-consumed (:candidates-consumed search)
       :stop-reason (:stop-reason search)
       :selected (get (:selected candidate) :target0)}
      {:candidate nil
       :initial-dl initial-dl
       :candidates-consumed (:candidates-consumed search)
       :stop-reason (:stop-reason search)
       :compression-rate 0.0})))

(defn- greedy-result
  [task search-context initial-dl target-trees steps resource]
  (let [dl (render/target-tree-dl target-trees)
        rate (alice/compression-rate initial-dl dl)]
    {:task-name (:name task)
     :initial-dl initial-dl
     :dl dl
     :compression-rate rate
     :meets-threshold? (>= rate (double (:threshold-rate task)))
     :selected (render/selected-targets target-trees)
     :steps steps
     :operator-count (count (:ops-with-counts search-context))
     :resource resource}))

(defn- run-greedy*
  [task opts {:keys [mode stop-at-task-threshold? max-steps]
              :or {stop-at-task-threshold? true}}]
  (let [{:keys [targets initial-dl cache-context] :as search-context}
        (assoc (wb-context/task-search-context task opts)
               :solutions (:solutions task))
        value-dl-cache (cache/value-dl-cache cache-context)
        target-trees (mapv #(render/raw-tree value-dl-cache %) targets)
        min-compression-rate (alice/require-rate-fraction
                              :min-compression-rate
                              (or (:min-compression-rate opts) 0.01))
        leaf-selection-policy (require-leaf-selection-policy
                               (or (:leaf-selection-policy opts)
                                   :python-test-parity))
        worthy-dl (double (or (:worthy-dl opts) 200.0))
        max-steps (or max-steps (:max-steps opts) Long/MAX_VALUE)]
    (loop [target-trees target-trees
           steps []
           consumed 0]
      (let [dl (render/target-tree-dl target-trees)
            rate (alice/compression-rate initial-dl dl)]
        (cond
          (and stop-at-task-threshold?
               (>= rate (double (:threshold-rate task))))
          (greedy-result task
                         search-context
                         initial-dl
                         target-trees
                         steps
                         {:mode mode
                          :stop-reason :threshold-reached
                          :steps (count steps)
                          :candidates-consumed consumed
                          :min-compression-rate min-compression-rate
                          :leaf-selection-policy leaf-selection-policy
                          :worthy-dl worthy-dl})

          (>= (count steps) max-steps)
          (greedy-result task
                         search-context
                         initial-dl
                         target-trees
                         steps
                         {:mode mode
                          :stop-reason :max-steps
                          :steps (count steps)
                          :candidates-consumed consumed
                          :min-compression-rate min-compression-rate
                          :leaf-selection-policy leaf-selection-policy
                          :worthy-dl worthy-dl})

          :else
          (let [step (first-successful-compression search-context
                                                   opts
                                                   min-compression-rate
                                                   worthy-dl
                                                   leaf-selection-policy
                                                   target-trees
                                                   (count steps))
                consumed (+ consumed (:candidates-consumed step))]
            (if (:replacement-tree step)
              (recur (apply-compression target-trees step)
                     (conj steps (record-step step))
                     consumed)
              (greedy-result task
                             search-context
                             initial-dl
                             target-trees
                             steps
                             {:mode mode
                              :stop-reason (:stop-reason step)
                              :steps (count steps)
                              :candidates-consumed consumed
                              :attempts (:attempts step)
                              :min-compression-rate min-compression-rate
                              :leaf-selection-policy leaf-selection-policy
                              :worthy-dl worthy-dl}))))))))

(defn run-greedy-task
  "Run a Python GreedyAlice-shaped task.

  Each iteration uses `:leaf-selection-policy` and accepts the first Wunderbaum
  candidate above `:min-compression-rate`, rewrites that local leaf, and
  repeats until the task threshold is met or no worthy leaf can be improved.
  The default policy is `:python-test-parity`; use `:largest-dl` when not
  matching Python tests. Rates are fractions, so `0.01` means a
  one-in-one-hundred DL reduction. The operator registry is always injected by
  the caller.
  "
  [task opts]
  (run-greedy* task opts {:mode :greedy-task}))

(defn run-compression-step
  "Run a Python-style compression step with a minimum compression rate.

  `:min-compression-rate` is a fraction. The default `0.01` corresponds to
  Python GreedyAlice's `min_rate=0.01`.
  "
  [task opts]
  (run-greedy* task
               opts
               {:mode :greedy-compression-step
                :stop-at-task-threshold? false
                :max-steps 1}))
