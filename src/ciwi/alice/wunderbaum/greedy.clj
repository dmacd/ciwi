(ns ciwi.alice.wunderbaum.greedy
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum.context :as wb-context]
            [ciwi.alice.wunderbaum.render :as render]
            [ciwi.cache :as cache]))

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

(defn- worthy-leaves
  [target-trees worthy-dl]
  (let [ids (wb-context/target-ids (count target-trees))]
    (->> (map-indexed vector target-trees)
         (mapcat (fn [[idx tree]]
                   (render/target-tree-leaves idx (nth ids idx) tree)))
         (filter #(>= (:dl %) worthy-dl))
         (sort-by (juxt #(- (:dl %)) :target-index :path))
         vec)))

(defn- compress-leaf
  [search-context min-compression-rate target-trees leaf]
  (let [values (into [(wb-context/target-value (:data leaf))]
                     (leaf-free-values search-context target-trees leaf))
        threshold-dl (* (:dl leaf)
                        (- 1.0 (/ min-compression-rate 100.0)))
        search (wb-context/first-candidate-at-rate
                (:dl leaf)
                min-compression-rate
                (wb-context/candidate-seq search-context
                                          values
                                          {:threshold-dl threshold-dl
                                           :score-target-count 1}))]
    (if-let [candidate (:candidate search)]
      (let [replacement (render/candidate-tree search-context candidate :target0)]
        {:leaf leaf
         :candidate candidate
         :replacement-tree replacement
         :selected (render/tree-expr replacement)
         :initial-dl (:dl leaf)
         :dl (:dl replacement)
         :compression-rate (alice/compression-rate (:dl leaf)
                                                   (:dl replacement))
         :candidates-consumed (:candidates-consumed search)
         :stop-reason (:stop-reason search)})
      {:leaf leaf
       :candidates-consumed (:candidates-consumed search)
       :stop-reason (:stop-reason search)})))

(defn- first-successful-compression
  [search-context min-compression-rate worthy-dl target-trees]
  (loop [leaves (worthy-leaves target-trees worthy-dl)
         consumed 0
         attempts []]
    (if-let [leaf (first leaves)]
      (let [attempt (compress-leaf search-context
                                   min-compression-rate
                                   target-trees
                                   leaf)
            consumed (+ consumed (:candidates-consumed attempt))
            attempt (assoc attempt :candidates-consumed consumed)]
        (if (:replacement-tree attempt)
          (assoc attempt :attempts attempts)
          (recur (rest leaves)
                 consumed
                 (conj attempts (select-keys attempt
                                             [:leaf
                                              :candidates-consumed
                                              :stop-reason])))))
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
  [{:keys [leaf selected initial-dl dl compression-rate candidates-consumed stop-reason]}]
  {:target-id (:target-id leaf)
   :path (:path leaf)
   :initial-dl initial-dl
   :dl dl
   :compression-rate compression-rate
   :selected selected
   :candidates-consumed candidates-consumed
   :stop-reason stop-reason})

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
        (wb-context/task-search-context task opts)
        value-dl-cache (cache/value-dl-cache cache-context)
        target-trees (mapv #(render/raw-tree value-dl-cache %) targets)
        min-compression-rate (double (or (:min-compression-rate opts) 1.0))
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
                          :worthy-dl worthy-dl})

          :else
          (let [step (first-successful-compression search-context
                                                   min-compression-rate
                                                   worthy-dl
                                                   target-trees)
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
                              :worthy-dl worthy-dl}))))))))

(defn run-greedy-task
  "Run a Python GreedyAlice-shaped task.

  Each iteration compresses the largest worthy raw leaf, accepts the first
  Wunderbaum candidate above `:min-compression-rate`, rewrites that local leaf,
  and repeats until the task threshold is met or no worthy leaf can be improved.
  The operator registry is always injected by the caller.
  "
  [task opts]
  (run-greedy* task opts {:mode :greedy-task}))

(defn run-compression-step
  "Run a Python-style compression step with a minimum compression rate.

  `:min-compression-rate` is a percent. The default `1.0` corresponds to
  Python GreedyAlice's `min_rate=0.01`.
  "
  [task opts]
  (run-greedy* task
               opts
               {:mode :greedy-compression-step
                :stop-at-task-threshold? false
                :max-steps 1}))
