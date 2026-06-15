(ns ciwi.search.trace)

(defn enabled?
  "Return true when observer tracing is active for a search opts map."
  [opts]
  (boolean (:observer opts)))

(defn- sample-rate
  [opts event]
  (long (or (get-in opts [:observer-sample-rates event])
            (:observer-sample-rate opts)
            1)))

(defn sampled?
  "Return true when an observer event should be emitted for a 1-based index."
  [opts event sample-index]
  (let [rate (sample-rate opts event)]
    (and (pos? rate)
         (zero? (mod (dec (long (max 1 sample-index))) rate)))))

(defn emit!
  "Emit a sampled observer event when `:observer` is present in opts."
  [opts event payload sample-index]
  (when-let [observer (:observer opts)]
    (when (sampled? opts event sample-index)
      (observer (assoc payload :event event)))))
