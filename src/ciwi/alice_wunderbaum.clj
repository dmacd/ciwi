(ns ciwi.alice-wunderbaum
  (:require [ciwi.alice :as alice]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.value :as value]
            [ciwi.wunderbaum :as wunderbaum]))

(def alice-operator-declarations
  "Explicit CIWI specs for the Python test_alice.py operator basis.

  CIWI operators do not yet carry Python's typed `specs`, so this table is the
  near-term equivalent of Python Operator._raw_specs for Wunderbaum indexing.
  "
  [{:op :brange :input-specs [:int :int] :output-spec :array-int}
   {:op :repeat :input-specs [:int :array-int] :output-spec :array-int}
   {:op :repeat :input-specs [:int :array] :output-spec :array}
   {:op :repeat :input-specs [:int :string] :output-spec :string}

   {:op :add :input-specs [:int :int] :output-spec :int}
   {:op :add :input-specs [:array-int :int] :output-spec :array-int}
   {:op :add :input-specs [:int :array-int] :output-spec :array-int}
   {:op :add :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :add :input-specs [:float :float] :output-spec :float}
   {:op :add :input-specs [:array-float :float] :output-spec :array-float}
   {:op :add :input-specs [:array-float :array-float] :output-spec :array-float}

   {:op :mult :input-specs [:int :int] :output-spec :int}
   {:op :mult :input-specs [:array-int :int] :output-spec :array-int}
   {:op :mult :input-specs [:int :array-int] :output-spec :array-int}
   {:op :mult :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :mult :input-specs [:float :float] :output-spec :float}
   {:op :mult :input-specs [:array-float :float] :output-spec :array-float}
   {:op :mult :input-specs [:array-float :array-float] :output-spec :array-float}

   {:op :negate :input-specs [:int] :output-spec :int}
   {:op :negate :input-specs [:array-int] :output-spec :array-int}
   {:op :negate :input-specs [:float] :output-spec :float}
   {:op :negate :input-specs [:array-float] :output-spec :array-float}

   {:op :concat :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :concat :input-specs [:array :array] :output-spec :array}
   {:op :concat :input-specs [:string :string] :output-spec :string}

   {:op :insert :input-specs [:array-int :int :array-int] :output-spec :array-int}
   {:op :insert :input-specs [:array-int :array-int :array-int] :output-spec :array-int}
   {:op :insert :input-specs [:array-int :array :array] :output-spec :array}

   {:op :cumsum :input-specs [:array-int] :output-spec :array-int}
   {:op :cumsum :input-specs [:array-float] :output-spec :array-float}

   {:op :getitem :input-specs [:array-int :int] :output-spec :int}
   {:op :getitem :input-specs [:array-int :array-int] :output-spec :array-int}
   {:op :getitem :input-specs [:array-int :array-bool] :output-spec :array-int}
   {:op :getitem :input-specs [:array :int] :output-spec :unknown}
   {:op :getitem :input-specs [:array :array-int] :output-spec :array}

   {:op :lessthan :input-specs [:int :int] :output-spec :bool}
   {:op :lessthan :input-specs [:array-int :int] :output-spec :array-bool}
   {:op :lessthan :input-specs [:array-int :array-int] :output-spec :array-bool}
   {:op :equal :input-specs [:int :int] :output-spec :bool}
   {:op :equal :input-specs [:array-int :int] :output-spec :array-bool}
   {:op :equal :input-specs [:array-int :array-int] :output-spec :array-bool}

   {:op :map :input-specs [:operator :array-int] :output-spec :array-int}
   {:op :fix :input-specs [:unknown :operator] :output-spec :operator}])

(defn- require-registry
  [registry]
  (when-not (map? registry)
    (throw (ex-info "Alice Wunderbaum requires an injected operator registry"
                    {:registry registry})))
  registry)

(defn declarations-for-registry
  ([registry]
   (declarations-for-registry registry {}))
  ([registry {:keys [operator-ids counts]
              :or {counts {}}}]
   (let [registry (require-registry registry)
         requested (some-> operator-ids set)
         available? (fn [op-id]
                      (and (contains? registry op-id)
                           (or (nil? requested)
                               (contains? requested op-id))))]
     (let [declarations (filter #(available? (:op %))
                                alice-operator-declarations)
           op-count (count (set (map :op declarations)))
           op-dl (if (pos? op-count)
                   (Math/ceil (value/jelias op-count))
                   0.0)]
       (mapv (fn [declaration]
               (assoc declaration
                      :count (get counts (:op declaration) 0)
                      :dl op-dl))
             declarations)))))

(defn- specified-value
  [x opts]
  (let [v (value/value x opts)]
    (assoc v :spec (or (:spec v)
                       (wunderbaum/infer-spec v)))))

(defn- target-value
  [x]
  (specified-value x {:permeable? false}))

(defn- free-value
  [x]
  (specified-value x {}))

(defn- task-values
  [task]
  {:targets (mapv target-value (:targets task))
   :free-values (mapv free-value (:free-values task))})

(defn- target-ids
  [n]
  (mapv #(keyword (str "target" %)) (range n)))

(defn- task-search-context
  [task opts]
  (let [{:keys [registry ops-with-counts]} opts
        registry (require-registry registry)
        value-dl-cache (or (:value-dl-cache opts) (atom {}))
        opts (assoc opts :value-dl-cache value-dl-cache)
        ops-with-counts (or ops-with-counts
                            (declarations-for-registry registry opts))
        {:keys [targets free-values]} (task-values task)
        all-values (vec (concat targets free-values))
        initial-dl (reduce + 0.0
                           (map #(value/desc-len-cached value-dl-cache %)
                                targets))
        wb (wunderbaum/wunderbaum {:registry registry
                                   :ops-with-counts ops-with-counts})]
    {:opts opts
     :ops-with-counts ops-with-counts
     :target-count (count targets)
     :targets targets
     :free-values free-values
     :all-values all-values
     :initial-dl initial-dl
     :value-dl-cache value-dl-cache
     :wunderbaum wb}))

(defn- candidate-seq
  ([context]
   (candidate-seq context (:all-values context)))
  ([{:keys [wunderbaum opts]} values]
   (wunderbaum/iterate wunderbaum values opts)))

(defn- first-candidate-at-rate
  [initial-dl threshold-rate candidates]
  (loop [remaining candidates
         consumed 0]
    (if-let [candidate (first remaining)]
      (let [consumed (inc consumed)
            rate (alice/compression-rate initial-dl (:dl candidate))]
        (if (>= rate threshold-rate)
          {:candidate (wunderbaum/realize-selected candidate)
           :candidates-consumed consumed
           :stop-reason :threshold-reached
           :compression-rate rate}
          (recur (rest remaining) consumed)))
      {:candidate nil
       :candidates-consumed consumed
       :stop-reason :exhausted
       :compression-rate 0.0})))

(defn- assoc-operator-count
  [result ops-with-counts]
  (assoc result :operator-count (count ops-with-counts)))

(defn- raw-tree
  [value-dl-cache v]
  (let [v (value/value v)]
    {:kind :raw
     :expr (:data v)
     :dl (value/desc-len-cached value-dl-cache v)}))

(defn- choice-tree
  [g value-dl-cache choice]
  (case (:kind choice)
    :raw
    (let [v (get-in g [:nodes (:node-id choice) :value])]
      {:kind :raw
       :expr (:data v)
       :dl (or (:dl choice)
               (value/desc-len-cached value-dl-cache v))})

    :operator
    (let [op-node (graph/node g (:op-id choice))
          operator (:operator op-node)
          children (mapv #(choice-tree g value-dl-cache %)
                         (:child-choices choice))
          op-dl (:dl operator)]
      {:kind :operator
       :op-id (:id operator)
       :op-dl op-dl
       :children children
       :expr (into [(:id operator)] (map :expr children))
       :dl (+ op-dl (reduce + 0.0 (map :dl children)))})))

(defn- candidate-tree
  [{:keys [value-dl-cache]} candidate target-id]
  (let [description (mdl/node-dl (:graph candidate)
                                 target-id
                                 {:value-dl-cache value-dl-cache})]
    (choice-tree (:graph candidate) value-dl-cache (:choice description))))

(defn- refresh-operator-tree
  [tree children]
  (assoc tree
         :children children
         :expr (into [(:op-id tree)] (map :expr children))
         :dl (+ (:op-dl tree) (reduce + 0.0 (map :dl children)))))

(defn- replace-tree
  [tree path replacement]
  (if (empty? path)
    replacement
    (let [idx (first path)
          path (subvec (vec path) 1)
          children (assoc (:children tree)
                          idx
                          (replace-tree (nth (:children tree) idx)
                                        path
                                        replacement))]
      (refresh-operator-tree tree children))))

(defn- target-tree-leaves
  [target-index target-id tree]
  (letfn [(walk [node path]
            (if (= :raw (:kind node))
              [{:target-index target-index
                :target-id target-id
                :path path
                :expr (:expr node)
                :dl (:dl node)}]
              (mapcat (fn [[idx child]]
                        (walk child (conj path idx)))
                      (map-indexed vector (:children node)))))]
    (vec (walk tree []))))

(defn- worthy-leaves
  [target-trees worthy-dl]
  (let [ids (target-ids (count target-trees))]
    (->> (map-indexed vector target-trees)
         (mapcat (fn [[idx tree]]
                   (target-tree-leaves idx (nth ids idx) tree)))
         (filter #(>= (:dl %) worthy-dl))
         (sort-by (juxt #(- (:dl %)) :target-index :path))
         vec)))

(defn- compress-leaf
  [context min-compression-rate leaf]
  (let [values (into [(target-value (:expr leaf))]
                     (:free-values context))
        search (first-candidate-at-rate (:dl leaf)
                                        min-compression-rate
                                        (candidate-seq context values))]
    (if-let [candidate (:candidate search)]
      (let [replacement (candidate-tree context candidate :target0)]
        {:leaf leaf
         :candidate candidate
         :replacement-tree replacement
         :selected (:expr replacement)
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
  [context min-compression-rate worthy-dl target-trees]
  (loop [leaves (worthy-leaves target-trees worthy-dl)
         consumed 0
         attempts []]
    (if-let [leaf (first leaves)]
      (let [attempt (compress-leaf context min-compression-rate leaf)
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
          replace-tree
          (:path leaf)
          replacement-tree))

(defn- target-tree-dl
  [target-trees]
  (reduce + 0.0 (map :dl target-trees)))

(defn- selected-targets
  [target-trees]
  (zipmap (target-ids (count target-trees))
          (map :expr target-trees)))

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
  [task context initial-dl target-trees steps resource]
  (let [dl (target-tree-dl target-trees)
        rate (alice/compression-rate initial-dl dl)]
    (-> {:task-name (:name task)
         :initial-dl initial-dl
         :dl dl
         :compression-rate rate
         :meets-threshold? (>= rate (double (:threshold-rate task)))
         :selected (selected-targets target-trees)
         :steps steps
         :resource resource}
        (assoc-operator-count (:ops-with-counts context)))))

(defn- run-greedy*
  [task opts {:keys [mode stop-at-task-threshold? max-steps]
              :or {stop-at-task-threshold? true}}]
  (let [{:keys [targets initial-dl value-dl-cache] :as context}
        (task-search-context task opts)
        target-trees (mapv #(raw-tree value-dl-cache %) targets)
        min-compression-rate (double (or (:min-compression-rate opts) 1.0))
        worthy-dl (double (or (:worthy-dl opts) 200.0))
        max-steps (or max-steps (:max-steps opts) Long/MAX_VALUE)]
    (loop [target-trees target-trees
           steps []
           consumed 0]
      (let [dl (target-tree-dl target-trees)
            rate (alice/compression-rate initial-dl dl)]
        (cond
          (and stop-at-task-threshold?
               (>= rate (double (:threshold-rate task))))
          (greedy-result task
                         context
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
                         context
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
          (let [step (first-successful-compression context
                                                   min-compression-rate
                                                   worthy-dl
                                                   target-trees)
                consumed (+ consumed (:candidates-consumed step))]
            (if (:replacement-tree step)
              (recur (apply-compression target-trees step)
                     (conj steps (record-step step))
                     consumed)
              (greedy-result task
                             context
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
