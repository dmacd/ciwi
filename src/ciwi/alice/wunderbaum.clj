(ns ciwi.alice.wunderbaum
  (:require [ciwi.alice.declarations :as alice-declarations]
            [ciwi.alice.wunderbaum.context :as context]
            [ciwi.alice.wunderbaum.greedy :as greedy]))

(def operator-declarations
  alice-declarations/operator-declarations)

(def python-dl-jitter
  alice-declarations/python-dl-jitter)

(def declarations-for-registry
  context/declarations-for-registry)

(def leaf-selection-policies
  greedy/leaf-selection-policies)

(def run-greedy-task
  greedy/run-greedy-task)

(def run-compression-step
  greedy/run-compression-step)

(def compression-step-candidate
  greedy/compression-step-candidate)
