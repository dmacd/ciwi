(ns ciwi.library
  (:refer-clojure :exclude [load-file])
  (:require [ciwi.composite :as composite]
            [ciwi.operator :as op]
            [ciwi.rewrite :as rewrite]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(def linear-sequence-composite-def
  {:kind :composite
   :id :linear-sequence
   :expr [:add [:mult [:brange [:input :range-start 0]
                       [:input :n 1]]
                [:input :step 1]]
          [:input :start 0]]
   :metadata {:origin :builtin}})

(def linear-sequence-template-def
  {:kind :rewrite-template
   :id :linear-sequence
   :operator :linear-sequence
   :matcher {:kind :affine-sequence
             :exclude-arithmetic-range? true}
   :children [:range-start :n :step :start]
   :reason :linear-sequence
   :metadata {:origin :builtin}})

(def builtin-definitions
  [linear-sequence-composite-def
   linear-sequence-template-def])

(defn- definition-seq
  [defs]
  (cond
    (and (map? defs) (:definitions defs)) (:definitions defs)
    (sequential? defs) defs
    :else [defs]))

(defn- arithmetic-range?
  [xs]
  (and (vector? xs)
       (seq xs)
       (every? integer? xs)
       (= xs (vec (range (first xs) (+ (first xs) (count xs)))))))

(defn- affine-sequence
  [xs]
  (when (and (vector? xs)
             (>= (count xs) 3)
             (every? number? xs))
    (let [start (first xs)
          step (- (second xs) start)
          n (count xs)]
      (when (= xs (mapv #(+ start (* step %)) (range n)))
        {:range-start 0
         :start start
         :step step
         :n n
         :base (vec (range n))
         :scaled (mapv #(* step %) (range n))}))))

(defn- square-range
  [xs]
  (when (and (vector? xs)
             (>= (count xs) 3)
             (every? number? xs)
             (= xs (mapv #(* % %) (range (count xs)))))
    {:n (count xs)}))

(defmulti match
  "Return matcher bindings for data, or nil when the matcher does not apply."
  (fn [matcher _data]
    (:kind matcher)))

(defmethod match :affine-sequence
  [matcher data]
  (when-let [bindings (affine-sequence data)]
    (when-not (and (:exclude-arithmetic-range? matcher)
                   (arithmetic-range? data))
      bindings)))

(defmethod match :square-range
  [_matcher data]
  (square-range data))

(defmethod match :default
  [matcher _data]
  (throw (ex-info "Unknown library matcher" {:matcher matcher})))

(defn- resolve-child
  [bindings child]
  (cond
    (and (vector? child) (= :binding (first child)))
    (get bindings (second child))

    (and (map? child) (:binding child))
    (get bindings (:binding child))

    (contains? bindings child)
    (get bindings child)

    :else child))

(defn- hydrate-composite
  [operators {:keys [id expr dl constant-indices metadata] :as definition}]
  (when-not id
    (throw (ex-info "Composite definition requires :id" {:definition definition})))
  (when-not expr
    (throw (ex-info "Composite definition requires :expr" {:definition definition})))
  (composite/operator id expr {:registry operators
                               :dl (or dl 1.0)
                               :constant-indices (or constant-indices #{})
                               :metadata metadata}))

(defn- hydrate-template
  [operators {:keys [id operator matcher children reason] :as definition}]
  (when-not id
    (throw (ex-info "Rewrite template definition requires :id" {:definition definition})))
  (when-not operator
    (throw (ex-info "Rewrite template definition requires :operator" {:definition definition})))
  (when-not matcher
    (throw (ex-info "Rewrite template definition requires :matcher" {:definition definition})))
  (let [runtime-op (get operators operator)]
    (when-not runtime-op
      (throw (ex-info "Unknown rewrite template operator"
                      {:operator operator
                       :known-operators (keys operators)})))
    (rewrite/value-template
     id
     (fn [g node-id data]
       (when-let [bindings (match matcher data)]
         (rewrite/candidate g
                            node-id
                            runtime-op
                            (mapv #(resolve-child bindings %) children)
                            (or reason id)))))))

(defn- load-one
  [state definition]
  (case (:kind definition)
    :composite
    (let [runtime-op (hydrate-composite (:operators state) definition)]
      (-> state
          (assoc-in [:operators (:id definition)] runtime-op)
          (update :definitions conj definition)))

    :rewrite-template
    (let [template (hydrate-template (:operators state) definition)]
      (-> state
          (update :templates conj template)
          (update :definitions conj definition)))

    (throw (ex-info "Unknown library definition kind" {:definition definition}))))

(defn load-definitions
  "Hydrate durable library definitions into runtime operators and templates."
  ([defs]
   (load-definitions defs {}))
  ([defs {:keys [operators]
          :or {operators op/registry}}]
   (reduce load-one
           {:operators operators
            :templates []
            :definitions []}
           (definition-seq defs))))

(defn read-definitions
  [path]
  (edn/read-string (slurp (io/file path))))

(defn load-file
  [path]
  (load-definitions (read-definitions path)))

(defn write-definitions!
  [path defs]
  (spit (io/file path)
        (with-out-str
          (pprint/pprint {:definitions (vec (definition-seq defs))})))
  path)
