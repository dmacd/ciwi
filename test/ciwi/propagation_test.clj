(ns ciwi.propagation-test
  (:require [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.propagation :as sut]
            [clojure.test :refer [deftest is testing]]))

(def william-co4-fire-down-golden
  "Transcribed from ../william/william/tests/test_propagation.py:
  graph co4, inputs [None, 12, 5], expected inferred child value 12 - 5."
  {:output 12
   :known-child 5
   :expected-missing-child 7})

(defn add-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :left nil)
      (graph/add-value :right nil)
      (graph/add-operator :add-out op/add :out [:left :right])))

(defn- data-at
  [mem id]
  (:data (sut/value-at mem id)))

(defn- memory-data
  [mem ids]
  (into {}
        (keep (fn [id]
                (when (contains? mem id)
                  [id (data-at mem id)])))
        ids))

(deftest propagates-add-up
  (let [g (add-graph)
        mem (sut/memory {:left 3 :right 4})
        result (first (sut/propagate g mem))]
    (is (= 7 (:data (sut/value-at result :out))))))

(deftest propagates-add-down-from-william-golden
  (testing (:doc (meta #'william-co4-fire-down-golden))
    (let [{:keys [output known-child expected-missing-child]} william-co4-fire-down-golden
          g (add-graph)
          mem (sut/memory {:out output :right known-child})
          result (first (sut/propagate g mem))]
      (is (= expected-missing-child
             (:data (sut/value-at result :left)))))))

(deftest propagates-unary-inversion
  (let [g (-> (graph/empty-graph)
              (graph/add-value :out nil)
              (graph/add-value :x nil)
              (graph/add-operator :negate-out op/negate :out [:x]))
        mem (sut/memory {:out -3})
        result (first (sut/propagate g mem))]
    (is (= 3 (:data (sut/value-at result :x))))))


(defn affine-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :scaled nil)
      (graph/add-value :range nil)
      (graph/add-value :range-start nil)
      (graph/add-value :n nil)
      (graph/add-value :step nil)
      (graph/add-value :start nil)
      (graph/add-operator :make-range op/brange :range [:range-start :n])
      (graph/add-operator :scale op/mult :scaled [:range :step])
      (graph/add-operator :shift op/add :out [:scaled :start])))

(deftest nil-memory-values-are-treated-as-unknowns
  (let [g (add-graph)
        missing-left (first (sut/propagate g
                                           (sut/memory {:left nil
                                                        :right 5})
                                           {:partial? true}))
        nil-output (first (sut/propagate g
                                         (sut/memory {:out nil
                                                      :right 5})
                                         {:partial? true}))]
    (is (nil? (sut/value-at missing-left :out)))
    (is (nil? (sut/value-at nil-output :left)))
    (is (nil? (:data (sut/value-at nil-output :out))))))

(defn co2-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :indices nil)
      (graph/add-value :indices-start nil)
      (graph/add-value :indices-stop nil)
      (graph/add-value :indices-step nil)
      (graph/add-value :content nil)
      (graph/add-value :content-start nil)
      (graph/add-value :content-stop nil)
      (graph/add-value :content-step nil)
      (graph/add-value :rest nil)
      (graph/add-value :rest-start nil)
      (graph/add-value :rest-stop nil)
      (graph/add-value :rest-step nil)
      (graph/add-operator :indices-trange op/trange :indices
                          [:indices-start :indices-stop :indices-step])
      (graph/add-operator :content-trange op/trange :content
                          [:content-start :content-stop :content-step])
      (graph/add-operator :rest-trange op/trange :rest
                          [:rest-start :rest-stop :rest-step])
      (graph/add-operator :insert op/insert :out [:indices :content :rest])))

(defn co3-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :prod nil)
      (graph/add-value :a nil)
      (graph/add-value :b nil)
      (graph/add-value :sum nil)
      (graph/add-value :c nil)
      (graph/add-value :neg nil)
      (graph/add-value :d nil)
      (graph/add-operator :prod-mult op/mult :prod [:a :b])
      (graph/add-operator :negate op/negate :neg [:d])
      (graph/add-operator :sum-add op/add :sum [:c :neg])
      (graph/add-operator :out-sub op/sub :out [:prod :sum])))

(defn co4-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :neg nil)
      (graph/add-value :x nil)
      (graph/add-value :diff nil)
      (graph/add-value :y nil)
      (graph/add-value :z nil)
      (graph/add-operator :negate op/negate :neg [:x])
      (graph/add-operator :diff-sub op/sub :diff [:y :z])
      (graph/add-operator :out-add op/add :out [:neg :diff])))

(deftest python-propagate-up-golden-cases
  (doseq [{:keys [name graph memory expected]}
          [{:name "co2"
            :graph (co2-graph)
            :memory {:indices-start 1
                     :indices-stop 7
                     :indices-step 2
                     :content-start 3
                     :content-stop 12
                     :content-step 3
                     :rest-start 15
                     :rest-stop 23
                     :rest-step 2}
            :expected {:out [15 3 17 6 19 9 21]
                       :indices [1 3 5]
                       :content [3 6 9]
                       :rest [15 17 19 21]}}
           {:name "co3 partial"
            :graph (co3-graph)
            :memory {:a 3 :b 4 :c 5 :d nil}
            :expected {:prod 12}}
           {:name "co4 partial right branch"
            :graph (co4-graph)
            :memory {:x nil :y 12 :z 5}
            :expected {:diff 7}}
           {:name "co4 partial left branch"
            :graph (co4-graph)
            :memory {:x 3 :y 43 :z nil}
            :expected {:neg -3}}]]
    (testing name
      (let [result (first (sut/propagate graph (sut/memory memory) {:partial? true}))]
        (is (= expected
               (memory-data result (keys expected))))
        (when-not (contains? expected :out)
          (is (not (contains? result :out))))))))

(defn set-mean-add-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :base nil)
      (graph/add-value :mask nil)
      (graph/add-value :item nil)
      (graph/add-value :mean nil)
      (graph/add-value :offset nil)
      (graph/add-operator :patch op/setitem :out [:base :mask :item])
      (graph/add-operator :mean-op op/mean :mean [:item])
      (graph/add-operator :item-add op/add :item [:mean :offset])))

(defn trees2-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :indices nil)
      (graph/add-value :content nil)
      (graph/add-value :rest nil)
      (graph/add-value :repeat-n nil)
      (graph/add-value :repeat-motif nil)
      (graph/add-operator :insert op/insert :out [:indices :content :rest])
      (graph/add-operator :repeat op/repeat :rest [:repeat-n :repeat-motif])))

(deftest python-random-propagation-golden-cases
  (doseq [{:keys [name graph memory observed-ids expected-results]}
          [{:name "matching/set_mean_add"
            :graph (set-mean-add-graph)
            :memory {:out [33 17 18 35 37 39 19]
                     :mask [true false false true true true false]}
            :observed-ids [:out :base :mask :item :mean :offset]
            :expected-results
            [{:out [33 17 18 35 37 39 19]
              :base [nil 17 18 nil nil nil 19]
              :mask [true false false true true true false]
              :item [33 35 37 39]
              :mean 36.0
              :offset [-3.0 -1.0 1.0 3.0]}]}
           {:name "composite/trees2"
            :graph (trees2-graph)
            :memory {:out [1 1 0 1 0 1 0 0 0 0]
                     :indices [2 4 6 7 8 9]}
            :observed-ids [:out :indices :content :rest :repeat-n :repeat-motif]
            :expected-results
            [{:out [1 1 0 1 0 1 0 0 0 0]
              :indices [2 4 6 7 8 9]
              :content [0 0 0 0 0 0]
              :rest [1 1 1 1]
              :repeat-n 4
              :repeat-motif [1]}
             {:out [1 1 0 1 0 1 0 0 0 0]
              :indices [2 4 6 7 8 9]
              :content 0
              :rest [1 1 1 1]
              :repeat-n 4
              :repeat-motif [1]}]}]]
    (testing name
      (let [results (sut/propagate graph (sut/memory memory))]
        (is (= expected-results
               (mapv #(memory-data % observed-ids) results)))))))

(deftest propagates-nested-affine-graph-upward
  (let [g (affine-graph)
        mem (sut/memory {:range-start 0
                         :n 4
                         :step 3
                         :start 2})
        result (first (sut/propagate g mem))]
    (is (= [0 1 2 3]
           (:data (sut/value-at result :range))))
    (is (= [0 3 6 9]
           (:data (sut/value-at result :scaled))))
    (is (= [2 5 8 11]
           (:data (sut/value-at result :out))))))

(deftest propagates-nested-affine-graph-downward
  (let [g (affine-graph)
        mem (sut/memory {:out [2 5 8 11]
                         :start 2
                         :step 3})
        result (first (sut/propagate g mem))]
    (is (= [0 3 6 9]
           (:data (sut/value-at result :scaled))))
    (is (= [0 1 2 3]
           (:data (sut/value-at result :range))))
    (is (= 0
           (:data (sut/value-at result :range-start))))
    (is (= 4
           (:data (sut/value-at result :n))))))


(defn getitem-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :xs nil)
      (graph/add-value :idx nil)
      (graph/add-operator :pick op/getitem :out [:xs :idx])))

(deftest propagates-getitem-up-and-down-with-boolean-mask
  (let [g (getitem-graph)
        up (first (sut/propagate g
                                 (sut/memory {:xs [3 5 2]
                                              :idx [true false true]})))
        down (first (sut/propagate g
                                   (sut/memory {:out [2.0 3.0]
                                                :idx [true false false true]})))]
    (is (= [3 2]
           (:data (sut/value-at up :out))))
    (is (= [2.0 nil nil 3.0]
           (:data (sut/value-at down :xs))))))

(defn setitem-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :xs nil)
      (graph/add-value :idx nil)
      (graph/add-value :item nil)
      (graph/add-operator :patch op/setitem :out [:xs :idx :item])))

(deftest propagates-setitem-up-and-down-through-mask-and-source
  (let [g (setitem-graph)
        up (first (sut/propagate g
                                 (sut/memory {:xs [342 6 8 252]
                                              :idx [false true true false]
                                              :item [78 34]})))
        mask-down (first (sut/propagate g
                                        (sut/memory {:out [342 78 34 252]
                                                     :idx [false true true false]})))
        source-down (first (sut/propagate g
                                          (sut/memory {:out ["-" "-" "-" "-" "x"]
                                                       :xs ["-" "-" "-" "-" "-"]})))]
    (is (= [342 78 34 252]
           (:data (sut/value-at up :out))))
    (is (= [342 nil nil 252]
           (:data (sut/value-at mask-down :xs))))
    (is (= [78 34]
           (:data (sut/value-at mask-down :item))))
    (is (= 4
           (:data (sut/value-at source-down :idx))))
    (is (= "x"
           (:data (sut/value-at source-down :item))))))


(defn threshold-patch-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :base nil)
      (graph/add-value :scores nil)
      (graph/add-value :threshold nil)
      (graph/add-value :mask nil)
      (graph/add-value :items nil)
      (graph/add-operator :mask-op op/lessthan :mask [:scores :threshold])
      (graph/add-operator :patch op/setitem :out [:base :mask :items])))

(deftest propagates-generated-mask-into-setitem
  (let [g (threshold-patch-graph)
        up (first (sut/propagate g
                                 (sut/memory {:base ["-" "-" "-" "-"]
                                              :scores [0 1 2 3]
                                              :threshold 2
                                              :items ["x" "x"]})))
        down (first (sut/propagate g
                                   (sut/memory {:out ["x" "x" "-" "-"]
                                                :scores [0 1 2 3]
                                                :threshold 2})))]
    (is (= [true true false false]
           (:data (sut/value-at up :mask))))
    (is (= ["x" "x" "-" "-"]
           (:data (sut/value-at up :out))))
    (is (= [true true false false]
           (:data (sut/value-at down :mask))))
    (is (= ["" "" "-" "-"]
           (:data (sut/value-at down :base))))
    (is (= ["x" "x"]
           (:data (sut/value-at down :items))))))


(defn length-derived-threshold-patch-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out nil)
      (graph/add-value :scores nil)
      (graph/add-value :n nil)
      (graph/add-value :base nil)
      (graph/add-value :threshold nil)
      (graph/add-value :mask nil)
      (graph/add-value :items nil)
      (graph/add-value :fill ["-"])
      (graph/add-operator :len-scores op/len :n [:scores])
      (graph/add-operator :make-base op/repeat :base [:n :fill])
      (graph/add-operator :mask-op op/lessthan :mask [:scores :threshold])
      (graph/add-operator :patch op/setitem :out [:base :mask :items])))

(deftest propagates-length-derived-base-into-setitem
  (let [g (length-derived-threshold-patch-graph)
        result (first (sut/propagate g
                                     (sut/memory {:scores [0 1 2 3]
                                                  :fill ["-"]
                                                  :threshold 2
                                                  :items ["x" "x"]})))]
    (is (= 4
           (:data (sut/value-at result :n))))
    (is (= ["-" "-" "-" "-"]
           (:data (sut/value-at result :base))))
    (is (= [true true false false]
           (:data (sut/value-at result :mask))))
    (is (= ["x" "x" "-" "-"]
           (:data (sut/value-at result :out))))))
