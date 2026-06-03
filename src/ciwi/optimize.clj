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

(defn- float-diff-step
  [x]
  (max 1.0e-6 (* (abs (double x)) 1.0e-3)))

(defn- finite-diff-axis
  [search objective x fx i]
  (if (nth (:int-mask search) i)
    (let [xp (update x i inc)
          xm (update x i dec)
          fp (:score (cached-eval search objective xp))
          fm (:score (cached-eval search objective xm))]
      {:g (cond
            (and (finite? fp) (finite? fm)) (* 0.5 (- fp fm))
            (finite? fp) (- fp fx)
            (finite? fm) (- fx fm)
            :else 0.0)
       :hdiag (when (and (finite? fp) (finite? fm))
                (- (+ fp fm) (* 2.0 fx)))})
    (loop [h (float-diff-step (nth x i))]
      (let [xi (double (nth x i))
            xp (assoc x i (+ xi h))
            xm (assoc x i (- xi h))
            fp (:score (cached-eval search objective xp))
            fm (:score (cached-eval search objective xm))]
        (cond
          (and (< h 1.0)
               (or (and (not (finite? fp)) (not (finite? fm)))
                   (and (finite? fp) (finite? fm) (= fp fm fx))
                   (and (finite? fp) (= fp fx) (not (finite? fm)))
                   (and (finite? fm) (= fm fx) (not (finite? fp)))))
          (recur (* 10.0 h))

          :else
          {:g (cond
                (and (finite? fp) (finite? fm)) (/ (- fp fm) (* 2.0 h))
                (finite? fp) (/ (- fp fx) h)
                (finite? fm) (/ (- fx fm) h)
                :else 0.0)
           :hdiag (when (and (finite? fp) (finite? fm))
                    (/ (- (+ fp fm) (* 2.0 fx)) (* h h)))})))))

(defn finite-diffs
  [search objective state]
  (let [x (:x state)
        fx (:score state)
        dims (count x)]
    (reduce (fn [{:keys [g hdiag]} i]
              (let [{gi :g hi :hdiag} (finite-diff-axis search objective x fx i)]
                {:g (assoc g i gi)
                 :hdiag (assoc hdiag i hi)}))
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

(defn- pattern-step
  [search x i direction]
  (let [step (if (nth (:int-mask search) i)
               1.0
               (float-diff-step (nth x i)))]
    (update x i + (* direction step))))

(defn- pattern-search
  [search objective state]
  (reduce (fn [best [i d]]
            (let [x (pattern-step search (:x best) i d)
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

(defrecord AdaptiveGridSearch [int-mask n-points grow shrink max-iters jointly-optimize?])

(defn adaptive-grid-search
  [{:keys [int-mask n-points grow shrink max-iters jointly-optimize?]
    :or {n-points 21
         grow 2.0
         shrink 0.5
         max-iters 20
         jointly-optimize? false}}]
  (->AdaptiveGridSearch (vec int-mask) n-points grow shrink max-iters jointly-optimize?))

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

(defn- initial-scale
  [x int-mask]
  (mapv (fn [v int?]
          (let [s (+ (* (abs v) 0.5) 1.0)]
            (if int? (max 1.0 (Math/rint s)) s)))
        x
        int-mask))

(defn- normalize-scale
  [scale int-mask]
  (mapv (fn [s int?]
          (if int?
            (max 1.0 (Math/rint s))
            s))
        scale
        int-mask))

(defn- adaptive-candidate
  [search objective x]
  (candidate-state (newton-search {:int-mask (:int-mask search)}) objective x))

(defn- axis-search
  [search objective state dim values]
  (let [f-init (:score state)]
    (reduce (fn [{:keys [best all-equal?]} v]
              (let [candidate (adaptive-candidate search objective (assoc (:x state) dim v))
                    score (:score candidate)
                    all-equal? (and all-equal?
                                    (or (not (finite? score))
                                        (= score f-init)))]
                {:best (better-state best candidate)
                 :all-equal? all-equal?}))
            {:best state
             :all-equal? true}
            values)))

(defn- product
  [xss]
  (if (empty? xss)
    [[]]
    (for [x (first xss)
          tail (product (rest xss))]
      (into [x] tail))))

(defn- update-joint-scale
  [search scale axes x]
  (-> (mapv (fn [s axis v]
              (* s (if (some #{v} [(first axis) (last axis)])
                     (:grow search)
                     (:shrink search))))
            scale
            axes
            x)
      (normalize-scale (:int-mask search))))

(defn- joint-sample
  [search objective state scale]
  (let [axes (mapv (fn [center span int?]
                     (axis center span (:n-points search) int?))
                   (:x state)
                   scale
                   (:int-mask search))]
    (some (fn [values]
            (let [candidate (adaptive-candidate search objective (mapv double values))]
              (when (and candidate
                         (finite? (:score candidate))
                         (< (:score candidate) (:score state)))
                (assoc candidate :scale (update-joint-scale search
                                                            scale
                                                            axes
                                                            (:x candidate))))))
          (product axes))))

(extend-type AdaptiveGridSearch
  SearchOperator
  (search-step [this objective state]
    (let [scale (or (:scale state)
                    (initial-scale (:x state) (:int-mask this)))]
      (loop [dim 0
             best state
             scale scale
             modified? false]
        (if (= dim (count (:x state)))
          (let [scale (normalize-scale scale (:int-mask this))]
            (if modified?
              (assoc best :scale scale)
              (or (when (:jointly-optimize? this)
                    (joint-sample this objective state scale))
                  (when (not= scale (:scale state))
                    (assoc state :scale scale)))))
          (let [values (axis (nth (:x best) dim)
                             (nth scale dim)
                             (:n-points this)
                             (nth (:int-mask this) dim))
                before-score (:score best)
                {:keys [best all-equal?]} (axis-search this objective best dim values)
                improved? (< (:score best) before-score)
                best-v (nth (:x best) dim)
                scale-factor (if improved?
                               (if (some #{best-v} [(first values) (last values)])
                                 (:grow this)
                                 (:shrink this))
                               (if all-equal? (:grow this) (:shrink this)))
                scale (assoc scale dim (* (nth scale dim) scale-factor))]
            (recur (inc dim) best scale (or modified? improved?))))))))
