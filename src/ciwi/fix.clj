(ns ciwi.fix
  (:require [ciwi.conditions :as conditions]
            [ciwi.operator :as op]
            [ciwi.value :as value]
            [clojure.string :as str]))

(defn- id-fragment
  [x]
  (-> (pr-str x)
      (str/replace #"[^A-Za-z0-9_.-]+" "_")
      (str/replace #"^_+|_+$" "")))

(defn- default-id
  [fixed-data operator]
  (keyword "fix" (str (name (:id operator)) "_" (id-fragment fixed-data))))

(defn- condition-key
  [condition]
  (vec (sort (conditions/normalize-condition condition))))

(defn- runtime-condition
  [condition]
  (when (some zero? condition)
    (->> condition
         (remove zero?)
         (mapv dec)
         conditions/normalize-condition)))

(defn- fixed-condition-entries
  [operator]
  (loop [conditions (:conditions operator)
         seen #{}
         entries []]
    (if-let [condition (first conditions)]
      (let [condition (conditions/normalize-condition condition)
            runtime (runtime-condition condition)
            key (some-> runtime condition-key)]
        (if (or (nil? runtime) (contains? seen key))
          (recur (rest conditions) seen entries)
          (recur (rest conditions)
                 (conj seen key)
                 (conj entries {:key key
                                :condition runtime
                                :original-condition condition}))))
      entries)))

(defn- original-condition-inputs
  [fixed-data original-condition cond cond-inputs]
  (let [runtime-inputs (zipmap (conditions/normalize-condition cond) cond-inputs)]
    (when (every? (fn [idx]
                    (or (zero? idx)
                        (contains? runtime-inputs (dec idx))))
                  original-condition)
      (mapv (fn [idx]
              (if (zero? idx)
                fixed-data
                (get runtime-inputs (dec idx))))
            original-condition))))

(defn fix-first
  "Return an Operator with the first input of `operator` captured as `fixed`.

  `fixed` may be a raw datum or a ciwi.value/Value. The captured value is charged
  in the returned operator's DL because it lives in the operator closure rather
  than as an ordinary child node.
  "
  ([fixed operator]
   (fix-first fixed operator {}))
  ([fixed operator {:keys [id dl]}]
   (when-not (op/operator? operator)
     (throw (ex-info "fix-first requires a ciwi.operator/Operator"
                     {:operator operator})))
   (let [fixed-data (value/datum fixed)
         condition-entries (fixed-condition-entries operator)
         condition-by-key (into {} (map (juxt :key identity) condition-entries))]
     (op/operator
      {:id (or id (default-id fixed-data operator))
       :conditions (mapv :condition condition-entries)
       :commutative? false
       :dl (double (or dl (+ (:dl operator)
                             (value/desc-len (value/value fixed-data)))))
       :call (fn [inputs]
               ((:call operator) (into [fixed-data] inputs)))
       :inverse (fn [output cond-inputs cond]
                  (when-let [{:keys [original-condition]} (get condition-by-key
                                                               (condition-key cond))]
                    (when-let [original-inputs (original-condition-inputs fixed-data
                                                                          original-condition
                                                                          cond
                                                                          cond-inputs)]
                      ((:inverse operator) output original-inputs original-condition))))}))))

(def operator
  (op/operator
   {:id :fix
    :conditions [[0 1]]
    :commutative? false
    :call (fn [[fixed operator]]
            (fix-first fixed operator))}))
