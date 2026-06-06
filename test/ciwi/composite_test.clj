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
         {:abs (structural-op :abs [[]] true)
          :bmap (structural-op :bmap [[0] [0 1] [0 2]] false)
          :listwrap (structural-op :listwrap [[]] true)
          :listslice (structural-op :listslice [[0 1 2]] false)
          :sum (structural-op :sum [] true)
          :union union-op
          :urange (structural-op :urange [[]] true)
          :zip2d zip2d-op}))

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
