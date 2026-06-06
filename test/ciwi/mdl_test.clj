(ns ciwi.mdl-test
  (:require [ciwi.graph :as graph]
            [ciwi.mdl :as sut]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- approx=
  [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1.0e-9))

(deftest chooses-shorter-operator-description
  (let [[g _] (-> (graph/empty-graph)
                  (graph/add-value :out [0 1 2 3 4])
                  (graph/add-derived-option :out op/brange [0 5]))
        result (sut/node-dl g :out)]
    (is (< (:dl result)
           (:dl (sut/node-dl (graph/add-value (graph/empty-graph) :out [0 1 2 3 4])
                             :out))))
    (is (= [:out-brange] (sut/selected-operators g :out)))))


(defn- with-dl
  [operator dl]
  (assoc operator :dl dl))

(def cheap-add (with-dl op/add 0.1))
(def cheap-sub (with-dl op/sub 0.1))
(def cheap-mult (with-dl op/mult 0.1))
(def expensive-add (with-dl op/add 1000.0))
(def expensive-concat (with-dl op/concat 1000.0))

(deftest raw-description-wins-when-every-option-is-more-expensive
  (let [[g op-id] (-> (graph/empty-graph)
                      (graph/add-value :out 3)
                      (graph/add-derived-option :out expensive-add [1 2]))
        result (sut/node-dl g :out)]
    (is (= :raw (get-in result [:choice :kind])))
    (is (empty? (sut/selected-operators g :out)))
    (is (= 3 (sut/selected-expression g :out)))
    (is (contains? (set (:options (graph/node g :out))) op-id))))

(deftest lower-dl-alternative-option-is-selected
  (let [g0 (-> (graph/empty-graph)
               (graph/add-value :out [0 1 2 3 4 5 6 7]))
        [g1 expensive-id] (graph/add-derived-option g0 :out expensive-concat
                                                    [[0 1 2 3] [4 5 6 7]])
        [g2 cheap-id] (graph/add-derived-option g1 :out op/brange [0 8])
        result (sut/node-dl g2 :out)]
    (is (= :operator (get-in result [:choice :kind])))
    (is (= cheap-id (get-in result [:choice :op-id])))
    (is (not= expensive-id (get-in result [:choice :op-id])))
    (is (= [cheap-id] (sut/selected-operators g2 :out)))
    (is (= [:brange 0 8] (sut/selected-expression g2 :out)))))

(deftest nested-selected-operators-are-collected-in-preorder
  (let [g0 (-> (graph/empty-graph)
               (graph/add-value :out [2 5 8 11 14 17])
               (graph/add-value :scaled [0 3 6 9 12 15])
               (graph/add-value :base [0 1 2 3 4 5])
               (graph/add-value :step 3)
               (graph/add-value :start 2))
        [g1 base-id] (graph/add-derived-option g0 :base op/brange [0 6])
        g2 (-> g1
               (graph/add-operator :scaled-mult cheap-mult :scaled [:base :step])
               (graph/add-operator :out-add cheap-add :out [:scaled :start]))]
    (is (= [:out-add :scaled-mult base-id]
           (sut/selected-operators g2 :out)))
    (is (= [:add [:mult [:brange 0 6] 3] 2]
           (sut/selected-expression g2 :out)))))

(deftest graph-dl-sums-independent-root-descriptions
  (let [[g _] (-> (graph/empty-graph)
                  (graph/add-value :range [0 1 2 3])
                  (graph/add-value :raw-large [100 200 300])
                  (graph/add-derived-option :range op/brange [0 4]))
        g (graph/set-roots g [:range :raw-large])]
    (is (= (+ (:dl (sut/node-dl g :range))
              (:dl (sut/node-dl g :raw-large)))
           (sut/graph-dl g)))
    (is (= #{:range :raw-large}
           (set (graph/roots g))))))

(deftest graph-dl-charges-selected-shared-children-once
  (let [g0 (-> (graph/empty-graph)
               (graph/add-value :left 1000)
               (graph/add-value :right 1001)
               (graph/add-value :shared [0 1 2 3 4 5 6 7])
               (graph/add-value :a [0 1 2 3 4 5 6 7 1000])
               (graph/add-value :b [0 1 2 3 4 5 6 7 1001]))
        [g1 _] (graph/add-derived-option g0 :shared op/brange [0 8])
        g2 (-> g1
               (graph/add-operator :a-concat op/concat :a [:shared :left])
               (graph/add-operator :b-concat op/concat :b [:shared :right])
               (graph/set-roots [:a :b]))
        shared-dl (:dl (sut/node-dl g2 :shared))
        root-summed-dl (+ (:dl (sut/node-dl g2 :a))
                          (:dl (sut/node-dl g2 :b)))
        shared-graph-dl (+ (:dl (:operator (graph/node g2 :a-concat)))
                           (:dl (:operator (graph/node g2 :b-concat)))
                           shared-dl
                           (value/desc-len (value/value 1000))
                           (value/desc-len (value/value 1001)))]
    (testing "selected structure reuses the shared value node"
      (is (= [:concat [:brange 0 8] 1000]
             (sut/selected-expression g2 :a)))
      (is (= [:concat [:brange 0 8] 1001]
             (sut/selected-expression g2 :b))))
    (testing "graph-level MDL charges the selected shared node once"
      (is (approx= shared-graph-dl (sut/graph-dl g2)))
      (is (approx= (+ shared-graph-dl shared-dl) root-summed-dl)))))

(defn- section1-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out64 64)
      (graph/add-value :v3 3)
      (graph/add-value :v4 4)
      (graph/add-value :out15 15)
      (graph/add-value :v1 1)
      (graph/add-operator :out64-add op/add :out64 [:v3 :v4])
      (graph/add-operator :out15-sub op/sub :out15 [:v4 :v1])
      (graph/set-roots [:out64 :out15])))

(defn- section2-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out64 64)
      (graph/add-value :v3 3)
      (graph/add-value :v4 4)
      (graph/add-value :out15 15)
      (graph/add-operator :out64-add op/add :out64 [:v3 :v4])
      (graph/add-operator :out15-sub op/sub :out15 [:v4 :out64])
      (graph/set-roots [:out64 :out15])))

(defn- section3-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out15 15)
      (graph/add-value :v4 4)
      (graph/add-value :out64 64)
      (graph/add-value :v3 3)
      (graph/add-operator :out15-sub op/sub :out15 [:v4 :out64])
      (graph/add-operator :out64-add op/add :out64 [:v3 :v4])
      (graph/add-operator :out15-negate op/negate :out15 [:v3])
      (graph/set-roots [:out15])))

(defn- section4-graph
  []
  (-> (graph/empty-graph)
      (graph/add-value :out64 64)
      (graph/add-value :v3 3)
      (graph/add-value :v15 15)
      (graph/add-value :v4 4)
      (graph/add-value :out1234 1234)
      (graph/add-operator :out64-add op/add :out64 [:v3 :v15])
      (graph/add-operator :v15-sub op/sub :v15 [:v4 :out64])
      (graph/add-operator :out1234-negate op/negate :out1234 [:v15])
      (graph/set-roots [:out64 :out1234])))

(deftest python-bottleneck-section-golden-cases
  (doseq [{:keys [name graph expected-dl expected-selected expected-expressions]}
          [{:name "section1"
            :graph (section1-graph)
            :expected-dl 26.280150129207975
            :expected-selected [:out64-add :out15-sub]
            :expected-expressions {:out64 [:add 3 4]
                                   :out15 [:sub 4 1]}}
           {:name "section2"
            :graph (section2-graph)
            :expected-dl 19.954900924594817
            :expected-selected [:out64-add :out15-sub]
            :expected-expressions {:out64 [:add 3 4]
                                   :out15 [:sub 4 [:add 3 4]]}}
           {:name "section3"
            :graph (section3-graph)
            :expected-dl 9.664933050786377
            :expected-selected [:out15-negate]
            :expected-expressions {:out15 [:negate 3]}}
           {:name "section4"
            :graph (section4-graph)
            :expected-dl 22.766942937463458
            :expected-selected [:out64-add :out1234-negate]
            :expected-expressions {:out64 [:add 3 15]
                                   :out1234 [:negate 15]}}]]
    (testing name
      (let [description (sut/graph-description graph)]
        (is (approx= expected-dl (:dl description)))
        (is (= expected-selected (:selected description)))
        (is (= expected-expressions
               (into {}
                     (map (fn [root-id]
                            [root-id (sut/selected-expression graph root-id)])
                          (graph/roots graph)))))))))

(def ^:private fixture-operators
  {"add" op/add
   "mult" op/mult
   "negate" op/negate
   "sub" op/sub})

(defn- fixture-resource
  [name]
  (or (io/resource (str "ciwi/fixtures/min_desc_len/" name ".dot"))
      (throw (ex-info "Missing DOT fixture" {:name name}))))

(defn- dot-id
  [raw-id]
  (keyword raw-id))

(defn- parse-dot-value
  [label]
  (edn/read-string (if (str/starts-with? label "a")
                     (subs label 1)
                     label)))

(defn- parse-dot-node-line
  [line]
  (when-let [[_ raw-id root-marker label]
             (re-find #"^([^\s\[\*]+)(\*)?\s+\[label=\"([^\"]+)\"" line)]
    (if-let [operator (get fixture-operators label)]
      {:kind :operator
       :raw-id raw-id
       :id (dot-id raw-id)
       :operator operator}
      {:kind :value
       :raw-id raw-id
       :id (dot-id raw-id)
       :root? (boolean root-marker)
       :value (parse-dot-value label)})))

(defn- parse-dot-edge-line
  [line]
  (when-let [[_ source target]
             (re-find #"\"([^\"]+)\"\s+->\s+\"([^\"]+)\"" line)]
    {:source source
     :target target}))

(defn- dot-fixture-graph
  [name]
  (let [lines (str/split-lines (slurp (fixture-resource name)))
        node-entries (keep parse-dot-node-line lines)
        edge-entries (keep parse-dot-edge-line lines)
        value-raw-ids (into #{}
                            (map :raw-id)
                            (filter #(= :value (:kind %)) node-entries))
        op-raw-ids (into #{}
                         (map :raw-id)
                         (filter #(= :operator (:kind %)) node-entries))
        op-shapes (reduce (fn [shapes {:keys [source target]}]
                            (cond
                              (and (contains? value-raw-ids source)
                                   (contains? op-raw-ids target))
                              (assoc-in shapes [target :parent] (dot-id source))

                              (and (contains? op-raw-ids source)
                                   (contains? value-raw-ids target))
                              (update-in shapes [source :children]
                                         (fnil conj [])
                                         (dot-id target))

                              :else shapes))
                          {}
                          edge-entries)
        g (reduce (fn [g {:keys [id value]}]
                    (graph/add-value g id value))
                  (graph/empty-graph)
                  (filter #(= :value (:kind %)) node-entries))
        g (reduce (fn [g {:keys [raw-id id operator]}]
                    (let [{:keys [parent children]} (get op-shapes raw-id)]
                      (graph/add-operator g id operator parent children)))
                  g
                  (filter #(= :operator (:kind %)) node-entries))
        roots (into []
                    (comp (filter :root?)
                          (map :id))
                    node-entries)]
    (graph/set-roots g roots)))

(defn- selected-operator-kinds
  [g description]
  (mapv #(get-in (graph/node g %) [:operator :id])
        (:selected description)))

(defn- selected-root-expressions
  [g]
  (mapv #(sut/selected-expression g %)
        (graph/roots g)))

(deftest python-bottleneck-dot-golden-cases
  (doseq [{:keys [name expected-dl expected-selected min-clone]}
          [{:name "mult_negate"
            :expected-dl 8760.879357497288
            :expected-selected []}
           {:name "mult_negate_add"
            :expected-dl 573.8697633677614
            :expected-selected [:add :negate :mult]
            :min-clone "mult_negate_add_min_clone"}
           {:name "regression"
            :expected-dl 19582.338006270023
            :expected-selected [:add :add :add :add]
            :min-clone "regression_min_clone"}]]
    (testing name
      (let [g (dot-fixture-graph name)
            description (sut/graph-description g)]
        (is (approx= expected-dl (:dl description)))
        (is (= expected-selected
               (selected-operator-kinds g description)))
        (when min-clone
          (is (= (selected-root-expressions (dot-fixture-graph min-clone))
                 (selected-root-expressions g))))))))
