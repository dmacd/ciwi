(ns ciwi.wunderbaum-test
  (:require [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.test-helpers :as h]
            [ciwi.value :as value]
            [ciwi.wunderbaum :as sut]
            [ciwi.wunderbaum.declarations :as declarations]
            [ciwi.wunderbaum.tuples :as tuples]
            [clojure.test :refer [deftest is testing]]))

(defn- range-wunderbaum
  []
  (sut/wunderbaum
   {:registry {:brange op/brange}
    :ops-with-counts [{:op :brange
                       :count 0
                       :input-specs [:int :int]
                       :output-spec :array-int}]}))

(defn- constant-int-op
  [id output arg]
  (op/operator
   {:id id
    :conditions [[]]
    :call (fn [_inputs] output)
    :inverse (fn [actual-output _cond-inputs cond]
               (when (and (= output actual-output)
                          (empty? cond))
                 [[arg]]))}))

(defn- delayed-constant-int-op
  [id output arg delay-ms]
  (op/operator
   {:id id
    :conditions [[]]
    :call (fn [_inputs] output)
    :inverse (fn [actual-output _cond-inputs cond]
               (when (and (= output actual-output)
                          (empty? cond))
                 (Thread/sleep delay-ms)
                 [[arg]]))}))

(defn- ordered-commit-wunderbaum
  []
  (let [slow (constant-int-op :slow-best 10 [0])
        fast (constant-int-op :fast-later 10 [1])]
    (sut/wunderbaum
     {:registry {:slow-best slow
                 :fast-later fast}
      :ops-with-counts [{:op :slow-best
                         :count 0
                         :input-specs [:array-int]
                         :output-spec :int
                         :dl 1.0}
                        {:op :fast-later
                         :count 0
                         :input-specs [:array-int]
                         :output-spec :int
                         :dl 2.0}]})))

(defn- cancellation-wunderbaum
  []
  (let [slow (constant-int-op :slow-best 10 [0])
        fast (delayed-constant-int-op :fast-pending 10 [1] 50)
        later (delayed-constant-int-op :cancelled-later 10 [2] 120)]
    (sut/wunderbaum
     {:registry {:slow-best slow
                 :fast-pending fast
                 :cancelled-later later}
      :ops-with-counts [{:op :slow-best
                         :count 0
                         :input-specs [:array-int]
                         :output-spec :int
                         :dl 1.0}
                        {:op :fast-pending
                         :count 0
                         :input-specs [:array-int]
                         :output-spec :int
                         :dl 2.0}
                        {:op :cancelled-later
                         :count 0
                         :input-specs [:array-int]
                         :output-spec :int
                         :dl 3.0}]})))

(def ^:private python-wunderbaum-operator-declarations
  [{:op :map :input-specs [:operator :array-int] :output-spec :array-int :dl 8.0}
   {:op :brange :input-specs [:int :int] :output-spec :array-int :dl 8.0}
   {:op :add :input-specs [:int :int] :output-spec :int :dl 8.0}
   {:op :add :input-specs [:array-int :int] :output-spec :array-int :dl 8.0}
   {:op :add :input-specs [:array-int :array-int] :output-spec :array-int :dl 8.0}
   {:op :mult :input-specs [:int :int] :output-spec :int :dl 8.0}
   {:op :mult :input-specs [:array-int :int] :output-spec :array-int :dl 8.0}
   {:op :mult :input-specs [:array-int :array-int] :output-spec :array-int :dl 8.0}
   {:op :negate :input-specs [:int] :output-spec :int :dl 8.0}
   {:op :negate :input-specs [:array-int] :output-spec :array-int :dl 8.0}
   {:op :concat :input-specs [:array-int :array-int] :output-spec :array-int :dl 8.0}
   {:op :repeat :input-specs [:int :array-int] :output-spec :array-int :dl 8.0}
   {:op :getitem :input-specs [:array-int :int] :output-spec :int :dl 8.0}
   {:op :getitem :input-specs [:array-int :array-int] :output-spec :array-int :dl 8.0}
   {:op :getitem :input-specs [:array-int :array-bool] :output-spec :array-int :dl 8.0}
   {:op :setitem :input-specs [:array-int :array-int :array-int]
    :output-spec :array-int :dl 8.0}])

(def ^:private python-wunderbaum-registry
  (select-keys op/registry
               [:map :brange :add :mult :negate :concat :repeat :getitem :setitem]))

(def ^:private python-wunderbaum-solution
  [:setitem [:repeat 3 [45]] [:negate [-1 -2]] [:negate [-87 -87]]])

(defn- expression-products
  [expression-sets]
  (if (empty? expression-sets)
    '(())
    (for [head (first expression-sets)
          tail (expression-products (rest expression-sets))]
      (cons head tail))))

(defn- option-expressions
  [g id]
  (let [n (graph/node g id)]
    (cond
      (graph/value-node? n)
      (if (seq (:options n))
        (into #{}
              (mapcat #(option-expressions g %))
              (:options n))
        #{(value/plain-datum (graph/value-data g id))})

      (graph/operator-node? n)
      (let [child-expressions (map #(option-expressions g %) (:children n))]
        (into #{}
              (map #(into [(:id (:operator n))] %))
              (expression-products child-expressions)))

      :else #{})))

(deftest wunderbaum-requires-injected-registry
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires an injected operator registry"
       (sut/wunderbaum {:ops-with-counts []}))))

(deftest operator-elements-are-indexed-by-generalized-condition-specs
  (let [elements (declarations/operator-elements-by-condition-key
                  {:brange op/brange}
                  [{:op :brange
                    :count 0
                    :input-specs [:int :int]
                    :output-spec :array-int}])
        output-conditioned (get elements [:array-int])
        forward-conditioned (get elements [:int :int])]
    (is (= #{[:array-int] [:int :int]}
           (set (keys elements))))
    (is (= [[-1]]
           (mapv :gen-cond output-conditioned)))
    (is (= [[0 1]]
           (mapv :gen-cond forward-conditioned)))))

(deftest node-tuples-use-best-first-index-order
  (let [g (-> (graph/empty-graph)
              (graph/add-value :a 1)
              (graph/add-value :b 2)
              (graph/add-value :c 3)
              (graph/set-roots [:a :b :c]))
        tuples (tuples/node-tuples g {:max-tuple-len 2
                                      :max-results 7})]
    (is (= [[0] [0 0] [1] [2] [0 1] [0 2] [1 0]]
           (mapv :indices tuples)))
    (is (= [[:a] [:a :a] [:b] [:c] [:a :b] [:a :c] [:b :a]]
           (mapv :nodes tuples)))))

(deftest wunderbaum-finds-range-by-delayed-output-inversion
  (let [target (value/value [0 1 2 3] {:spec :array-int})
        initial (sut/initial-state [target])
        result (sut/realize-selected
                (first (sut/iterate (range-wunderbaum)
                                    [target]
                                    {:max-popped 8
                                     :max-yields 1})))]
    (is (some? result))
    (is (= [:brange 0 4]
           (get-in result [:selected :target0])))
    (is (< (:dl result)
           (mdl/graph-dl (:graph initial))))))

(deftest wunderbaum-parallel-finds-range-by-delayed-output-inversion
  (let [target (value/value [0 1 2 3] {:spec :array-int})
        result (sut/realize-selected
                (first (sut/iterate-parallel (range-wunderbaum)
                                             [target]
                                             {:parallelism 2
                                              :max-popped 8
                                              :max-yields 1})))]
    (is (some? result))
    (is (= [:brange 0 4]
           (get-in result [:selected :target0])))))

(deftest wunderbaum-global-best-first-finds-range-by-delayed-output-inversion
  (let [target (value/value [0 1 2 3] {:spec :array-int})
        result (sut/realize-selected
                (first (sut/iterate-global-best-first
                        (range-wunderbaum)
                        [target]
                        {:parallelism 2
                         :max-popped 8
                         :max-yields 1})))]
    (is (some? result))
    (is (= [:brange 0 4]
           (get-in result [:selected :target0])))))

(deftest global-best-first-defers-descendant-expansion
  (let [target (value/value [0 1 2 3] {:spec :array-int})
        stats (atom {})
        results (doall (sut/iterate-global-best-first
                        (range-wunderbaum)
                        [target]
                        {:parallelism 2
                         :max-popped 8
                         :threshold-dl 0.0
                         :wunderbaum-stats-atom stats}))]
    (is (empty? results))
    (is (pos? (:deferred-expansions @stats)))
    (is (pos? (:expansion-tasks-popped @stats)))))

(deftest global-best-first-uses-ordered-commit-when-later-worker-finishes-first
  (let [target (value/value 10 {:spec :int})
        stats (atom {})
        result (sut/realize-selected
                (first (sut/iterate-global-best-first
                        (ordered-commit-wunderbaum)
                        [target]
                        {:parallelism 2
                         :frontier-batch-size 1
                         :max-popped 8
                         :max-yields 1
                         :wunderbaum-stats-atom stats
                         :candidate-transform
                         (fn [summary]
                           (when (< (:build-dl summary) 1.5)
                             (Thread/sleep 75))
                           summary)})))]
    (is (some? result))
    (is (= [:slow-best [0]]
           (get-in result [:selected :target0])))
    (is (= :global-best-first (:strategy @stats)))
    (is (pos? (:commit-wait-ns @stats)))))

(deftest global-best-first-cancels-later-ranked-active-work
  (let [target (value/value 10 {:spec :int})
        stats (atom {})
        result (sut/realize-selected
                (first (sut/iterate-global-best-first
                        (cancellation-wunderbaum)
                        [target]
                        {:parallelism 3
                         :frontier-batch-size 1
                         :max-popped 8
                         :max-yields 1
                         :wunderbaum-stats-atom stats
                         :candidate-transform
                         (fn [summary]
                           (when (< (:build-dl summary) 1.5)
                             (Thread/sleep 180))
                           summary)})))
        cancelled (+ (long (or (:cancelled-items @stats) 0))
                     (long (or (:cancelled-results @stats) 0)))]
    (is (some? result))
    (is (= [:slow-best [0]]
           (get-in result [:selected :target0])))
    (is (pos? cancelled))))

(deftest python-wunderbaum-parallel-drains-bounded-prefix
  (let [wb (sut/wunderbaum
            {:registry python-wunderbaum-registry
             :ops-with-counts python-wunderbaum-operator-declarations})
        targets [(value/value [45 87 87]
                              {:spec :array-int
                               :permeable? false
                               :name "target"})
                 (value/value [45]
                              {:spec :array-int
                               :permeable? false
                               :name "target2"})
                 (value/value 3
                              {:spec :int
                               :name "free"})]
        results (doall (sut/iterate-parallel wb
                                             targets
                                             {:parallelism 2
                                              :max-popped 1000
                                              :threshold-dl 0.0
                                              :max-yields 1}))]
    (is (empty? results))))

(deftest python-wunderbaum-finds-setitem-repeat-negate-solution
  (let [wb (sut/wunderbaum
            {:registry python-wunderbaum-registry
             :ops-with-counts python-wunderbaum-operator-declarations})
        targets [(value/value [45 87 87]
                              {:spec :array-int
                               :permeable? false
                               :name "target"})
                 (value/value [45]
                              {:spec :array-int
                               :permeable? false
                               :name "target2"})
                 (value/value 3
                              {:spec :int
                               :name "free"})]
        result (some (fn [[idx candidate]]
                       (let [expressions (option-expressions (:graph candidate) :target0)]
                         (when (contains? expressions python-wunderbaum-solution)
                           (assoc candidate
                                  :candidate-index idx
                                  :expressions expressions))))
                     (map-indexed vector
                                  (sut/iterate wb
                                               targets
                                               {:max-popped 50000
                                                :max-yields 9500
                                                :max-node-tuples 1000})))]
    (is (some? result))
    (when result
      (is (contains? (:expressions result) python-wunderbaum-solution))
      (is (= [45 87 87]
             (h/plain-missing (graph/value-data (:graph result) :target0))))
      (is (< (:candidate-index result) 9500)))))

(deftest wunderbaum-uses-multiple-conditioned-nodes-for-inversion
  (let [wb (sut/wunderbaum
            {:registry {:add op/add}
             :ops-with-counts [{:op :add
                                :count 0
                                :input-specs [:array-int :int]
                                :output-spec :array-int}]})
        result (some (fn [candidate]
                       (let [candidate (sut/realize-selected candidate)
                             expr (get-in candidate [:selected :target0])]
                         (when (and (vector? expr)
                                    (= :add (first expr)))
                           candidate)))
                     (sut/iterate wb
                                  [(value/value [1000000 1000001 1000002]
                                                {:spec :array-int})
                                   (value/value 1000000 {:spec :int})]
                                  {:max-popped 32
                                   :max-yields 8}))
        expr (get-in result [:selected :target0])]
    (is (some? result))
    (is (= :add (first expr)))
    (is (= [[0 1 2] 1000000]
           (rest expr)))
    (is (= 1000000
           (get-in result [:selected :target1])))))

(deftest wunderbaum-uses-injected-registry-not-global-registry
  (let [quad (op/operator
              {:id :quad
               :conditions [[]]
               :call (fn [[x]]
                       [x x x x])
               :inverse (fn [output _cond-inputs cond]
                          (let [values (clojure.core/cond
                                         (dense/ndarray? output) (dense/ravel output)
                                         (vector? output) output
                                         :else nil)]
                            (when (and (empty? cond)
                                       (seq values)
                                       (apply = values))
                              [[(first values)]])))})
        wb (sut/wunderbaum
            {:registry {:quad quad}
             :ops-with-counts [{:op :quad
                                :count 0
                                :input-specs [:int]
                                :output-spec :array-int}]})
        result (sut/realize-selected
                (first (sut/iterate wb
                                    [(value/value [7 7 7 7] {:spec :array-int})]
                                    {:max-popped 8
                                     :max-yields 1})))]
    (is (= [:quad 7]
           (get-in result [:selected :target0])))
    (is (nil? (get op/registry :quad)))))
