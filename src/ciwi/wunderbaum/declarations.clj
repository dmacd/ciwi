(ns ciwi.wunderbaum.declarations
  (:require [ciwi.delayed-builder :as delayed]
            [ciwi.enumerator :as enumerator]
            [ciwi.operator :as op]))

(defn require-registry
  [registry]
  (when-not (map? registry)
    (throw (ex-info "Wunderbaum requires an injected operator registry"
                    {:registry registry})))
  registry)

(defn- resolve-operator
  [registry operator]
  (cond
    (op/operator? operator) operator
    (keyword? operator) (get registry operator)
    :else nil))

(defn generalized-conditions
  "Return Python-Wunderbaum-style generalized conditions for an operator arity.

  `-1` denotes the operator output. Nonnegative entries denote conditioned child
  positions. Inverse attachments are `[-1 ...conditioned-inputs]`; the forward
  attachment is all child positions."
  [conditions arity]
  (let [conditions (or conditions [])]
    (vec
     (concat
      (for [condition conditions
            :let [condition (vec condition)]
            :when (< (count condition) arity)]
        (into [-1] condition))
      [(vec (range arity))]))))

(defn- normalize-op-count
  [op-count]
  (cond
    (map? op-count)
    op-count

    (and (vector? op-count) (= 2 (count op-count)))
    {:op (first op-count)
     :count (second op-count)}

    (and (vector? op-count) (= 3 (count op-count)))
    (assoc (nth op-count 2)
           :op (first op-count)
           :count (second op-count))

    :else
    (throw (ex-info "Expected Wunderbaum operator/count declaration"
                    {:op-count op-count}))))

(defn- normalize-declaration
  [registry op-count]
  (let [{operator :op
         op-count-value :count
         :keys [arity input-specs output-spec conditions dl id jitter]
         :as declaration} (normalize-op-count op-count)
        op-count-value (or op-count-value 0)
        runtime-op (resolve-operator registry operator)
        input-specs (vec input-specs)
        arity (or arity (count input-specs))
        conditions (or conditions (:conditions runtime-op))]
    (when-not runtime-op
      (throw (ex-info "Unknown Wunderbaum operator" {:operator operator})))
    (when-not (seq input-specs)
      (throw (ex-info "Wunderbaum operator declaration requires :input-specs"
                      {:declaration declaration})))
    (when-not output-spec
      (throw (ex-info "Wunderbaum operator declaration requires :output-spec"
                      {:declaration declaration})))
    (when-not (= arity (count input-specs))
      (throw (ex-info "Wunderbaum operator arity must match :input-specs"
                      {:arity arity
                       :input-specs input-specs
                       :declaration declaration})))
    {:id (or id (:id runtime-op))
     :operator runtime-op
     :arity arity
     :input-specs input-specs
     :output-spec output-spec
     :conditions (vec conditions)
     :count op-count-value
     :dl (or dl (:dl runtime-op))
     :jitter (double (or jitter 0.0))}))

(defn- condition-key
  [{:keys [input-specs output-spec]} gen-cond]
  (mapv (fn [position]
          (if (= -1 position)
            output-spec
            (nth input-specs position)))
        gen-cond))

(defn operator-elements-by-condition-key
  "Index operator elements by the specs of their conditioned attachment nodes."
  [registry ops-with-counts]
  (let [registry (require-registry registry)
        declarations (mapv #(normalize-declaration registry %) ops-with-counts)
        total-count (reduce + 0.0 (map :count declarations))]
    (reduce
     (fn [elements declaration]
       (let [effective-dl (enumerator/effective-dl (:dl declaration)
                                                   (:count declaration)
                                                   total-count
                                                   enumerator/default-concentration
                                                   (:jitter declaration))]
         (reduce
          (fn [elements gen-cond]
            (let [k (condition-key declaration gen-cond)
                  element (delayed/graph-element
                           (:operator declaration)
                           gen-cond
                           {:arity (:arity declaration)
                            :input-specs (:input-specs declaration)
                            :output-spec (:output-spec declaration)
                            :dl effective-dl
                            :id (:id declaration)})]
              (update elements k (fnil conj []) element)))
          elements
          (generalized-conditions (:conditions declaration)
                                  (:arity declaration)))))
     {}
     declarations)))
