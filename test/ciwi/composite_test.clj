(ns ciwi.composite-test
  (:require [ciwi.composite :as sut]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]))

(defn- data-results
  [results]
  (mapv #(mapv value/datum %) results))

(defn- fixture-op
  ([id conditions call inverse]
   (fixture-op id conditions call inverse false))
  ([id conditions call inverse commutative?]
   (op/operator
    {:id id
     :conditions conditions
     :commutative? commutative?
     :call call
     :inverse inverse})))

(defn- structural-op
  [id conditions commutative?]
  (fixture-op id conditions (constantly nil) (constantly nil) commutative?))

(defn- abs-call
  [x]
  (when-not (number? x)
    (throw (ex-info "abs expects a scalar number" {:x x})))
  (if (neg? x) (- x) x))

(defn- abs-inverse
  [output _cond-inputs cond]
  (when (and (empty? cond)
             (number? output)
             (not (neg? output)))
    (cond-> [[output]]
      (pos? output) (conj [(- output)]))))

(def ^:private abs-op
  (fixture-op :abs
              [[]]
              (fn [[x]]
                (abs-call x))
              abs-inverse
              true))

(defn- toint-scalar
  [x]
  (cond
    (true? x) 1
    (false? x) 0
    (integer? x) x
    (number? x) (long x)
    (string? x) (Long/parseLong x)
    :else (throw (ex-info "toint expects a scalar bool/number/string" {:x x}))))

(defn- tofloat-scalar
  [x]
  (cond
    (true? x) 1.0
    (false? x) 0.0
    (number? x) (double x)
    (string? x) (Double/parseDouble x)
    :else (throw (ex-info "tofloat expects a scalar bool/number/string" {:x x}))))

(defn- whole-or-elementwise
  [f x]
  (if (and (sequential? x) (not (string? x)))
    (mapv f x)
    (f x)))

(defn- int-like?
  [x]
  (and (number? x) (== x (long x))))

(defn- bool-code?
  [x]
  (or (= x 0) (= x 1) (= x 0.0) (= x 1.0)))

(defn- conversion-inverse
  [output target]
  (let [convert-branch (fn [f]
                         (if (and (sequential? output) (not (string? output)))
                           (mapv f output)
                           (f output)))
        bool-branch (fn []
                      (when (if (and (sequential? output) (not (string? output)))
                              (every? bool-code? output)
                              (bool-code? output))
                        [(convert-branch #(= % 1))]))]
    (case target
      :int
      (when (if (and (sequential? output) (not (string? output)))
              (every? integer? output)
              (integer? output))
        (cond-> [[(convert-branch double)]
                 [(convert-branch str)]]
          (bool-branch) (conj (bool-branch))))

      :float
      (when (if (and (sequential? output) (not (string? output)))
              (every? number? output)
              (number? output))
        (cond-> []
          (if (and (sequential? output) (not (string? output)))
            (every? int-like? output)
            (int-like? output))
          (conj [(convert-branch long)])

          true
          (conj [(convert-branch #(str (double %)))])

          (bool-branch)
          (conj (bool-branch)))))))

(def ^:private toint-op
  (fixture-op :toint
              [[]]
              (fn [[x]]
                (whole-or-elementwise toint-scalar x))
              (fn [output _cond-inputs cond]
                (when (empty? cond)
                  (conversion-inverse output :int)))))

(def ^:private tofloat-op
  (fixture-op :tofloat
              [[]]
              (fn [[x]]
                (whole-or-elementwise tofloat-scalar x))
              (fn [output _cond-inputs cond]
                (when (empty? cond)
                  (conversion-inverse output :float)))))

(def ^:private listwrap-op
  (fixture-op :listwrap
              [[]]
              (fn [[x]]
                [x])
              (fn [output _cond-inputs cond]
                (when (and (empty? cond)
                           (sequential? output)
                           (= 1 (count output)))
                  [[(first output)]]))
              true))

(def ^:private urange-op
  (fixture-op :urange
              [[]]
              (fn [[stop]]
                (vec (range stop)))
              (fn [output _cond-inputs cond]
                (when (and (empty? cond)
                           (vector? output)
                           (seq output)
                           (every? integer? output)
                           (= output (vec (range (inc (peek output))))))
                  [[(inc (peek output))]]))
              true))

(defn- boolish-vector?
  [x]
  (and (vector? x)
       (every? #(or (true? %) (false? %)) x)))

(def ^:private bool-not-op
  (fixture-op :not
              [[]]
              (fn [[x]]
                (cond
                  (or (true? x) (false? x)) (not x)
                  (boolish-vector? x) (mapv not x)
                  :else (throw (ex-info "not expects bool or bool vector" {:x x}))))
              (fn [output _cond-inputs cond]
                (when (empty? cond)
                  (clojure.core/cond
                    (or (true? output) (false? output)) [[(not output)]]
                    (boolish-vector? output) [[(mapv not output)]])))
              true))

(def ^:private div-op
  (fixture-op :div
              [[0] [1]]
              (fn [[x y]]
                (cond
                  (and (vector? x) (vector? y) (= (count x) (count y)))
                  (mapv / x y)

                  (vector? x)
                  (mapv #(/ % y) x)

                  (vector? y)
                  (mapv #(/ x %) y)

                  :else
                  (/ x y)))
              (fn [output cond-inputs cond]
                (when (and (number? output)
                           (not (zero? output))
                           (= 1 (count cond)))
                  (let [known (first cond-inputs)]
                    (case (first cond)
                      0 [[(/ known output)]]
                      1 [[(* output known)]]
                      nil))))))

(defn- callable-fixture-op
  [f]
  (cond
    (op/operator? f) f
    (keyword? f) (get op/registry f)
    :else nil))

(defn- transpose
  [rows]
  (when (seq rows)
    (apply mapv vector rows)))

(defn- bmap-inverse
  [output cond-inputs cond]
  (let [cond (vec cond)]
    (when (and (seq cond)
               (= 0 (first cond)))
      (let [f (callable-fixture-op (first cond-inputs))
            known-children (subvec cond 1)
            known-values (subvec (vec cond-inputs) 1)
            func-cond (mapv dec known-children)]
        (when (and f
                   (contains? (set (:conditions f)) func-cond)
                   (vector? output)
                   (every? vector? known-values)
                   (every? #(= (count output) (count %)) known-values))
          (let [inversions
                (loop [idx 0
                       total-branches 1
                       result []]
                  (if (= idx (count output))
                    result
                    (let [func-cond-inputs (mapv #(nth % idx) known-values)
                          invs (mapv (fn [values]
                                       (mapv value/datum values))
                                     (op/invert-op f
                                                   (value/value (nth output idx))
                                                   (mapv value/value func-cond-inputs)
                                                   func-cond))
                          total-branches (* total-branches (count invs))]
                      (when (and (seq invs)
                                 (<= total-branches 100))
                        (recur (inc idx) total-branches (conj result invs))))))]
            (when (seq inversions)
              (let [branch-count (apply min (map count inversions))]
                (mapv (fn [branch-idx]
                        (mapv vec
                              (transpose
                               (mapv #(nth % branch-idx) inversions))))
                      (range branch-count))))))))))

(def ^:private bmap-op
  (fixture-op :bmap
              [[0] [0 1] [0 2]]
              (fn [[f xs ys]]
                (when-let [f (callable-fixture-op f)]
                  (when (= (count xs) (count ys))
                    (mapv (fn [x y]
                            (value/datum (op/apply-op f [(value/value x)
                                                         (value/value y)])))
                          xs
                          ys))))
              bmap-inverse))

(def ^:private cumop-op
  (fixture-op :cumop
              [[0]]
              (fn [[f start xs]]
                (when-let [f (callable-fixture-op f)]
                  (loop [remaining xs
                         current start
                         result [start]]
                    (if-let [remaining (seq remaining)]
                      (let [current (value/datum (op/apply-op f [(value/value current)
                                                                 (value/value (first remaining))]))]
                        (recur (next remaining) current (conj result current)))
                      result))))
              (fn [output cond-inputs cond]
                (when (and (= [0] (vec cond))
                           (vector? output)
                           (seq output))
                  (when-let [f (callable-fixture-op (first cond-inputs))]
                    (when (contains? (set (:conditions f)) [0])
                      (let [inversions
                            (loop [pairs (partition 2 1 output)
                                   result []]
                              (if-let [[previous current] (first pairs)]
                                (let [invs (mapv (fn [values]
                                                   (mapv value/datum values))
                                                 (op/invert-op f
                                                               (value/value current)
                                                               [(value/value previous)]
                                                               [0]))]
                                  (when (seq invs)
                                    (recur (next pairs) (conj result invs))))
                                result))]
                        (when (seq inversions)
                          (let [branch-count (apply min (map count inversions))]
                            (mapv (fn [branch-idx]
                                    [(first output)
                                     (mapv first
                                           (mapv #(nth % branch-idx) inversions))])
                                  (range branch-count)))))))))))

(def ^:private table-op
  (fixture-op :table
              [[]]
              (fn [[values positions]]
                (mapv values positions))
              (fn [output _cond-inputs cond]
                (when (and (empty? cond)
                           (vector? output)
                           (seq output))
                  (let [values (vec (sort (set output)))
                        position (zipmap values (range))]
                    [[values (mapv position output)]])))))

(defn- missing-like
  [x]
  (if (string? x) "" nil))

(defn- dec-composite-inverse
  [output cond-inputs cond]
  (when (and (= [1] (vec cond))
             (vector? output)
             (boolish-vector? (first cond-inputs))
             (= (count output) (count (first cond-inputs))))
    (let [mask (first cond-inputs)]
      [[(mapv (fn [selected? x]
                (if selected? (missing-like x) x))
              mask
              output)
        (mapv (fn [selected? x]
                (if selected? x (missing-like x)))
              mask
              output)]])))

(def ^:private dec-op
  (fixture-op :dec
              [[1]]
              (fn [[base mask xs]]
                (value/datum
                 (op/apply-op op/setitem
                              [(value/value base)
                               (value/value mask)
                               (op/apply-op op/getitem
                                            [(value/value xs)
                                             (value/value mask)])])))
              dec-composite-inverse))

(defn- as-set
  [x]
  (if (set? x)
    x
    (set x)))

(def ^:private zip2d-op
  (fixture-op :zip2d
              [[]]
              (fn [[xs ys]]
                (mapv vector xs ys))
              (constantly nil)))

(def ^:private union-op
  (fixture-op :union
              [[0] [1]]
              (fn [[left right]]
                (set/union (as-set left) (as-set right)))
              (fn [output cond-inputs cond]
                (when (= 1 (count cond))
                  [[(set/difference (as-set output)
                                    (as-set (first cond-inputs)))]]))
              false))

(def ^:private python-composite-fixture-registry
  (merge op/registry
         {:abs abs-op
          :bmap bmap-op
          :cumop cumop-op
          :dec dec-op
          :div div-op
          :listwrap listwrap-op
          :listslice (structural-op :listslice [[0 1 2]] false)
          :not bool-not-op
          :sum (structural-op :sum [] true)
          :table table-op
          :tofloat tofloat-op
          :toint toint-op
          :union union-op
          :urange urange-op
          :zip2d zip2d-op}))

(def ^:private native-composite-spec-declarations
  [{:op :add :input-specs [:int :int] :output-spec :int}
   {:op :mult :input-specs [:int :int] :output-spec :int}
   {:op :brange :input-specs [:int :int] :output-spec :array-int}
   {:op :repeat :input-specs [:int :array] :output-spec :array}
   {:op :repeat :input-specs [:int :array-int] :output-spec :array-int}
   {:op :zip2d :input-specs [:array-int :array] :output-spec :array}
   {:op :getitem :input-specs [:array-float :array-bool] :output-spec :array-float}
   {:op :getitem :input-specs [:array-float :array-int] :output-spec :array-float}
   {:op :setitem :input-specs [:array-float :array-bool :array-float]
    :output-spec :array-float}
   {:op :setitem :input-specs [:array-float :array-int :array-float]
    :output-spec :array-float}])

(defn- shared-fixture-spec
  [expr]
  (letfn [(next-id [state prefix]
            (let [counter (inc (:counter state))]
              [(assoc state :counter counter)
               (keyword (str (name prefix) counter))]))
            (operator-for [head]
              (get python-composite-fixture-registry head))
            (remember-input [state g input-id sample]
              (if-let [node-id (get-in state [:input-nodes input-id])]
                [state g node-id]
                (let [[state node-id] (next-id state :v)]
                  [(-> state
                       (update :input-order conj input-id)
                       (assoc-in [:input-nodes input-id] node-id))
                   (graph/add-value g node-id sample)
                   node-id])))
            (build [state g form]
              (cond
                (and (vector? form) (= :input (first form)))
                (let [[_ input-id sample] form]
                  (remember-input state g input-id sample))

                (and (vector? form) (operator-for (first form)))
                (let [operator (operator-for (first form))
                      [state g child-ids]
                      (reduce (fn [[state acc ids] child]
                                (let [[state acc child-id] (build state acc child)]
                                  [state acc (conj ids child-id)]))
                              [state g []]
                              (rest form))
                      [state parent-id] (next-id state :v)
                      [state op-id] (next-id state :op)]
                  [state
                   (-> g
                       (graph/add-value parent-id nil)
                       (graph/add-operator op-id operator parent-id child-ids))
                   parent-id])

                :else
                (let [[state node-id] (next-id state :v)]
                  [state (graph/add-value g node-id form) node-id])))]
    (let [[state g root-id] (build {:counter 0
                                    :input-order []
                                    :input-nodes {}}
                                   (graph/empty-graph)
                                   expr)
          g (graph/set-roots g [root-id])
          leaf-position (zipmap (graph/leaves g root-id) (range))]
      {:graph g
       :root root-id
       :input-groups (mapv (fn [input-id]
                             [(leaf-position (get-in state
                                                     [:input-nodes input-id]))])
                           (:input-order state))})))

(defn- fixture-composite
  [id expr]
  (sut/operator id (shared-fixture-spec expr)))

(deftest composite-operator-calls-through-graph-propagation
  (let [cop (sut/operator :mul-plus [:add [:mult 0 1] 2])]
    (is (= [[0 1] [0 2] [1 2]]
           (:conditions cop)))
    (is (= 37
           (value/datum (op/apply-op cop [(value/value 5)
                                          (value/value 7)
                                          (value/value 2)]))))
    (is (= [[2]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 5) (value/value 7)]
                                       [0 1]))))))

(deftest composite-operator-captures-constant-leaves
  (let [cop (sut/operator :mul-plus-two
                          [:add [:mult 0 1] 2]
                          {:constant-indices #{2}})]
    (is (= [[0] [1]]
           (:conditions cop)))
    (is (= 37
           (value/datum (op/apply-op cop [(value/value 5)
                                          (value/value 7)]))))
    (is (= [[7]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 5)]
                                       [0]))))
    (is (= [[5]]
           (data-results (op/invert-op cop
                                       (value/value 37)
                                       [(value/value 7)]
                                       [1]))))))


(deftest composite-template-inputs-can-share-graph-leaves
  (let [square (sut/operator :square
                             [:mult [:input :x 2]
                              [:input :x 2]])
        square-plus-y (sut/operator :square-plus-y
                                    [:add [:mult [:input :x 2]
                                           [:input :x 2]]
                                     [:input :y 3]])]
    (is (= 25
           (value/datum (op/apply-op square [(value/value 5)]))))
    (is (= 32
           (value/datum (op/apply-op square-plus-y [(value/value 5)
                                                   (value/value 7)]))))
    (is (= [[0]]
           (:conditions square-plus-y)))
    (is (= [[7]]
           (data-results (op/invert-op square-plus-y
                                       (value/value 32)
                                       [(value/value 5)]
                                       [0]))))))

(deftest composite-template-literals-are-captured-as-constants
  (let [inc-op (sut/operator :increment [:add [:input :x 0] 1])]
    (is (= [[]]
           (:conditions inc-op)))
    (is (= 42
           (value/datum (op/apply-op inc-op [(value/value 41)]))))
    (is (= [[41]]
           (data-results (op/invert-op inc-op
                                       (value/value 42)
                                       []
                                       []))))))


(deftest dag-shaped-composites-match-python-execution-cases
  (let [dag0 (sut/operator :dag0
                           [:mult [:input :x 2]
                            [:input :x 2]])
        dag1 (sut/operator :dag1
                           [:add [:mult [:input :x 2]
                                  [:input :x 2]]
                            [:input :x 2]])
        dag2 (sut/operator :dag2
                           [:add [:mult [:input :x 2]
                                  [:input :x 2]]
                            [:input :y 3]])
        dag3 (sut/operator :dag3
                           [:add [:mult [:input :x 2]
                                  [:input :y 3]]
                            [:mult [:input :x 2]
                             [:input :y 3]]])]
    (is (= 225
           (value/datum (op/apply-op dag0 [(value/value 15)]))))
    (is (= 30
           (value/datum (op/apply-op dag1 [(value/value 5)]))))
    (is (= 32
           (value/datum (op/apply-op dag2 [(value/value 5)
                                           (value/value 7)]))))
    (is (= 70
           (value/datum (op/apply-op dag3 [(value/value 5)
                                           (value/value 7)]))))
    (is (= [[7]]
           (data-results (op/invert-op dag2
                                       (value/value 32)
                                       [(value/value 5)]
                                       [0]))))))

(deftest python-dag-composite-execution-golden-cases
  (let [dag4 (fixture-composite
              :dag4
              [:zip2d [:brange [:input :x0 3]
                       [:add [:input :x0 3]
                        [:input :length 4]]]
               [:repeat [:input :length 4]
                [:input :y [9]]]])
        dag5 (fixture-composite
              :dag5
              [:union [:zip2d [:brange [:input :x0 3]
                               [:add [:input :x0 3]
                                [:input :length 4]]]
                       [:repeat [:input :length 4]
                        [:input :y [9]]]]
               [:input :extra #{[9 8]}]])]
    (is (= [[1]]
           (:conditions dag4)))
    (is (= [[1]]
           (:conditions dag5)))
    (is (= [[3 9] [4 9] [5 9] [6 9]]
           (value/datum (op/apply-op dag4
                                     [(value/value 3)
                                      (value/value 4)
                                      (value/value [9])]))))
    (is (= #{[3 9] [4 9] [5 9] [6 9] [9 8]}
           (value/datum (op/apply-op dag5
                                     [(value/value 3)
                                      (value/value 4)
                                      (value/value [9])
                                      (value/value #{[9 8]})]))))
    (is (= [[#{[9 8]}]]
           (data-results (op/invert-op dag5
                                       (value/value #{[3 9] [4 9] [5 9]
                                                      [6 9] [9 8]})
                                       [(value/value 3)
                                        (value/value 4)
                                        (value/value [9])]
                                       [0 1 2]))))))

(deftest native-composite-spec-synchronization-golden-cases
  (let [dag4-spec (shared-fixture-spec
                   [:zip2d [:brange [:input :x0 3]
                            [:add [:input :x0 3]
                             [:input :length 4]]]
                    [:repeat [:input :length 4]
                     [:input :y [9]]]])
        dec-spec (shared-fixture-spec
                  [:setitem [:input :base nil]
                   [:input :mask nil]
                   [:getitem [:input :xs nil]
                    [:input :mask nil]]])
        square-plus-y [:add [:mult [:input :x 2]
                             [:input :x 2]]
                       [:input :y 3]]]
    (is (= #{{:input-specs [:int :int :array]
              :output-spec :array}
             {:input-specs [:int :int :array-int]
              :output-spec :array}}
           (set (sut/composite-specs dag4-spec
                                     native-composite-spec-declarations))))
    (is (= #{{:input-specs [:array-float :array-bool :array-float]
              :output-spec :array-float}
             {:input-specs [:array-float :array-int :array-float]
              :output-spec :array-float}}
           (set (sut/composite-specs dec-spec
                                     native-composite-spec-declarations
                                     {:fixed-output-spec :array-float}))))
    (is (= #{{:input-specs [:int :int]
              :output-spec :int}}
           (set (sut/composite-specs square-plus-y
                                     native-composite-spec-declarations
                                     {:fixed-output-spec :int}))))
    (is (empty? (sut/composite-specs dec-spec
                                     native-composite-spec-declarations
                                     {:fixed-output-spec :array-int})))))

(deftest composite-commutativity-is-inferred-symbolically
  (let [plus (sut/operator :plus [:add [:input :x 0] [:input :y 0]])
        times (sut/operator :times [:mult [:input :x 0] [:input :y 0]])
        minus (sut/operator :minus [:sub [:input :x 0] [:input :y 0]])
        square-plus-y (sut/operator :square-plus-y
                                    [:add [:mult [:input :x 0]
                                           [:input :x 0]]
                                     [:input :y 0]])]
    (is (:commutative? plus))
    (is (:commutative? times))
    (is (not (:commutative? minus)))
    (is (not (:commutative? square-plus-y)))))

(def ^:private python-commutativity-fixtures
  [{:name "co0"
    :expr [:insert [:input :indices [1]]
           [:input :content 2]
           [:input :rest [3 4]]]
    :commutes? false}
   {:name "co1"
    :expr [:add [:mult [:input :a 2]
                 [:input :b 3]]
           [:input :c 5]]
    :commutes? true}
   {:name "co2"
    :expr [:insert [:trange [:input :idx-start 1]
                    [:input :idx-stop 7]
                    [:input :idx-step 2]]
           [:trange [:input :content-start 3]
            [:input :content-stop 12]
            [:input :content-step 3]]
           [:trange [:input :rest-start 15]
            [:input :rest-stop 23]
            [:input :rest-step 2]]]
    :commutes? false}
   {:name "co3"
    :expr [:sub [:mult [:input :a 3]
                 [:input :b 4]]
           [:add [:input :c 5]
            [:negate [:input :d 2]]]]
    :commutes? false}
   {:name "co4"
    :expr [:add [:negate [:input :x 3]]
           [:sub [:input :y 12]
            [:input :z 5]]]
    :commutes? false}
   {:name "co5"
    :expr [:add [:mult [:input :a nil]
                 [:input :b nil]]
           [:insert [:input :indices nil]
            [:input :content nil]
            [:input :rest nil]]]
    :commutes? false}
   {:name "co6"
    :expr [:repeat [:sub [:add [:input :a nil]
                          [:input :b nil]]
                    [:input :c nil]]
           [:repeat [:input :n nil]
            [:map [:input :f-neg op/negate]
             [:input :xs nil]]]]
    :commutes? false}
   {:name "co7"
    :expr [:abs [:add [:abs [:input :x nil]]
            [:input :y nil]]]
    :commutes? true}
   {:name "co8"
    :expr [:bmap [:input :f-add op/add]
           [:input :x nil]
           [:insert [:input :indices nil]
            [:input :content nil]
            [:input :rest nil]]]
    :commutes? false}
   {:name "co9"
    :expr [:union [:zip2d [:brange [:input :start nil]
                           [:input :stop nil]]
                   [:repeat [:input :n nil]
                    [:input :motif nil]]]
           [:input :extra nil]]
    :commutes? false}
   {:name "co10"
    :expr [:insert [:urange [:input :indices nil]]
           [:urange [:input :content nil]]
           [:urange [:input :rest nil]]]
    :commutes? false}
   {:name "co11"
    :expr [:insert [:trange [:input :idx-start nil]
                    [:input :idx-stop nil]
                    [:input :idx-step nil]]
           [:repeat [:input :n nil]
            [:listwrap [:input :wrapped nil]]]
           [:input :rest nil]]
    :commutes? false}
   {:name "co12"
    :expr [:repeat [:input :n nil]
           [:input :motif nil]]
    :commutes? false}
   {:name "co13"
    :expr [:repeat [:sub [:add [:input :a nil]
                          [:input :b nil]]
                    [:input :c nil]]
           [:repeat [:input :n nil]
            [:map [:input :f-neg op/negate]
             [:input :xs nil]]]]
    :commutes? false}
   {:name "co14"
    :expr [:repeat [:input :n nil]
           [:listslice [:input :xs nil]
            [:input :start nil]
            [:input :stop nil]]]
    :commutes? false}
   {:name "co15"
    :expr [:add [:input :a nil]
           [:input :b nil]]
    :commutes? true}
   {:name "co16"
    :expr [:sum [:urange [:input :n nil]]]
    :commutes? true}
   {:name "co17"
    :expr [:repeat [:input :n nil]
           [:listslice [:input :xs nil]
            [:add [:input :start-a nil]
             [:input :start-b nil]]
            [:sub [:input :stop-a nil]
             [:input :stop-b nil]]
            [:input :step nil]]]
    :commutes? false}
   {:name "co18"
    :expr [:bmap [:input :f-add op/add]
           [:input :x nil]
           [:input :y nil]]
    :commutes? true}
   {:name "co19"
    :expr [:map [:input :f-neg op/negate]
           [:input :xs nil]]
    :commutes? true}
   {:name "co20"
    :expr [:bmap [:input :f-repeat op/repeat]
           [:input :x nil]
           [:input :y nil]]
    :commutes? false}
   {:name "co21"
    :expr [:map [:input :f-neg op/negate]
           [:urange [:input :n nil]]]
    :commutes? true}])

(deftest python-composite-graph-commutativity-golden-cases
  (doseq [{:keys [name expr commutes?]} python-commutativity-fixtures]
    (let [{:keys [graph root]} (shared-fixture-spec expr)]
      (is (= commutes? (graph/commutes? graph root))
          name))))


(deftest composite-inverts-nested-arithmetic-with-captured-constants
  (let [offset-product (sut/operator :offset-product
                                     [:add [:mult [:input :x 2]
                                            [:input :y 3]]
                                      5])
        sub-chain (sut/operator :sub-chain
                                [:sub [:add [:input :x 0]
                                       [:input :y 0]]
                                 [:input :z 0]])]
    (is (= [[6]]
           (data-results (op/invert-op offset-product
                                       (value/value 35)
                                       [(value/value 5)]
                                       [0]))))
    (is (= [[7]]
           (data-results (op/invert-op sub-chain
                                       (value/value 10)
                                       [(value/value 4) (value/value 1)]
                                       [0 2]))))))

(deftest composite-inverts-through-negated-intermediate-values
  (let [neg-shift (sut/operator :neg-shift
                                [:add [:negate [:input :x 0]]
                                 [:input :y 0]])]
    (is (= [[-10]]
           (data-results (op/invert-op neg-shift
                                       (value/value 13)
                                       [(value/value 3)]
                                       [1]))))
    (is (= [[10]]
           (data-results (op/invert-op neg-shift
                                       (value/value 13)
                                       [(value/value -3)]
                                       [0]))))))

(deftest python-composite-inverse-golden-cases
  (let [co2 (fixture-composite
             :co2
             [:insert [:trange [:input :idx-start 1]
                       [:input :idx-stop 7]
                       [:input :idx-step 2]]
              [:trange [:input :content-start 23]
               [:input :content-stop 32]
               [:input :content-step 3]]
              [:trange [:input :rest-start 15]
               [:input :rest-stop 23]
               [:input :rest-step 2]]])
        co3 (fixture-composite
             :co3
             [:sub [:mult [:input :a 3]
                    [:input :b 4]]
              [:add [:input :c 5]
               [:negate [:input :d 2]]]])
        co4 (fixture-composite
             :co4
             [:add [:negate [:input :x 3]]
              [:sub [:input :y 12]
               [:input :z 5]]])]
    (is (= [[23 32 3 15 23 2]]
           (data-results (op/invert-op co2
                                       (value/value [15 23 17 26 19 29 21])
                                       [(value/value 1)
                                        (value/value 7)
                                        (value/value 2)]
                                       [0 1 2]))))
    (is (= [[1 7 2 15 23 2]]
           (data-results (op/invert-op co2
                                       (value/value [15 23 17 26 19 29 21])
                                       [(value/value 23)
                                        (value/value 32)
                                        (value/value 3)]
                                       [3 4 5]))))
    (is (= [[13 33 10 15 10 -1]]
           (data-results (op/invert-op co2
                                       (value/value [15 13 14 13 12 23 11])
                                       [(value/value 1)
                                        (value/value 9)
                                        (value/value 4)]
                                       [0 1 2]))))
    (is (= [[2]]
           (data-results (op/invert-op co3
                                       (value/value 9)
                                       [(value/value 3)
                                        (value/value 4)
                                        (value/value 5)]
                                       [0 1 2]))))
    (is (= [[-6]]
           (data-results (op/invert-op co4
                                       (value/value 13)
                                       [(value/value 12)
                                        (value/value 5)]
                                       [1 2]))))
    (is (= [[25]]
           (data-results (op/invert-op co4
                                       (value/value 15)
                                       [(value/value 3)
                                        (value/value 43)]
                                       [0 1]))))))

(deftest python-composite-sequence-edit-inverse-golden-cases
  (let [co0 (fixture-composite
             :co0
             [:insert [:input :indices [1]]
              [:input :content 2]
              [:input :rest [3 4]]])
        co12 (fixture-composite
              :co12
              [:repeat [:input :n nil]
               [:input :motif nil]])]
    (is (= [[(vec (range 7)) [7 8]]]
           (data-results (op/invert-op co0
                                       (value/value (vec (range 9)))
                                       [(value/value (vec (range 7)))]
                                       [0]))))
    (is (= [[8 [132 1542]]]
           (data-results (op/invert-op co12
                                       (value/value (vec (take 16
                                                               (cycle [132 1542]))))
                                       []
                                       []))))))

(deftest python-composite-callable-and-abs-inverse-golden-cases
  (let [co7 (fixture-composite
             :co7
             [:abs [:add [:abs [:input :x nil]]
                    [:input :y nil]]])
        map-abs (fixture-composite
                 :trees13
                 [:map abs-op
                  [:input :xs nil]])]
    (is (= [[9] [-9]]
           (data-results (op/invert-op co7
                                       (value/value 12)
                                       [(value/value 3)]
                                       [1]))))
    (is (= [[[1 0 0]] [[-1 0 0]]]
           (data-results (op/invert-op map-abs
                                       (value/value [1 0 0])
                                       []
                                       []))))
    (is (empty? (data-results (op/invert-op map-abs
                                            (value/value [1 -7 0])
                                            []
                                            []))))
    (is (empty? (data-results (op/invert-op map-abs
                                            (value/value [1 7 9 3 4 2 8])
                                            []
                                            []))))))

(deftest python-composite-concat-and-conversion-inverse-golden-cases
  (let [trees0 (fixture-composite
                :trees0
                [:concat [:input :left nil]
                 [:input :right nil]])
        map-toint (fixture-composite
                   :trees12
                   [:map toint-op
                    [:input :xs nil]])
        map-tofloat (fixture-composite
                     :trees17
                     [:map tofloat-op
                      [:input :xs nil]])]
    (is (= [[(vec (range 1 10))]]
           (data-results (op/invert-op trees0
                                       (value/value (vec (range 10)))
                                       [(value/value [0])]
                                       [0]))))
    (is (= [[[0.0 7.0]] [["0" "7"]]]
           (data-results (op/invert-op map-toint
                                       (value/value [0 7])
                                       []
                                       []))))
    (is (= [[(vec (range 100))]
            [(mapv #(str (double %)) (range 100))]]
           (data-results (op/invert-op map-tofloat
                                       (value/value (mapv double (range 100)))
                                       []
                                       []))))))

(deftest python-composite-urange-listwrap-and-bmap-inverse-golden-cases
  (let [trees7 (fixture-composite
                :trees7
                [:bmap op/add
                 [:input :xs nil]
                 [:urange [:input :n nil]]])
        co10 (fixture-composite
              :co10
              [:insert [:urange [:input :indices nil]]
               [:urange [:input :content nil]]
               [:urange [:input :rest nil]]])
        co11 (fixture-composite
              :co11
              [:insert [:trange [:input :idx-start nil]
                        [:input :idx-stop nil]
                        [:input :idx-step nil]]
               [:repeat [:input :n nil]
                [:listwrap [:input :wrapped nil]]]
               [:input :rest nil]])
        sparse-pic (assoc (vec (repeat 30 0))
                          3 1
                          14 1
                          25 1)]
    (is (= [[6]]
           (data-results (op/invert-op trees7
                                       (value/value (vec (range 6)))
                                       [(value/value (vec (repeat 6 0)))]
                                       [0]))))
    (is (= [[[0 -1 1 0 0 1 1 0 0 -1]]]
           (data-results (op/invert-op trees7
                                       (value/value [0 0 3 3 4 6 7 7 8 8])
                                       [(value/value 10)]
                                       [1]))))
    (is (empty? (data-results (op/invert-op co10
                                            (value/value (vec (concat (range 5)
                                                                      (range 9))))
                                            [(value/value 0)]
                                            [2]))))
    (is (= [[3 36 11 (vec (repeat 27 0))]]
           (data-results (op/invert-op co11
                                       (value/value sparse-pic)
                                       [(value/value 3)
                                        (value/value 1)]
                                       [3 4]))))))

(deftest python-composite-cumop-div-and-table-inverse-golden-cases
  (let [trees6 (fixture-composite
                :trees6
                [:cumop op/mult
                 [:input :start nil]
                 [:input :xs nil]])
        trees14 (fixture-composite
                 :trees14
                 [:cumop div-op
                  [:input :start nil]
                  [:input :xs nil]])
        trees15 (fixture-composite
                 :trees15
                 [:bmap div-op
                  [:input :xs nil]
                  [:input :ys nil]])
        trees18 (fixture-composite
                 :trees18
                 [:map toint-op
                  [:map bool-not-op
                   [:input :xs nil]]])
        trees9 (fixture-composite
                :trees9
                [:table [:input :values nil]
                 [:repeat [:add [:input :a nil]
                           [:input :b nil]]
                  [:map abs-op
                   [:input :xs nil]]]])]
    (is (= [[1 (vec (repeat 8 2))]]
           (data-results (op/invert-op trees6
                                       (value/value (mapv #(long (Math/pow 2 %))
                                                          (range 9)))
                                       []
                                       []))))
    (is (empty? (data-results (op/invert-op trees6
                                            (value/value (apply list (range 1 9)))
                                            []
                                            []))))
    (is (empty? (data-results (op/invert-op trees14
                                            (value/value [1.0 2.0 0.0 4.5])
                                            []
                                            []))))
    (is (= [[[3.0 5.0]]]
           (data-results (op/invert-op trees15
                                       (value/value [2.0 8.0])
                                       [(value/value [6.0 40.0])]
                                       [0]))))
    (is (empty? (data-results (op/invert-op trees18
                                            (value/value [13 -14 0 0 0])
                                            []
                                            []))))
    (is (= [[[-1 5] 6 [1 0 0 0 0]]
            [[-1 5] 6 [-1 0 0 0 0]]]
           (data-results (op/invert-op trees9
                                       (value/value (vec (take 30
                                                               (cycle [5 -1 -1 -1 -1]))))
                                       [(value/value 0)]
                                       [1]))))))

(deftest python-composite-insert-derived-inverse-golden-cases
  (let [trees2 (fixture-composite
                :trees2
                [:insert [:input :indices nil]
                 [:input :content nil]
                 [:repeat [:input :n nil]
                  [:input :motif nil]]])
        trees10 (fixture-composite
                 :trees10
                 [:concat [:repeat [:input :left-n nil]
                           [:input :left-motif nil]]
                  [:repeat [:input :right-n nil]
                   [:input :right-motif nil]]])
        output (apply list [1 1 0 1 0 1 0 0 0 0])]
    (is (= [[[0 0 0 0 0 0] 4 [1]]
            [0 4 [1]]]
           (data-results (op/invert-op trees2
                                       (value/value output)
                                       [(value/value [2 4 6 7 8 9])]
                                       [0]))))
    (is (= [[[1 1 1 1] 6 [0]]
            [1 6 [0]]]
           (data-results (op/invert-op trees2
                                       (value/value output)
                                       [(value/value [0 1 3 5])]
                                       [0]))))
    (is (= [[[2 4 6 7 8 9] 4 [1]]]
           (data-results (op/invert-op trees2
                                       (value/value output)
                                       [(value/value 0)]
                                       [1]))))
    (is (= [[[0 1 3 5] 6 [0]]]
           (data-results (op/invert-op trees2
                                       (value/value output)
                                       [(value/value 1)]
                                       [1]))))
    (doseq [[cond cond-inputs] [[[] []]
                                [[2] [4]]
                                [[3] [0]]
                                [[1] [5]]
                                [[2 3] [10 [0]]]]]
      (is (empty? (data-results (op/invert-op trees2
                                              (value/value output)
                                              (mapv value/value cond-inputs)
                                              cond)))))
    (is (= [[6 [3]]]
           (data-results (op/invert-op trees10
                                       (value/value (vec (repeat 10 3)))
                                       [(value/value 4)
                                        (value/value [3])]
                                       [0 1]))))))

(deftest python-composite-setitem-getitem-inverse-golden-cases
  (let [dec (fixture-composite
             :dec
             [:setitem [:input :base nil]
              [:input :mask nil]
              [:getitem [:input :xs nil]
               [:input :mask nil]]])
        cl-func (fixture-composite
                 :cl_func
                 [:dec [:input :base nil]
                  [:equal [:input :left nil]
                   [:input :right nil]]
                  [:input :xs nil]])
        insert-via-setitem (fixture-composite
                            :insert
                            [:setitem [:setitem [:input :base nil]
                                       [:input :mask nil]
                                       [:input :content nil]]
                             [:not [:input :mask nil]]
                             [:input :rest nil]])]
    (is (= [[[1.0 nil nil 4.0]
             [nil 2.0 3.0 nil]]]
           (data-results (op/invert-op dec
                                       (value/value [1.0 2.0 3.0 4.0])
                                       [(value/value [false true true false])]
                                       [1]))))
    (is (= [[[nil 1.0 nil 0.0]
             [1.0 nil 0.0 nil]]]
           (data-results (op/invert-op cl-func
                                       (value/value [1.0 1.0 0.0 0.0])
                                       [(value/value [1.0 0.0 0.0 1.0])
                                        (value/value [1.0 1.0 0.0 0.0])]
                                       [1 2]))))
    (is (= [[["" "" "" "" ""]
             ["b" "d" "e"]
             ["a" "c"]]]
           (data-results (op/invert-op insert-via-setitem
                                       (value/value ["a" "b" "c" "d" "e"])
                                       [(value/value [false true false true true])]
                                       [1]))))))

(deftest python-composite-remaining-empty-inverse-golden-cases
  (let [trees1 (fixture-composite
                :trees1
                [:concat [:input :left nil]
                 [:repeat [:input :n nil]
                  [:input :motif nil]]])
        trees3 (fixture-composite
                :trees3
                [:zip2d [:concat [:repeat [:input :left-n nil]
                                  [:input :left-motif nil]]
                         [:brange [:input :left-start nil]
                          [:input :left-stop nil]]]
                 [:concat [:brange [:input :right-start nil]
                           [:input :right-stop nil]]
                  [:repeat [:input :right-n nil]
                   [:input :right-motif nil]]]])
        trees4 (fixture-composite
                :trees4
                [:insert [:urange [:input :index-stop nil]]
                 [:input :content nil]
                 [:concat [:repeat [:input :repeat-n nil]
                           [:input :repeat-motif nil]]
                  [:concat [:urange [:input :range-stop nil]]
                   [:input :tail nil]]]])
        zipped [[0 1] [0 2] [0 3] [0 4] [1 4] [2 4] [3 4] [4 4]]
        good-output (apply list [9 9 9 9 9 3 3 3 3 3 0 1 2 3 4 100])
        wrong-output (apply list [1 1 1 1 1 3 3 3 3 3 0 1 2 3 4 100])]
    (is (empty? (data-results (op/invert-op trees1
                                            (value/value (apply list [1 2 3 4 5 5 5 5]))
                                            []
                                            []))))
    (is (empty? (data-results (op/invert-op trees3
                                            (value/value zipped)
                                            []
                                            []))))
    (is (empty? (data-results (op/invert-op trees4
                                            (value/value good-output)
                                            [(value/value 5)]
                                            [0]))))
    (is (empty? (data-results (op/invert-op trees4
                                            (value/value good-output)
                                            [(value/value 9)]
                                            [1]))))
    (doseq [[cond cond-inputs] [[[] []]
                                [[2] [5]]
                                [[0] [25]]
                                [[1] [5]]]]
      (is (empty? (data-results (op/invert-op trees4
                                              (value/value wrong-output)
                                              (mapv value/value cond-inputs)
                                              cond)))))))

(deftest composite-inversion-returns-no-results-for-unsatisfied-or-invalid-local-equations
  (let [product (sut/operator :product
                              [:mult [:input :x 0]
                               [:input :y 0]])
        square-plus-y (sut/operator :square-plus-y
                                    [:add [:mult [:input :x 2]
                                           [:input :x 2]]
                                     [:input :y 3]])]
    (is (empty? (data-results (op/invert-op product
                                            (value/value 10)
                                            [(value/value 0)]
                                            [1]))))
    (is (empty? (data-results (op/invert-op square-plus-y
                                            (value/value 32)
                                            [(value/value 7)]
                                            [1]))))))


(deftest composite-item-operators-call-and-invert-through-propagation
  (let [pick (sut/operator :pick-selected
                           [:getitem [:input :xs [3 5 2]]
                            [:input :mask [true false true]]])
        patch (sut/operator :patch-selected
                            [:setitem [:input :xs [342 6 8 252]]
                             [:input :mask [false true true false]]
                             [:input :items [78 34]]])]
    (is (= [3 2]
           (value/datum (op/apply-op pick [(value/value [3 5 2])
                                           (value/value [true false true])]))))
    (is (= [[] [1]]
           (:conditions pick)))
    (is (= [[[2.0 nil nil 3.0]]]
           (data-results (op/invert-op pick
                                       (value/value [2.0 3.0])
                                       [(value/value [true false false true])]
                                       [1]))))
    (is (= [[[2.0 3.0] [0 1]]]
           (data-results (op/invert-op pick
                                       (value/value [2.0 3.0])
                                       []
                                       []))))
    (is (= [342 78 34 252]
           (value/datum (op/apply-op patch [(value/value [342 6 8 252])
                                            (value/value [false true true false])
                                            (value/value [78 34])]))))
    (is (= [[0] [1]]
           (:conditions patch)))
    (is (= [[[342 nil nil 252] [78 34]]]
           (data-results (op/invert-op patch
                                       (value/value [342 78 34 252])
                                       [(value/value [false true true false])]
                                       [1]))))))


(deftest composite-generated-mask-feeds-setitem
  (let [patch (sut/operator :threshold-patch
                            [:setitem [:input :base ["-" "-" "-" "-"]]
                             [:lessthan [:input :scores [0 1 2 3]]
                              [:input :threshold 2]]
                             [:input :items ["x" "x"]]])]
    (is (= [[1 2]]
           (:conditions patch)))
    (is (= ["x" "x" "-" "-"]
           (value/datum (op/apply-op patch [(value/value ["-" "-" "-" "-"])
                                            (value/value [0 1 2 3])
                                            (value/value 2)
                                            (value/value ["x" "x"])]))))
    (is (= [[["" "" "-" "-"] ["x" "x"]]]
           (data-results (op/invert-op patch
                                       (value/value ["x" "x" "-" "-"])
                                       [(value/value [0 1 2 3])
                                        (value/value 2)]
                                       [1 2]))))))


(deftest composite-length-derived-base-feeds-setitem
  (let [patch (sut/operator :length-derived-threshold-patch
                            [:setitem [:repeat [:len [:input :scores [0 1 2 3]]] ["-"]]
                             [:lessthan [:input :scores [0 1 2 3]]
                              [:input :threshold 2]]
                             [:input :items ["x" "x"]]])]
    (is (= ["x" "x" "-" "-"]
           (value/datum (op/apply-op patch [(value/value [0 1 2 3])
                                            (value/value 2)
                                            (value/value ["x" "x"])]))))))
