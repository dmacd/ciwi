(ns ciwi.optimize)

(defprotocol SearchOperator
  (search-step [this objective state]
    "Return a new search state, or nil when no improvement is available."))

(defn objective-value
  [objective x]
  (let [result (objective x)]
    (if (map? result)
      result
      {:score (first result)
       :params (second result)})))

(defn finite?
  [x]
  (and (number? x) (not (Double/isNaN (double x))) (not (Double/isInfinite (double x)))))

(defn round-int-dims
  [x int-mask]
  (mapv (fn [v int?]
          (if int?
            (double (Math/rint (double v)))
            (double v)))
        x
        int-mask))

(defn make-state
  [x score params]
  {:x (mapv double x)
   :score score
   :params params})

(defn better-state
  [state candidate]
  (if (and candidate
           (finite? (:score candidate))
           (< (:score candidate) (:score state)))
    candidate
    state))

(defrecord NewtonSearch [int-mask max-iters newton-step-cap backtrack-max cache evals])

(defn newton-search
  [{:keys [int-mask max-iters newton-step-cap backtrack-max]
    :or {max-iters 1000000
         newton-step-cap 100
         backtrack-max 4}}]
  (->NewtonSearch (vec int-mask)
                  max-iters
                  newton-step-cap
                  backtrack-max
                  (atom {})
                  (atom 0)))

(defn- cached-eval
  [search objective x]
  (let [x (round-int-dims x (:int-mask search))
        k x]
    (if (contains? @(:cache search) k)
      (get @(:cache search) k)
      (let [result (try
                     (objective-value objective x)
                     (catch Exception _ {:score nil :params nil}))
            result (if (finite? (:score result)) result {:score nil :params nil})]
        (swap! (:evals search) inc)
        (swap! (:cache search) assoc k result)
        result))))

(defn finite-diffs
  [search objective state]
  (let [x (:x state)
        fx (:score state)
        dims (count x)]
    (reduce (fn [{:keys [g hdiag]} i]
              (let [xp (update x i inc)
                    xm (update x i dec)
                    fp (:score (cached-eval search objective xp))
                    fm (:score (cached-eval search objective xm))]
                {:g (assoc g i (cond
                                  (and (finite? fp) (finite? fm)) (* 0.5 (- fp fm))
                                  (finite? fp) (- fp fx)
                                  (finite? fm) (- fx fm)
                                  :else 0.0))
                 :hdiag (assoc hdiag i (when (and (finite? fp) (finite? fm))
                                         (- fp (* 2.0 fx) fm)))}))
            {:g (vec (repeat dims 0.0))
             :hdiag (vec (repeat dims nil))}
            (range dims))))

(defn propose-newton-step
  [search g hdiag]
  (let [step (mapv (fn [gi hi]
                     (if (and hi (not (zero? hi)))
                       (max (- (:newton-step-cap search))
                            (min (:newton-step-cap search) (/ (- gi) hi)))
                       0.0))
                   g
                   hdiag)]
    (if (some #(not (zero? %)) step)
      step
      (let [idx (first (last (sort-by (comp abs second) (map-indexed vector g))))]
        (if (and idx (not (zero? (nth g idx))))
          (assoc step idx (if (pos? (nth g idx)) -1.0 1.0))
          step)))))

(defn- candidate-state
  [search objective x]
  (let [result (cached-eval search objective x)]
    (when (finite? (:score result))
      (make-state x (:score result) (:params result)))))

(defn- backtrack-step
  [search objective state step]
  (loop [s step
         attempts 0]
    (when (and (< attempts (:backtrack-max search))
               (some #(not (zero? %)) s))
      (let [x-new (round-int-dims (mapv + (:x state) s) (:int-mask search))
            candidate (candidate-state search objective x-new)]
        (if (and candidate (< (:score candidate) (:score state)))
          candidate
          (recur (mapv (fn [v int?]
                         (if int?
                           (- v (Math/signum (double v)))
                           (* 0.5 v)))
                       s
                       (:int-mask search))
                 (inc attempts)))))))

(defn- pattern-search
  [search objective state]
  (reduce (fn [best [i d]]
            (let [x (update (:x best) i + d)
                  candidate (candidate-state search objective x)]
              (better-state best candidate)))
          state
          (for [i (range (count (:x state)))
                d [-1.0 1.0]]
            [i d])))

(extend-type NewtonSearch
  SearchOperator
  (search-step [this objective state]
    (let [{:keys [g hdiag]} (finite-diffs this objective state)
          step (propose-newton-step this g hdiag)]
      (or (backtrack-step this objective state step)
          (let [candidate (pattern-search this objective state)]
            (when (< (:score candidate) (:score state))
              candidate))))))

(defn optimize
  [search objective x0 score0 params0]
  (loop [state (make-state x0 score0 params0)
         iter 0]
    (if (>= iter (:max-iters search))
      state
      (if-let [next-state (search-step search objective state)]
        (recur next-state (inc iter))
        state))))

(defrecord AdaptiveGridSearch [int-mask n-points grow shrink max-iters])

(defn adaptive-grid-search
  [{:keys [int-mask n-points grow shrink max-iters]
    :or {n-points 21
         grow 2.0
         shrink 0.5
         max-iters 20}}]
  (->AdaptiveGridSearch (vec int-mask) n-points grow shrink max-iters))

(defn- linspace
  [lo hi n]
  (if (= n 1)
    [lo]
    (mapv #(+ lo (* % (/ (- hi lo) (dec n)))) (range n))))

(defn- axis
  [center span n int?]
  (let [xs (linspace (- center span) (+ center span) n)]
    (if int?
      (vec (distinct (map #(double (long %)) xs)))
      xs)))

(extend-type AdaptiveGridSearch
  SearchOperator
  (search-step [this objective state]
    (let [scale (mapv (fn [x int?]
                        (let [s (+ (* (abs x) 0.5) 1.0)]
                          (if int? (max 1.0 (Math/rint s)) s)))
                      (:x state)
                      (:int-mask this))]
      (loop [i 0
             best state]
        (if (= i (count (:x state)))
          (when (< (:score best) (:score state)) best)
          (let [values (axis (nth (:x best) i) (nth scale i) (:n-points this) (nth (:int-mask this) i))
                best' (reduce (fn [acc v]
                                (let [x (assoc (:x acc) i v)
                                      candidate (candidate-state (newton-search {:int-mask (:int-mask this)}) objective x)]
                                  (better-state acc candidate)))
                              best
                              values)]
            (recur (inc i) best')))))))
