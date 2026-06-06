(ns ciwi.wunderbaum-test
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [ciwi.wunderbaum :as sut]
            [clojure.test :refer [deftest is testing]]))

(defn- range-wunderbaum
  []
  (sut/wunderbaum
   {:registry {:brange op/brange}
    :ops-with-counts [{:op :brange
                       :count 0
                       :input-specs [:int :int]
                       :output-spec :array-int}]}))

(deftest wunderbaum-requires-injected-registry
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires an injected operator registry"
       (sut/wunderbaum {:ops-with-counts []}))))

(deftest operator-elements-are-indexed-by-generalized-condition-specs
  (let [elements (sut/operator-elements-by-condition-key
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
        tuples (sut/node-tuples g {:max-tuple-len 2
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
                          (when (and (empty? cond)
                                     (vector? output)
                                     (seq output)
                                     (apply = output))
                            [[(first output)]]))})
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
