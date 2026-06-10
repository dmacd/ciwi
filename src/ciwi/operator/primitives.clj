(ns ciwi.operator.primitives
  (:refer-clojure :exclude [concat name repeat])
  (:require [ciwi.operator.core :as core]
            [ciwi.operator.mapping :as mapping]
            [ciwi.operator.numeric :as numeric]
            [ciwi.operator.sequence :as sequence]))

(def operator core/operator)
(def operator? core/operator?)
(def apply-op core/apply-op)
(def invert-op core/invert-op)

(def repeated-motif sequence/repeated-motif)
(def partition-by-frequency sequence/partition-by-frequency)

(def add numeric/add)
(def sub numeric/sub)
(def mult numeric/mult)
(def dot numeric/dot)
(def negate numeric/negate)
(def lessthan numeric/lessthan)
(def equal numeric/equal)
(def logical-not numeric/logical-not)
(def logical-and numeric/logical-and)
(def logical-or numeric/logical-or)
(def len numeric/len)
(def brange numeric/brange)
(def trange numeric/trange)
(def mean numeric/mean)
(def cumsum numeric/cumsum)

(declare registry)

(def map-op
  (operator
   {:id :map
    :conditions [[0]]
    :call (fn [[f xs]]
            (or (mapping/map-call registry f xs)
                (throw (ex-info "map expects an operator keyword/record and a sequence"
                                {:f f :xs xs}))))
    :inverse (fn [output cond-inputs cond]
               (mapping/map-inversions registry output cond-inputs cond))}))

(def repeat sequence/repeat)
(def insert sequence/insert)
(def concat sequence/concat)
(def getitem sequence/getitem)
(def setitem sequence/setitem)

(def registry
  {:add add
   :sub sub
   :mult mult
   :dot dot
   :negate negate
   :lessthan lessthan
   :equal equal
   :not logical-not
   :and logical-and
   :or logical-or
   :len len
   :brange brange
   :trange trange
   :mean mean
   :repeat repeat
   :map map-op
   :insert insert
   :cumsum cumsum
   :concat concat
   :getitem getitem
   :setitem setitem})
