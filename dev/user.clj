(ns user
  (:require [ciwi.core :as ciwi]))

(defn reset
  []
  (ciwi/ready?))

