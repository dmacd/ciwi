(ns ciwi.conditions-test
  (:require [ciwi.composite :as composite]
            [ciwi.conditions :as sut]
            [ciwi.dsl :as dsl]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [clojure.test :refer [deftest is testing]]))

(deftest combines-child-conditions-with-offsets
  (is (= [[0 1 4] [0 1 5] [2 4] [2 5]]
         (sut/condition-combinations [[[0 1] [2]]
                                      [[0] [1]]]
                                     [0 4]))))

(deftest filters-redundant-conditions-like-python-william
  (doseq [[input expected]
          '(([()] [()])
            ([() (1)] [() (1)])
            ([(1) ()] [() (1)])
            ([(0 2) (1) ()] [() (1) (0 2)])
            ([(0 2) () (3)] [() (3) (0 2)])
            ([(0) (0 2)] [(0)])
            ([(0 1) (0)] [(0)])
            ([(3) (0 2 3) (1 3)] [(3)])
            ([(0 2) (2) (0 1 2)] [(2)])
            ([(2) (0 2) (2)] [(2)])
            ([(2) (1 2) (1)] [(1) (2)])
            ([(3) (0 1)] [(3) (0 1)])
            ([(1 2) (2 3)] [(1 2) (2 3)])
            ([(0) (3) (4)] [(0) (3) (4)])
            ([(2) (1 2 4 5) (5)] [(2) (5)]))]
    (testing (str input)
      (is (= (mapv vec expected)
             (sut/filter-redundant (mapv vec input) 4))))))

(deftest removes-redundant-conditions-like-python-william
  (is (= [[0] [1]]
         (sut/remove-redundant-conditions [[] [0] [1]])))
  (is (= [[0] [1 3 2]]
         (sut/remove-redundant-conditions [[1 2] [0] [1 3 2] [] [1]]))))

(deftest extracts-tree-conditions-from-clojure-graphs
  (let [{add-g :graph add-root :root} (dsl/from-expr [:add 1 2])
        {neg-g :graph neg-root :root} (dsl/from-expr [:negate 1])
        {cat-g :graph cat-root :root} (dsl/from-expr [:concat [:brange 0 3]
                                                      [:repeat 2 [:z]]])]
    (is (= [[0] [1]]
           (sut/get-conditions add-g add-root)))
    (is (= [[]]
           (sut/get-conditions neg-g neg-root)))
    (is (= [[0 1] [2 3]]
           (sut/get-conditions cat-g cat-root)))))

(defn- condition-only-op
  [id conditions]
  (op/operator
   {:id id
    :conditions (mapv vec conditions)
    :call (constantly nil)}))

(def ^:private condition-fixture-registry
  (merge op/registry
         {:abs (condition-only-op :abs [[]])
          :bmap (condition-only-op :bmap [[0] [0 1] [0 2]])
          :listwrap (condition-only-op :listwrap [[]])
          :listslice (condition-only-op :listslice [[0 1 2]])
          :sum (condition-only-op :sum [])
          :union (condition-only-op :union [[0] [1]])
          :urange (condition-only-op :urange [[]])
          :zip2d (condition-only-op :zip2d [[]])}))

(defn- condition-fixture-spec
  [expr]
  (let [counter (atom 0)
        inputs (atom {})]
    (letfn [(next-id [prefix]
              (keyword (str (name prefix) (swap! counter inc))))
            (operator-for [head]
              (get condition-fixture-registry head))
            (build [g form]
              (cond
                (and (vector? form) (= :input (first form)))
                (let [[_ input-id sample] form]
                  (if-let [node-id (get @inputs input-id)]
                    [g node-id]
                    (let [node-id (next-id :v)]
                      (swap! inputs assoc input-id node-id)
                      [(graph/add-value g node-id sample) node-id])))

                (and (vector? form) (operator-for (first form)))
                (let [operator (operator-for (first form))
                      [g child-ids]
                      (reduce (fn [[acc ids] child]
                                (let [[acc child-id] (build acc child)]
                                  [acc (conj ids child-id)]))
                              [g []]
                              (rest form))
                      parent-id (next-id :v)
                      op-id (next-id :op)]
                  [(-> g
                       (graph/add-value parent-id nil)
                       (graph/add-operator op-id operator parent-id child-ids))
                   parent-id])

                :else
                (let [node-id (next-id :v)]
                  [(graph/add-value g node-id form) node-id])))]
      (let [[g root-id] (build (graph/empty-graph) expr)]
        {:graph (graph/set-roots g [root-id])
         :root root-id}))))

(def ^:private python-condition-fixtures
  [{:name "co0"
    :expr [:insert [:input :indices [1]]
           [:input :content 2]
           [:input :rest [3 4]]]
    :expected [[] [0] [1]]}
   {:name "co1"
    :expr [:add [:mult [:input :a 2]
                 [:input :b 3]]
           [:input :c 5]]
    :expected [[0 1] [0 2] [1 2]]}
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
    :expected [[] [0 1 2] [3 4 5]]}
   {:name "co3"
    :expr [:sub [:mult [:input :a 3]
                 [:input :b 4]]
           [:add [:input :c 5]
            [:negate [:input :d 2]]]]
    :expected [[0 1 2] [0 1 3] [0 2 3] [1 2 3]]}
   {:name "co4"
    :expr [:add [:negate [:input :x 3]]
           [:sub [:input :y 12]
            [:input :z 5]]]
    :expected [[0 1] [0 2] [1 2]]}
   {:name "co5"
    :expr [:add [:mult [:input :a nil]
                 [:input :b nil]]
           [:insert [:input :indices nil]
            [:input :content nil]
            [:input :rest nil]]]
    :expected [[0 1] [0 2 3 4] [1 2 3 4]]}
   {:name "co6"
    :expr [:repeat [:sub [:add [:input :a nil]
                          [:input :b nil]]
                    [:input :c nil]]
           [:repeat [:input :n nil]
            [:map [:input :f-neg op/negate]
             [:input :xs nil]]]]
    :expected [[0 1 4] [0 2 4] [1 2 4]]}
   {:name "co7"
    :expr [:abs [:add [:abs [:input :x nil]]
            [:input :y nil]]]
    :expected [[0] [1]]}
   {:name "co8"
    :expr [:bmap [:input :f-add op/add]
           [:input :x nil]
           [:insert [:input :indices nil]
            [:input :content nil]
            [:input :rest nil]]]
    :expected [[0 1] [0 2 3 4]]}
   {:name "co9"
    :expr [:union [:zip2d [:brange [:input :start nil]
                           [:input :stop nil]]
                   [:repeat [:input :n nil]
                    [:input :motif nil]]]
           [:input :extra nil]]
    :expected [[4] [0 1 2 3]]}
   {:name "co10"
    :expr [:insert [:urange [:input :indices nil]]
           [:urange [:input :content nil]]
           [:urange [:input :rest nil]]]
    :expected [[] [0] [1]]}
   {:name "co11"
    :expr [:insert [:trange [:input :idx-start nil]
                    [:input :idx-stop nil]
                    [:input :idx-step nil]]
           [:repeat [:input :n nil]
            [:listwrap [:input :wrapped nil]]]
           [:input :rest nil]]
    :expected [[] [3] [4] [0 1 2]]}
   {:name "co12"
    :expr [:repeat [:input :n nil]
           [:input :motif nil]]
    :expected [[] [0] [1]]}
   {:name "co13"
    :expr [:repeat [:sub [:add [:input :a nil]
                          [:input :b nil]]
                    [:input :c nil]]
           [:repeat [:input :n nil]
            [:map [:input :f-neg op/negate]
             [:input :xs nil]]]]
    :expected [[0 1 4] [0 2 4] [1 2 4]]}
   {:name "co14"
    :expr [:repeat [:input :n nil]
           [:listslice [:input :xs nil]
            [:input :start nil]
            [:input :stop nil]]]
    :expected [[1 2 3]]}
   {:name "co15"
    :expr [:add [:input :a nil]
           [:input :b nil]]
    :expected [[0] [1]]}
   {:name "co16"
    :expr [:sum [:urange [:input :n nil]]]
    :expected [[0]]}
   {:name "co17"
    :expr [:repeat [:input :n nil]
           [:listslice [:input :xs nil]
            [:add [:input :start-a nil]
             [:input :start-b nil]]
            [:sub [:input :stop-a nil]
             [:input :stop-b nil]]
            [:input :step nil]]]
    :expected [[1 2 3 4 5]]}
   {:name "co18"
    :expr [:bmap [:input :f-add op/add]
           [:input :x nil]
           [:input :y nil]]
    :expected [[0 1] [0 2]]}
   {:name "co19"
    :expr [:map [:input :f-neg op/negate]
           [:input :xs nil]]
    :expected [[0]]}
   {:name "co20"
    :expr [:bmap [:input :f-repeat op/repeat]
           [:input :x nil]
           [:input :y nil]]
    :expected [[0]]}
   {:name "co21"
    :expr [:map [:input :f-neg op/negate]
           [:urange [:input :n nil]]]
    :expected [[0]]}
   {:name "dag0"
    :expr [:mult [:input :x 15]
           [:input :x 15]]
    :expected [[0]]}
   {:name "dag1"
    :expr [:add [:mult [:input :x 5]
                 [:input :x 5]]
           [:input :x 5]]
    :expected [[0]]}
   {:name "dag2"
    :expr [:add [:mult [:input :x 5]
                 [:input :x 5]]
           [:input :y 7]]
    :expected [[0]]}
   {:name "dag3"
    :expr [:add [:mult [:input :x 5]
                 [:input :y 7]]
           [:mult [:input :x 5]
            [:input :y 7]]]
    :expected [[0 1]]}
   {:name "dag4"
    :expr [:zip2d [:brange [:input :a nil]
                   [:add [:input :a nil]
                    [:input :b nil]]]
           [:repeat [:input :b nil]
            [:input :c nil]]]
    :expected [[1]]}
   {:name "dag5"
    :expr [:union [:zip2d [:brange [:input :a nil]
                           [:add [:input :a nil]
                            [:input :b nil]]]
                   [:repeat [:input :b nil]
                    [:input :c nil]]]
           [:input :d nil]]
    :expected [[1]]}
   {:name "dag6"
    :expr [:setitem [:input :xs nil]
           [:input :idx nil]
           [:getitem [:input :source nil]
            [:input :idx nil]]]
    :expected [[0] [1]]}
   {:name "dag7"
    :expr [:getitem [:input :xs nil]
           [:brange [:input :n nil]
            [:input :n nil]]]
    :expected [[1]]}])

(deftest python-composite-condition-golden-cases
  (doseq [{:keys [name expr expected]} python-condition-fixtures]
    (testing name
      (let [cop (composite/operator (keyword name)
                                    (condition-fixture-spec expr))]
        (is (= expected (:conditions cop)))))))
