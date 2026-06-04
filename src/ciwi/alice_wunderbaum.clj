(ns ciwi.alice-wunderbaum
  (:require [ciwi.alice :as alice]
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
     (mapv (fn [declaration]
             (assoc declaration :count (get counts (:op declaration) 0)))
           (filter #(available? (:op %)) alice-operator-declarations)))))

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

(defn- selected-targets
  [result target-count]
  (select-keys (:selected result) (target-ids target-count)))

(defn- result-summary
  [task target-count initial-dl candidates]
  (let [best (first (sort-by :dl candidates))
        dl (or (:dl best) initial-dl)
        rate (alice/compression-rate initial-dl dl)
        selected (if best
                   (selected-targets best target-count)
                   (zipmap (target-ids target-count) (:targets task)))]
    {:task-name (:name task)
     :initial-dl initial-dl
     :dl dl
     :compression-rate rate
     :meets-threshold? (>= rate (double (:threshold-rate task)))
     :selected selected
     :best best
     :candidates candidates
     :resource {:candidates-yielded (count candidates)}}))

(defn run-task
  "Run one CIWI Alice task through the straight-port Wunderbaum path.

  This is not the local bounded RewriteOperator mode. The caller must inject the
  operator registry, and may inject explicit `:ops-with-counts`; otherwise the
  Alice declaration table is filtered to the supplied registry.
  "
  ([task opts]
   (let [{:keys [registry ops-with-counts] :as opts} opts
         registry (require-registry registry)
         ops-with-counts (or ops-with-counts
                             (declarations-for-registry registry opts))
         {:keys [targets free-values]} (task-values task)
         all-values (vec (concat targets free-values))
         initial-dl (reduce + 0.0 (map value/desc-len targets))
         wb (wunderbaum/wunderbaum {:registry registry
                                    :ops-with-counts ops-with-counts})
         candidates (vec (wunderbaum/iterate wb all-values opts))]
     (assoc (result-summary task (count targets) initial-dl candidates)
            :operator-count (count ops-with-counts)))))
