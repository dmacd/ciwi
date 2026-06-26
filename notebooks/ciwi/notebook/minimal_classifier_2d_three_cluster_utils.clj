(ns ciwi.notebook.minimal-classifier-2d-three-cluster-utils
  (:require [ciwi.alice :as alice]
            [ciwi.alice.wunderbaum :as alice-wunderbaum]
            [ciwi.dsl :as dsl]
            [ciwi.graph :as graph]
            [ciwi.operator :as op]
            [ciwi.render.graph :as render-graph]
            [ciwi.value :as value]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files Paths]
           [java.util Base64 Random]
           [java.util.concurrent Callable ExecutorCompletionService Executors TimeUnit]))

;; Notebook-only support for
;; minimal_classifier_2d_three_cluster_onehot_sweep.ipynb.

(def n-classes 3)
(def cluster-radius 1.0)
(def circle-phase (Math/toRadians 37.0))
(def round-decimals 4)
(def anisotropic-class 0)
(def anisotropic-major-scale 9.0)
(def anisotropic-minor-scale 0.16)

(def default-sweep-opts
  {:separations [4.0 8.0 16.0]
   :n-train-per-class-grid [64 128]
   :n-test-per-class 5
   :n-repeats 1
   :max-test-points nil
   :seed 0
   :free-value-mode "zero"
   :store-graphs? true
   :num-workers 4
   :outer-workers 8
   :threshold-rate 1.0
   :min-compression-rate 0.01
   :max-dag-dl 25
   :tie-tol 1.0e-9
   :progress? true
   :skip-cached? true})

(def ^:private bytes-per-mib 1048576.0)

(def ^:private heap-row-keys
  [:heap-used-mib :heap-total-mib :heap-free-mib :heap-max-mib])

(defn heap-snapshot
  "Return a Runtime heap snapshot in bytes and MiB."
  []
  (let [rt (Runtime/getRuntime)
        total (.totalMemory rt)
        free (.freeMemory rt)
        max-memory (.maxMemory rt)
        used (- total free)]
    {:heap-used-bytes used
     :heap-total-bytes total
     :heap-free-bytes free
     :heap-max-bytes max-memory
     :heap-used-mib (/ (double used) bytes-per-mib)
     :heap-total-mib (/ (double total) bytes-per-mib)
     :heap-free-mib (/ (double free) bytes-per-mib)
     :heap-max-mib (/ (double max-memory) bytes-per-mib)}))

(defn- capture-heap
  []
  (try
    (heap-snapshot)
    (catch Throwable _
      nil)))

(defn heap-summary
  [heap]
  (when heap
    (format "%.1f MiB used / %.1f MiB total / %.1f MiB max"
            (double (or (:heap-used-mib heap) Double/NaN))
            (double (or (:heap-total-mib heap) Double/NaN))
            (double (or (:heap-max-mib heap) Double/NaN)))))

(defn- heap-row-fields
  [heap-start heap-observed heap-finish]
  (cond-> {}
    heap-start
    (assoc :heap-start-used-mib (:heap-used-mib heap-start))

    heap-observed
    (assoc :heap-observed-used-mib (:heap-used-mib heap-observed)
           :heap-observed-event (some-> (:search-event heap-observed) name))

    heap-finish
    (merge (select-keys heap-finish heap-row-keys))))

(defn- candidate-heap-observer
  [last-search-heap]
  (fn [event]
    (when-let [heap (capture-heap)]
      (vreset! last-search-heap
               (assoc heap
                      :search-event (:event event)
                      :search-frontier-order (:frontier-order event)
                      :search-step-index (:step-index event))))))

(defmacro cell
  "Run a whole REPL notebook cell when this form is evaluated.

  Put `(cell ...)` inside `(comment ...)` so namespace loading stays inert while
  evaluating the `cell` form in Cursive returns the cell output inline."
  [& body]
  `(do ~@body))

(def classifier-operator-ids
  [:setitem :lessthan :not :and :or :getitem :add :mult :sub :negate])

(def classifier-registry
  (select-keys op/registry classifier-operator-ids))

(def classifier-operator-dl
  (Math/ceil (value/jelias (count classifier-operator-ids))))

(defn- declaration
  [operator input-specs output-spec]
  {:op operator
   :input-specs (vec input-specs)
   :output-spec output-spec
   :count 0
   :dl classifier-operator-dl})

(def classifier-ops-with-counts
  [(declaration :setitem [:array-bool :array-bool :array-bool] :array-bool)
   (declaration :setitem [:array-bool :array-int :array-bool] :array-bool)
   (declaration :setitem [:array-float :array-bool :array-float] :array-float)
   (declaration :setitem [:array-float :array-int :array-float] :array-float)

   (declaration :lessthan [:float :float] :bool)
   (declaration :lessthan [:int :int] :bool)
   (declaration :lessthan [:array-float :float] :array-bool)
   (declaration :lessthan [:array-float :int] :array-bool)
   (declaration :lessthan [:array-float :array-float] :array-bool)

   (declaration :not [:bool] :bool)
   (declaration :not [:array-bool] :array-bool)
   ;; Python's And/Or specs are scalar bool only; keep the notebook basis aligned.
   (declaration :and [:bool :bool] :bool)
   (declaration :or [:bool :bool] :bool)

   (declaration :getitem [:array-float :array-bool] :array-float)
   (declaration :getitem [:array-float :array-int] :array-float)
   (declaration :getitem [:array-bool :array-bool] :array-bool)
   (declaration :getitem [:array-bool :array-int] :array-bool)
   (declaration :getitem [:array :int] :unknown)
   (declaration :getitem [:array :array-int] :array)

   (declaration :add [:float :float] :float)
   (declaration :add [:array-float :float] :array-float)
   (declaration :add [:array-float :array-float] :array-float)
   (declaration :add [:int :int] :int)
   (declaration :add [:array-int :int] :array-int)
   (declaration :add [:array-int :array-int] :array-int)

   (declaration :mult [:float :float] :float)
   (declaration :mult [:array-float :float] :array-float)
   (declaration :mult [:array-float :array-float] :array-float)
   (declaration :mult [:int :int] :int)
   (declaration :mult [:array-int :int] :array-int)
   (declaration :mult [:array-int :array-int] :array-int)

   (declaration :sub [:int :float] :float)
   (declaration :sub [:int :int] :int)
   (declaration :sub [:float :float] :float)
   (declaration :sub [:array-int :array-int] :array-int)
   (declaration :sub [:array-float :array-float] :array-float)
   (declaration :sub [:array-float :int] :array-float)
   (declaration :sub [:array-float :float] :array-float)

   (declaration :negate [:int] :int)
   (declaration :negate [:float] :float)
   (declaration :negate [:array-int] :array-int)
   (declaration :negate [:array-float] :array-float)])

(defn cluster-variance
  ([separation]
   (cluster-variance separation {}))
  ([separation {:keys [radius]
                :or {radius cluster-radius}}]
   (let [separation (double separation)]
     (when-not (pos? separation)
       (throw (ex-info "separation must be positive" {:separation separation})))
     (/ (double radius) separation))))

(defn cluster-std
  ([separation]
   (cluster-std separation {}))
  ([separation opts]
   (Math/sqrt (cluster-variance separation opts))))

(defn unit-circle-directions
  ([] (unit-circle-directions {}))
  ([{:keys [phase n-classes]
     :or {phase circle-phase
          n-classes n-classes}}]
   (mapv (fn [class-index]
           (let [angle (+ (double phase)
                          (/ (* 2.0 Math/PI class-index)
                             (double n-classes)))]
             [(Math/cos angle) (Math/sin angle)]))
         (range n-classes))))

(defn cluster-centers
  ([] (cluster-centers nil))
  ([separation]
   (cluster-centers separation {}))
  ([_separation {:keys [radius phase]
                 :or {radius cluster-radius
                      phase circle-phase}}]
   (mapv (fn [[x y]]
           [(* (double radius) x) (* (double radius) y)])
         (unit-circle-directions {:phase phase :n-classes n-classes}))))

(defn cluster-angles
  ([] (cluster-angles {}))
  ([{:keys [phase n-classes]
     :or {phase circle-phase
          n-classes n-classes}}]
   (mapv (fn [class-index]
           (+ (double phase)
              (/ (* 2.0 Math/PI class-index)
                 (double n-classes))))
         (range n-classes))))

(defn cluster-covariances
  ([separation]
   (cluster-covariances separation {}))
  ([separation {:keys [radius phase anisotropic-class]
                :or {radius cluster-radius
                     phase circle-phase
                     anisotropic-class anisotropic-class}}]
   (let [base (cluster-variance separation {:radius radius})
         angle (nth (cluster-angles {:phase phase :n-classes n-classes})
                    (int anisotropic-class))
         c (Math/cos angle)
         s (Math/sin angle)
         minor anisotropic-minor-scale
         major anisotropic-major-scale
         aniso [(* base (+ (* minor c c) (* major s s)))
                (* base (- minor major) c s)
                (* base (+ (* minor s s) (* major c c)))]]
     (mapv (fn [class-index]
             (if (= class-index (int anisotropic-class))
               aniso
               [base 0.0 base]))
           (range n-classes)))))

(defn coordinate-landmarks
  ([separation]
   (coordinate-landmarks separation {}))
  ([separation {:keys [radius round-decimals]
                :or {radius cluster-radius
                     round-decimals round-decimals}}]
   (let [centers (cluster-centers separation {:radius radius})
         pow (Math/pow 10.0 (double round-decimals))
         round-fn #(/ (Math/round (* pow (double %))) pow)
         axis-values
         (mapcat (fn [axis]
                   (let [coords (sort (map #(nth % axis) centers))]
                     (map #(/ (+ (first %) (second %)) 2.0)
                          (partition 2 1 coords))))
                 [0 1])]
     (sort (set (map round-fn (cons 0.0 axis-values)))))))

(defn compression-free-values
  [separation mode]
  (case (or mode "zero")
    "circle_landmarks" (coordinate-landmarks separation)
    "zero" [0.0]
    "none" []
    (throw (ex-info "unknown threshold free-value mode" {:mode mode}))))

(defn- round-to
  [digits x]
  (let [pow (Math/pow 10.0 (double digits))]
    (/ (Math/round (* pow (double x))) pow)))

(defn- sample-2d
  [^Random rng [cx cy] [a b d] digits]
  (let [z0 (.nextGaussian rng)
        z1 (.nextGaussian rng)
        l00 (Math/sqrt (max 0.0 a))
        l10 (if (zero? l00) 0.0 (/ b l00))
        l11 (Math/sqrt (max 0.0 (- d (* l10 l10))))
        x (+ cx (* l00 z0))
        y (+ cy (* l10 z0) (* l11 z1))]
    [(round-to digits x) (round-to digits y)]))

(defn- shuffle-vector
  [^Random rng xs]
  (loop [v (vec xs)
         i (dec (count xs))]
    (if (<= i 0)
      v
      (let [j (.nextInt rng (inc i))]
        (recur (assoc v i (nth v j) j (nth v i))
               (dec i))))))

(defn make-3cluster-data
  [separation & {:keys [radius n-per-class seed round-decimals]
                 :or {radius cluster-radius
                      n-per-class 16
                      seed 0
                      round-decimals round-decimals}}]
  (let [rng (Random. (long seed))
        centers (cluster-centers separation {:radius radius})
        covariances (cluster-covariances separation {:radius radius})
        rows (for [class-index (range n-classes)
                   _ (range n-per-class)]
               {:x (sample-2d rng
                              (nth centers class-index)
                              (nth covariances class-index)
                              round-decimals)
                :y class-index})
        shuffled (shuffle-vector rng rows)]
    {:x (mapv :x shuffled)
     :y (mapv :y shuffled)}))

(defn one-hot-labels
  ([labels]
   (one-hot-labels labels n-classes))
  ([labels n-classes]
   (mapv (fn [label]
           (mapv #(= (int label) %) (range n-classes)))
         labels)))

(defn one-hot-columns
  ([labels-or-rows]
   (one-hot-columns labels-or-rows n-classes))
  ([labels-or-rows n-classes]
   (let [rows (if (and (seq labels-or-rows)
                       (sequential? (first labels-or-rows)))
                (mapv vec labels-or-rows)
                (one-hot-labels labels-or-rows n-classes))]
     (mapv (fn [class-index]
             (mapv #(nth % class-index) rows))
           (range n-classes)))))

(defn preview-rows
  [x-values y-values & [{:keys [limit]
                         :or {limit 8}}]]
  (let [onehot (one-hot-labels y-values)]
    (mapv (fn [idx]
            (let [[x0 x1] (nth x-values idx)
                  [y0 y1 y2] (nth onehot idx)]
              {:x0 x0
               :x1 x1
               :y (nth y-values idx)
               :y0 y0
               :y1 y1
               :y2 y2}))
          (range (min limit (count x-values))))))

(defn nearest-center-predict
  ([x-values]
   (nearest-center-predict x-values nil))
  ([x-values separation]
   (let [centers (cluster-centers separation)]
     (mapv (fn [[x0 x1]]
             (->> centers
                  (map-indexed
                   (fn [idx [cx cy]]
                     [idx (+ (Math/pow (- (double x0) cx) 2.0)
                             (Math/pow (- (double x1) cy) 2.0))]))
                  (sort-by second)
                  ffirst))
           x-values))))

(defn make-cluster-task
  [x-features y-values & {:keys [separation threshold-rate free-value-mode name]
                          :or {threshold-rate 1.0
                               free-value-mode "zero"
                               name "minimal_3cluster_onehot_classifier"}}]
  (let [x-features (mapv vec x-features)
        y-columns (one-hot-columns y-values n-classes)
        x0 (mapv first x-features)
        x1 (mapv second x-features)]
    (alice/compression-task
     (vec (concat y-columns [x0 x1]))
     {:name name
      :threshold-rate (alice/require-rate-fraction :threshold-rate threshold-rate)
      :free-values (compression-free-values separation free-value-mode)})))

(defn default-run-opts
  [{:keys [num-workers min-compression-rate max-dag-dl max-popped max-yields
           halted? observer observer-sample-rate parallel-strategy]
    :or {num-workers 1
         min-compression-rate 0.01
         max-dag-dl 25}}]
  (cond-> {:registry classifier-registry
           :operator-ids classifier-operator-ids
           :ops-with-counts classifier-ops-with-counts
           :num-workers num-workers
           :min-compression-rate min-compression-rate
           :max-dag-dl max-dag-dl
           :optimize-candidates? true}
    max-popped (assoc :max-popped max-popped)
    max-yields (assoc :max-yields max-yields)
    halted? (assoc :halted? halted?)
    observer (assoc :observer observer)
    observer-sample-rate (assoc :observer-sample-rate observer-sample-rate)
    parallel-strategy (assoc :parallel-strategy parallel-strategy)))

(defn operator-form?
  [expr]
  (and (vector? expr)
       (seq expr)
       (contains? classifier-registry (first expr))))

(defn expression-leaves-dl
  [expr]
  (if (operator-form? expr)
    (reduce + 0.0 (map expression-leaves-dl (rest expr)))
    (value/desc-len (value/value expr))))

(defn selected-leaves-dl
  [selected]
  (reduce + 0.0 (map expression-leaves-dl (vals selected))))

(defn compression-percent
  [model-dl residual-dl]
  (if (and (Double/isFinite (double model-dl))
           (Double/isFinite (double residual-dl))
           (not (zero? (double model-dl))))
    (* 100.0 (- 1.0 (/ (double residual-dl) (double model-dl))))
    Double/NaN))

(defn run-cluster-compression
  [x-features y-values & {:keys [separation name num-workers threshold-rate
                                 min-compression-rate max-dag-dl free-value-mode
                                 halted? progress-observer max-popped max-yields
                                 parallel-strategy]
                          :or {name "minimal_3cluster_onehot_classifier"
                               num-workers 1
                               threshold-rate 1.0
                               min-compression-rate 0.01
                               max-dag-dl 25
                               free-value-mode "zero"}}]
  (let [task (make-cluster-task x-features y-values
                                :separation separation
                                :threshold-rate threshold-rate
                                :free-value-mode free-value-mode
                                :name name)
        started (System/nanoTime)
        result (alice-wunderbaum/run-greedy-task
                task
                (default-run-opts
                  {:num-workers num-workers
                   :min-compression-rate min-compression-rate
                   :max-dag-dl max-dag-dl
                   :max-popped max-popped
                   :max-yields max-yields
                   :halted? halted?
                   :observer progress-observer
                   :observer-sample-rate 100
                   :parallel-strategy parallel-strategy}))
        elapsed-s (/ (double (- (System/nanoTime) started)) 1000000000.0)
        residual-dl (selected-leaves-dl (:selected result))]
    (assoc result
           :elapsed-s elapsed-s
           :residual-dl residual-dl
           :compression-percent (compression-percent (:dl result) residual-dl))))

(defn make-candidate-dataset
  [x-train-values y-train-values test-row candidate-label]
  {:x (conj (mapv vec x-train-values) (vec test-row))
   :y (conj (if (and (seq y-train-values)
                    (sequential? (first y-train-values)))
              (mapv vec y-train-values)
              (one-hot-labels y-train-values))
            (first (one-hot-labels [candidate-label])))})

(defn score-candidate-label
  [test-index test-row candidate-label
   {:keys [separation x-train-values y-train-values condition-name num-workers
           threshold-rate min-compression-rate max-dag-dl free-value-mode halted?
           max-popped max-yields parallel-strategy progress-observer]
    :or {condition-name "minimal_3cluster_classify"
         num-workers 1
         threshold-rate 1.0
         min-compression-rate 0.01
         max-dag-dl 25
         free-value-mode "zero"}}]
  (let [{x-candidate :x y-candidate :y}
        (make-candidate-dataset x-train-values
                                y-train-values
                                test-row
                                candidate-label)
        result (run-cluster-compression
                x-candidate
                y-candidate
                :separation separation
                :name (format "%s_test_%d_candidate_%d"
                              condition-name
                              (int test-index)
                              (int candidate-label))
                :num-workers num-workers
                :threshold-rate threshold-rate
                :min-compression-rate min-compression-rate
                :max-dag-dl max-dag-dl
                :free-value-mode free-value-mode
                :halted? halted?
                :max-popped max-popped
                :max-yields max-yields
                :parallel-strategy parallel-strategy
                :progress-observer progress-observer)]
    [{:test-index (int test-index)
      :candidate-label (int candidate-label)
      :residual-dl (double (:residual-dl result))
      :graph-dl (double (:dl result))
      :elapsed-s (double (:elapsed-s result))
      :accepted true
      :error ""
      :compression-rate (double (:compression-rate result))}
     result]))

(defn- score-candidate-job
  [{:keys [test-index test-row true-label candidate-label halted? store-graphs?]
    :as job}]
  (let [started (System/nanoTime)
        heap-start (capture-heap)
        last-search-heap (volatile! nil)
        job (assoc job :progress-observer (candidate-heap-observer last-search-heap))]
    (try
      (if (and halted? @halted?)
        [(merge {:test-index (int test-index)
                 :candidate-label (int candidate-label)
                 :true-label (int true-label)
                 :residual-dl Double/POSITIVE_INFINITY
                 :graph-dl Double/POSITIVE_INFINITY
                 :elapsed-s 0.0
                 :accepted false
                 :error "halted"
                 :compression-rate 0.0}
                (heap-row-fields heap-start @last-search-heap (capture-heap)))
         [(int test-index) (int candidate-label)]
         nil]
        (let [[row result] (score-candidate-label
                            test-index
                            test-row
                            candidate-label
                            job)]
          [(merge (assoc row :true-label (int true-label))
                  (heap-row-fields heap-start @last-search-heap (capture-heap)))
           [(int test-index) (int candidate-label)]
           (when store-graphs? result)]))
      (catch Throwable t
        [(merge {:test-index (int test-index)
                 :candidate-label (int candidate-label)
                 :true-label (int true-label)
                 :residual-dl Double/POSITIVE_INFINITY
                 :graph-dl Double/POSITIVE_INFINITY
                 :elapsed-s (/ (double (- (System/nanoTime) started)) 1000000000.0)
                 :accepted false
                 :error (or (.getMessage t) (str (class t)))
                 :compression-rate 0.0}
                (heap-row-fields heap-start @last-search-heap (capture-heap)))
         [(int test-index) (int candidate-label)]
         nil]))))

(defn finite-number?
  [x]
  (and (number? x) (Double/isFinite (double x))))

(defn summarize-candidate-scores
  [score-rows & [{:keys [tie-tol]
                  :or {tie-tol 1.0e-9}}]]
  (->> score-rows
       (group-by :test-index)
       (map (fn [[test-index rows]]
              (let [true-label (:true-label (first rows))
                    finite (filter #(finite-number? (:residual-dl %)) rows)]
                (if (seq finite)
                  (let [ordered (sort-by (juxt :residual-dl
                                               :graph-dl
                                               :candidate-label)
                                         finite)
                        best (first ordered)
                        residuals (mapv :residual-dl ordered)
                        best-residual (double (:residual-dl best))
                        tie-count (count (filter #(<= (Math/abs (- (double (:residual-dl %))
                                                                 best-residual))
                                                     tie-tol)
                                                 finite))]
                    {:test-index (int test-index)
                     :true-label (int true-label)
                     :predicted-label (int (:candidate-label best))
                     :correct (if (= (int (:candidate-label best))
                                     (int true-label))
                                1 0)
                     :best-residual-dl best-residual
                     :best-graph-dl (double (:graph-dl best))
                     :score-margin (if (> (count residuals) 1)
                                     (- (second residuals) (first residuals))
                                     Double/POSITIVE_INFINITY)
                     :tie-count tie-count
                     :all-failed 0})
                  {:test-index (int test-index)
                   :true-label (int true-label)
                   :predicted-label -1
                   :correct 0
                   :best-residual-dl Double/POSITIVE_INFINITY
                   :best-graph-dl Double/POSITIVE_INFINITY
                   :score-margin Double/NaN
                   :tie-count 0
                   :all-failed 1}))))
       (sort-by :test-index)
       vec))

(defn- update-progress!
  [progress f & args]
  (when progress
    (apply swap! progress f args)))

(defn- progress-message!
  [progress? fmt & args]
  (when progress?
    (println (apply format fmt args))
    (flush)))

(defn classify-by-recompression
  [x-train-values y-train-values x-test-values y-test-values
   & {:keys [separation labels condition-name max-test-points store-graphs?
             progress? progress halted? num-workers outer-workers threshold-rate
             min-compression-rate max-dag-dl free-value-mode tie-tol max-popped
             max-yields parallel-strategy]
      :or {condition-name "minimal_3cluster_classify"
           store-graphs? true
           progress? true
           num-workers 1
           threshold-rate 1.0
           min-compression-rate 0.01
           max-dag-dl 25
           free-value-mode "zero"
           tie-tol 1.0e-9}}]
  (let [labels (vec (or labels (range n-classes)))
        x-test-values (mapv vec x-test-values)
        y-test-values (mapv int y-test-values)
        n-examples (if max-test-points
                     (min (int max-test-points) (count x-test-values))
                     (count x-test-values))
        jobs (vec (for [test-index (range n-examples)
                        candidate-label labels]
                    {:test-index test-index
                     :test-row (nth x-test-values test-index)
                     :true-label (nth y-test-values test-index)
                     :candidate-label candidate-label
                     :separation separation
                     :x-train-values x-train-values
                     :y-train-values y-train-values
                     :condition-name condition-name
                     :store-graphs? store-graphs?
                     :num-workers num-workers
                     :threshold-rate threshold-rate
                     :min-compression-rate min-compression-rate
                     :max-dag-dl max-dag-dl
                     :free-value-mode free-value-mode
                     :halted? halted?
                     :max-popped max-popped
                     :max-yields max-yields
                     :parallel-strategy parallel-strategy}))
        collect-result
        (fn [{:keys [score-rows graphs-by-key] :as acc} [row graph-key result]]
          (update-progress! progress
                            (fn [p]
                              (-> p
                                  (update :completed (fnil inc 0))
                                  (assoc :last-row row
                                         :heap-current (select-keys row heap-row-keys)
                                         :condition condition-name))))
          (when progress?
            (println (format "%s: test=%d label=%d %s %.1fs heap=%s"
                             condition-name
                             (:test-index row)
                             (:candidate-label row)
                             (if (:accepted row) "ok" "fail")
                             (double (:elapsed-s row))
                             (or (heap-summary row) "n/a"))))
          (cond-> (assoc acc :score-rows (conj score-rows row))
            (and store-graphs? result)
            (assoc :graphs-by-key (assoc graphs-by-key graph-key result))))]
    (when progress?
      (println (format "%s: %d candidate compression runs"
                       condition-name
                       (count jobs))))
    (let [{:keys [score-rows graphs-by-key]}
          (if (and outer-workers (> (long outer-workers) 1) (> (count jobs) 1))
            (let [executor (Executors/newFixedThreadPool (int outer-workers))
                  completion (ExecutorCompletionService. executor)]
              (try
                (doseq [job jobs]
                  (.submit completion
                           ^Callable
                           (reify Callable
                             (call [_]
                               (score-candidate-job job)))))
                (loop [remaining (count jobs)
                       acc {:score-rows []
                            :graphs-by-key {}}]
                  (if (zero? remaining)
                    acc
                    (let [future (.take completion)
                          result (.get future)]
                      (recur (dec remaining)
                             (collect-result acc result)))))
                (finally
                  (.shutdownNow executor)
                  (.awaitTermination executor 1 TimeUnit/SECONDS))))
            (reduce (fn [acc job]
                      (collect-result acc (score-candidate-job job)))
                    {:score-rows []
                     :graphs-by-key {}}
                    jobs))
          score-rows (vec (sort-by (juxt :test-index :candidate-label)
                                   score-rows))
          prediction-rows (summarize-candidate-scores score-rows
                                                      {:tie-tol tie-tol})]
      {:scores score-rows
       :predictions prediction-rows
       :graphs-by-key graphs-by-key})))

(defn condition-train-seed
  [repeat sep-index n-train-per-class & [{:keys [seed]
                                          :or {seed 0}}]]
  (+ (int seed)
     (* 10000 (int repeat))
     (* 100 (int sep-index))
     (int n-train-per-class)))

(defn condition-test-seed
  [repeat sep-index & [{:keys [seed]
                        :or {seed 0}}]]
  (+ (int seed)
     (* 10000 (int repeat))
     (* 100 (int sep-index))
     777))

(defn condition-cache-key
  [{:keys [seed repeat sep-index separation n-train-per-class n-test-per-class
           max-test-points free-value-mode]}]
  [(int seed)
   (int repeat)
   (int sep-index)
   (double separation)
   (int n-train-per-class)
   (int n-test-per-class)
   (some-> max-test-points int)
   (str free-value-mode)])

(defn condition-metadata
  [{:keys [seed repeat sep-index separation n-train-per-class n-test-per-class
           max-test-points free-value-mode]}]
  {:seed (int seed)
   :repeat (int repeat)
   :sep-index (int sep-index)
   :separation (double separation)
   :radius (double cluster-radius)
   :cluster-std (cluster-std separation)
   :cluster-variance (cluster-variance separation)
   :n-train-per-class (int n-train-per-class)
   :n-train-total (* (int n-train-per-class) n-classes)
   :n-test-per-class (int n-test-per-class)
   :max-test-points (some-> max-test-points int)
   :free-value-mode (str free-value-mode)})

(defn- with-condition-metadata
  [rows metadata condition-key]
  (mapv #(merge metadata {:condition-key condition-key} %) rows))

(defn run-accuracy-sweep!
  [& [{:keys [condition-graph-cache progress halted?]
       :as opts}]]
  (let [{:keys [separations n-train-per-class-grid n-repeats n-test-per-class
                max-test-points seed free-value-mode store-graphs? progress?
                skip-cached? num-workers outer-workers threshold-rate
                min-compression-rate max-dag-dl tie-tol max-popped max-yields
                parallel-strategy]}
        (merge default-sweep-opts opts)
        cache-atom (cond
                     (instance? clojure.lang.IAtom condition-graph-cache)
                     condition-graph-cache

                     (map? condition-graph-cache)
                     (atom condition-graph-cache)

                     :else
                     (atom {}))
        conditions (vec (for [repeat (range (int n-repeats))
                              [sep-index separation] (map-indexed vector separations)
                              n-train-per-class n-train-per-class-grid]
                          {:seed seed
                           :repeat repeat
                           :sep-index sep-index
                           :separation (double separation)
                           :n-train-per-class (int n-train-per-class)
                           :n-test-per-class (int n-test-per-class)
                           :max-test-points max-test-points
                           :free-value-mode free-value-mode}))
        total-candidates
        (reduce (fn [total {:keys [n-test-per-class max-test-points]}]
                  (+ total
                     (* n-classes
                        (if max-test-points
                          (min (int max-test-points)
                               (* n-classes (int n-test-per-class)))
                          (* n-classes (int n-test-per-class))))))
                0
                conditions)
        progress (or progress (atom {}))
        initial-heap (capture-heap)]
    (swap! progress merge {:total total-candidates
                           :completed 0
                           :conditions (count conditions)
                           :started-at-ms (System/currentTimeMillis)
                           :heap-start initial-heap
                           :heap-current initial-heap
                           :status :running})
    (loop [remaining conditions
           all-scores []
           all-predictions []]
      (if (empty? remaining)
        (let [heap (capture-heap)]
          (swap! progress assoc
                 :status :done
                 :finished-at-ms (System/currentTimeMillis)
                 :heap-finished heap
                 :heap-current heap)
          {:scores (vec all-scores)
           :predictions (vec all-predictions)
           :condition-graph-cache @cache-atom
           :progress @progress})
        (let [{:keys [repeat sep-index separation n-train-per-class] :as metadata-input}
              (first remaining)
              condition-key (condition-cache-key metadata-input)
              cached (get @cache-atom condition-key)]
          (cond
            (and halted? @halted?)
            (do
              (let [heap (capture-heap)]
                (swap! progress assoc
                       :status :halted
                       :finished-at-ms (System/currentTimeMillis)
                       :heap-finished heap
                       :heap-current heap))
              {:scores (vec all-scores)
               :predictions (vec all-predictions)
               :condition-graph-cache @cache-atom
               :progress @progress})

            (and skip-cached? cached)
            (do
              (progress-message! progress?
                                 "using cached condition sep=%s, n/class=%s, repeat=%s"
                                 separation
                                 n-train-per-class
                                 repeat)
              (swap! progress update :completed
                     (fnil + 0)
                     (count (:scores cached)))
              (swap! progress assoc :heap-current (capture-heap))
              (recur (rest remaining)
                     (into all-scores (:scores cached))
                     (into all-predictions (:predictions cached))))

            :else
            (let [train-seed (condition-train-seed repeat
                                                   sep-index
                                                   n-train-per-class
                                                   {:seed seed})
                  test-seed (condition-test-seed repeat sep-index {:seed seed})
                  train-data (make-3cluster-data separation
                                                 :n-per-class n-train-per-class
                                                 :seed train-seed)
                  test-data (make-3cluster-data separation
                                                :n-per-class n-test-per-class
                                                :seed test-seed)
                  y-train-onehot (one-hot-labels (:y train-data))
                  condition-name (format "minimal_3cluster_sep_%.3f_n_%d_r_%d"
                                         separation
                                         n-train-per-class
                                         repeat)
                  n-examples (if max-test-points
                               (min (int max-test-points) (count (:x test-data)))
                               (count (:x test-data)))
                  _ (progress-message!
                     progress?
                     "starting condition sep=%s, n/class=%s, repeat=%s: %d candidate compression runs"
                     separation
                     n-train-per-class
                     repeat
                     (* n-examples n-classes))
                  classified (classify-by-recompression
                              (:x train-data)
                              y-train-onehot
                              (:x test-data)
                              (:y test-data)
                              :separation separation
                              :condition-name condition-name
                              :max-test-points max-test-points
                              :store-graphs? store-graphs?
                              :progress? progress?
                              :progress progress
                              :halted? halted?
                              :num-workers num-workers
                              :outer-workers outer-workers
                              :threshold-rate threshold-rate
                              :min-compression-rate min-compression-rate
                              :max-dag-dl max-dag-dl
                              :free-value-mode free-value-mode
                              :tie-tol tie-tol
                              :max-popped max-popped
                              :max-yields max-yields
                              :parallel-strategy parallel-strategy)
                  test-indices (mapv :test-index (:predictions classified))
                  nearest (nearest-center-predict (mapv #(nth (:x test-data) %) test-indices)
                                                 separation)
                  predictions (mapv (fn [row nearest-label]
                                      (assoc row
                                             :nearest-center-label nearest-label
                                             :nearest-center-correct
                                             (if (= (int nearest-label)
                                                    (int (:true-label row)))
                                               1 0)))
                                    (:predictions classified)
                                    nearest)
                  metadata (condition-metadata metadata-input)
                  scores (with-condition-metadata (:scores classified)
                           metadata
                           condition-key)
                  predictions (with-condition-metadata predictions
                                metadata
                                condition-key)
                  cache-entry {:scores scores
                               :predictions predictions
                               :graphs-by-key (:graphs-by-key classified)
                               :metadata metadata-input}]
              (when store-graphs?
                (swap! cache-atom assoc condition-key cache-entry))
              (recur (rest remaining)
                     (into all-scores scores)
                     (into all-predictions predictions)))))))))

(defn start-accuracy-sweep!
  [& [opts]]
  (let [progress (atom {})
        halted? (atom false)
        result-promise (promise)
        fut (future
              (try
                (let [result (run-accuracy-sweep!
                              (assoc opts
                                     :progress progress
                                     :halted? halted?))]
                  (deliver result-promise result)
                  result)
                (catch Throwable t
                  (let [heap (capture-heap)]
                    (swap! progress assoc
                           :status :error
                           :error (or (.getMessage t) (str (class t)))
                           :finished-at-ms (System/currentTimeMillis)
                           :heap-error heap
                           :heap-current heap))
                  (deliver result-promise {:error t
                                           :progress @progress})
                  (throw t))))]
    {:future fut
     :progress progress
     :halted? halted?
     :result result-promise}))

(defn cancel-sweep!
  [job]
  (reset! (:halted? job) true)
  (swap! (:progress job) assoc
         :status :halting
         :heap-current (capture-heap))
  job)

(defn finite-values
  [xs]
  (filter finite-number? xs))

(defn finite-mean
  [xs]
  (let [xs (vec (finite-values xs))]
    (if (seq xs)
      (/ (reduce + 0.0 xs) (count xs))
      Double/NaN)))

(defn finite-median
  [xs]
  (let [xs (vec (sort (finite-values xs)))
        n (count xs)]
    (cond
      (zero? n) Double/NaN
      (odd? n) (nth xs (quot n 2))
      :else (/ (+ (nth xs (dec (quot n 2)))
                  (nth xs (quot n 2)))
               2.0))))

(defn mean-boolish
  [rows key]
  (let [xs (map (fn [row]
                  (let [x (key row)]
                    (cond
                      (true? x) 1.0
                      (false? x) 0.0
                      (nil? x) 0.0
                      :else (double x))))
                rows)]
    (if (seq xs)
      (/ (reduce + 0.0 xs) (count xs))
      Double/NaN)))

(defn summarize-accuracy-sweep
  ([predictions]
   (summarize-accuracy-sweep predictions nil))
  ([predictions scores]
   (let [score-groups (group-by (juxt :separation :n-train-per-class :n-train-total)
                                (or scores []))]
     (->> predictions
          (group-by (juxt :separation :n-train-per-class :n-train-total))
          (map (fn [[[separation n-train-per-class n-train-total] rows]]
                 (let [first-row (first rows)
                       score-rows (get score-groups [separation
                                                     n-train-per-class
                                                     n-train-total])]
                   (merge
                    {:separation separation
                     :n-train-per-class n-train-per-class
                     :n-train-total n-train-total
                     :accuracy (mean-boolish rows :correct)
                     :nearest-center-accuracy (mean-boolish rows :nearest-center-correct)
                     :n-predictions (count rows)
                     :n-repeats (count (set (map :repeat rows)))
                     :all-failed-rate (mean-boolish rows :all-failed)
                     :mean-score-margin (finite-mean (map :score-margin rows))
                     :median-score-margin (finite-median (map :score-margin rows))
                     :tie-rate (/ (count (filter #(> (long (:tie-count %)) 1) rows))
                                  (double (max 1 (count rows))))}
                    (select-keys first-row
                                 [:sep-index :radius :cluster-std
                                  :cluster-variance :free-value-mode
                                  :n-test-per-class :max-test-points])
                    (when (seq score-rows)
                      {:candidate-success-rate (mean-boolish score-rows :accepted)
                       :mean-candidate-elapsed-s (finite-mean (map :elapsed-s score-rows))})))))
          (sort-by (juxt :separation :n-train-per-class :n-train-total))
          vec))))

(defn- html-escape
  [x]
  (-> (str x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn html-view
  [html & [{:keys [title key]
            :or {title "CIWI notebook view"
                 key "ciwi-notebook-view"}}]]
  (tagged-literal 'cursive/html
                  {:html (str "<div style='background:#ffffff;color:#111111;"
                              "padding:10px;font-family:sans-serif;"
                              "line-height:1.35'>"
                              html
                              "</div>")
                   :title title
                   :key key}))

(defn view-html
  [view]
  (if (and (instance? clojure.lang.TaggedLiteral view)
           (= 'cursive/html (:tag view)))
    (get (:form view) :html)
    (str "<pre>" (html-escape (pr-str view)) "</pre>")))

(defn stack-view
  [views & [{:keys [title key]
             :or {title "CIWI notebook output"
                  key "ciwi-notebook-output"}}]]
  (html-view
   (apply str
          (interpose "<div style='height:12px'></div>"
                     (map view-html (remove nil? views))))
   {:title title
    :key key}))

(defn- fmt
  [x]
  (cond
    (nil? x) ""
    (and (number? x) (Double/isNaN (double x))) "nan"
    (number? x) (format "%.4g" (double x))
    :else (str x)))

(defn table-view
  [rows & [{:keys [columns title key limit]
            :or {title "table"
                 key "table"}}]]
  (let [rows (vec (if limit (take limit rows) rows))
        columns (or columns (vec (keys (first rows))))
        header (str "<tr>"
                    (apply str
                           (for [column columns]
                             (str "<th>" (html-escape (name column)) "</th>")))
                    "</tr>")
        body (apply str
                    (for [row rows]
                      (str "<tr>"
                           (apply str
                                  (for [column columns]
                                    (str "<td>" (html-escape (fmt (get row column))) "</td>")))
                           "</tr>")))]
    (html-view
     (str "<style>"
          "table.ciwi{border-collapse:collapse;font:12px sans-serif;color:#111;background:#fff}"
          ".ciwi th,.ciwi td{border:1px solid #cfcfcf;padding:4px 6px;text-align:right;color:#111;background:#fff}"
          ".ciwi th{background:#f6f6f6;color:#111}"
          "</style>"
          "<h3 style='color:#111;margin:0 0 8px'>" (html-escape title) "</h3>"
          "<table class='ciwi'>" header body "</table>")
     {:title title :key key})))

(defn- bounds
  [points]
  (let [xs (map first points)
        ys (map second points)
        min-x (apply min xs)
        max-x (apply max xs)
        min-y (apply min ys)
        max-y (apply max ys)
        span (max (- max-x min-x) (- max-y min-y) 1.0)
        mid-x (/ (+ min-x max-x) 2.0)
        mid-y (/ (+ min-y max-y) 2.0)
        pad (* 0.12 span)]
    [(- mid-x (/ span 2.0) pad)
     (+ mid-x (/ span 2.0) pad)
     (- mid-y (/ span 2.0) pad)
     (+ mid-y (/ span 2.0) pad)]))

(defn- project-point
  [[min-x max-x min-y max-y] width height [x y]]
  (let [plot-left 46
        plot-top 20
        plot-width (- width plot-left 18)
        plot-height (- height plot-top 44)]
    [(+ plot-left (* (/ (- (double x) min-x) (- max-x min-x)) plot-width))
     (+ plot-top (* (- 1.0 (/ (- (double y) min-y) (- max-y min-y))) plot-height))]))

(def cluster-colors ["#4e79a7" "#f28e2b" "#59a14f"])

(defn cluster-preview-view
  [x-values y-values separation & [{:keys [title key]
                                    :or {title "rotated 3-cluster task"
                                         key "cluster-preview"}}]]
  (let [width 560
        height 520
        centers (cluster-centers separation)
        all-points (concat x-values centers [[0.0 0.0]])
        b (bounds all-points)
        scale (/ (- width 64.0) (- (second b) (first b)))
        covariances (cluster-covariances separation)
        [origin-x origin-y] (project-point b width height [0.0 0.0])
        [radius-x _] (project-point b width height [cluster-radius 0.0])
        data-circles
        (apply str
               (for [[[x y] label] (map vector x-values y-values)
                     :let [[sx sy] (project-point b width height [x y])]]
                 (format "<circle cx='%.2f' cy='%.2f' r='3.2' fill='%s' opacity='0.72'/>"
                         sx sy (nth cluster-colors label))))
        center-crosses
        (apply str
               (for [[class-index center] (map-indexed vector centers)
                     :let [[sx sy] (project-point b width height center)
                           color (nth cluster-colors class-index)]]
                 (str (format "<line x1='%.2f' y1='%.2f' x2='%.2f' y2='%.2f' stroke='%s' stroke-width='2.4'/>"
                              (- sx 8) (- sy 8) (+ sx 8) (+ sy 8) color)
                      (format "<line x1='%.2f' y1='%.2f' x2='%.2f' y2='%.2f' stroke='%s' stroke-width='2.4'/>"
                              (- sx 8) (+ sy 8) (+ sx 8) (- sy 8) color))))
        ellipses
        (apply str
               (for [[class-index [a b2 d]] (map-indexed vector covariances)
                     :let [trace (+ a d)
                           disc (Math/sqrt (max 0.0 (+ (Math/pow (- a d) 2.0)
                                                       (* 4.0 b2 b2))))
                           lambda1 (/ (+ trace disc) 2.0)
                           lambda2 (/ (- trace disc) 2.0)
                           angle (if (zero? b2)
                                   0.0
                                   (Math/toDegrees
                                    (Math/atan2 (- lambda1 a) b2)))
                           [cx cy] (project-point b width height
                                                  (nth centers class-index))]]
                 (format "<ellipse cx='%.2f' cy='%.2f' rx='%.2f' ry='%.2f' transform='rotate(%.2f %.2f %.2f)' fill='none' stroke='%s' stroke-width='1.2' opacity='0.8'/>"
                         cx cy (* 2.0 scale (Math/sqrt lambda1))
                         (* 2.0 scale (Math/sqrt lambda2))
                         (- angle) cx cy (nth cluster-colors class-index))))]
    (html-view
     (str "<svg width='" width "' height='" height "' viewBox='0 0 " width " " height
          "' xmlns='http://www.w3.org/2000/svg'>"
          "<rect width='100%' height='100%' fill='white'/>"
          "<text x='46' y='16' font-family='sans-serif' font-size='14'>"
          (html-escape (format "%s, radius/variance=%.2f, base variance=%.3f"
                               title
                               (double separation)
                               (cluster-variance separation)))
          "</text>"
          (format "<line x1='46' y1='%.2f' x2='542' y2='%.2f' stroke='black' opacity='0.18'/>"
                  origin-y origin-y)
          (format "<line x1='%.2f' y1='20' x2='%.2f' y2='476' stroke='black' opacity='0.18'/>"
                  origin-x origin-x)
          (format "<circle cx='%.2f' cy='%.2f' r='%.2f' fill='none' stroke='black' stroke-width='1' opacity='0.35'/>"
                  origin-x origin-y (Math/abs (- radius-x origin-x)))
          ellipses
          data-circles
          center-crosses
          "<text x='278' y='508' text-anchor='middle' font-family='sans-serif' font-size='12'>x0</text>"
          "<text x='12' y='250' transform='rotate(-90 12 250)' text-anchor='middle' font-family='sans-serif' font-size='12'>x1</text>"
          "</svg>")
     {:title title :key key})))

(defn- color-ramp
  [v vmin vmax]
  (if (or (nil? v) (not (finite-number? v)) (= vmin vmax))
    "#f2f2f2"
    (let [t (max 0.0 (min 1.0 (/ (- (double v) (double vmin))
                                  (- (double vmax) (double vmin)))))
          hue (- 250.0 (* 180.0 t))]
      (format "hsl(%.1f,65%%,72%%)" hue))))

(defn summary-heatmap
  [summary value & [{:keys [title key vmin vmax]
                     :or {key "summary-heatmap"}}]]
  (let [separations (sort (set (map :separation summary)))
        train-totals (sort (set (map :n-train-total summary)))
        value-map (into {}
                        (map (fn [row]
                               [[(:n-train-total row) (:separation row)]
                                (get row value)]))
                        summary)
        values (finite-values (map value-map
                                   (for [n train-totals s separations] [n s])))
        vmin (or vmin (if (seq values) (apply min values) 0.0))
        vmax (or vmax (if (seq values) (apply max values) 1.0))
        header (str "<tr><th>n \\ sep</th>"
                    (apply str
                           (for [s separations]
                             (format "<th>%.2f</th>" (double s))))
                    "</tr>")
        rows (apply str
                    (for [n train-totals]
                      (str "<tr><th>" n "</th>"
                           (apply str
                                  (for [s separations
                                        :let [v (get value-map [n s])]]
                                    (str "<td style='background:" (color-ramp v vmin vmax) "'>"
                                         (html-escape (fmt v))
                                         "</td>")))
                           "</tr>")))]
    (html-view
     (str "<style>.heat{border-collapse:collapse;font:12px sans-serif}"
          ".heat th,.heat td{border:1px solid #cfcfcf;padding:8px 10px;text-align:center;color:#111}"
          ".heat th{background:#f7f7f7;color:#111}</style>"
          "<h3 style='color:#111;margin:0 0 8px'>" (html-escape (or title (name value))) "</h3>"
          "<table class='heat'>" header rows "</table>")
     {:title (or title (name value))
      :key key})))

(defn accuracy-curves
  [summary & [{:keys [key title]
               :or {key "accuracy-curves"
                    title "IC recompression accuracy"}}]]
  (let [width 640
        height 360
        left 56
        top 24
        plot-width 540
        plot-height 280
        separations (sort (set (map :separation summary)))
        min-sep (apply min separations)
        max-sep (apply max separations)
        x-scale (fn [sep]
                  (+ left (* (/ (- (double sep) min-sep)
                                (max 1.0 (- max-sep min-sep)))
                             plot-width)))
        y-scale (fn [accuracy]
                  (+ top (* (- 1.0 (max 0.0 (min 1.0 (double accuracy))))
                            plot-height)))
        groups (sort-by first (group-by :n-train-total summary))
        colors ["#4e79a7" "#f28e2b" "#59a14f" "#e15759" "#76b7b2"]
        series-svg
        (apply str
               (for [[idx [n rows]] (map-indexed vector groups)
                     :let [rows (sort-by :separation rows)
                           color (nth colors (mod idx (count colors)))
                           points (mapv (fn [row]
                                          [(x-scale (:separation row))
                                           (y-scale (:accuracy row))])
                                        rows)
                           path (str/join " "
                                          (map-indexed
                                           (fn [i [x y]]
                                             (format "%s %.2f %.2f"
                                                     (if (zero? i) "M" "L") x y))
                                           points))]]
                 (str "<path d='" path "' fill='none' stroke='" color "' stroke-width='2'/>"
                      (apply str
                             (for [[x y] points]
                               (format "<circle cx='%.2f' cy='%.2f' r='4' fill='%s'/>"
                                       x y color)))
                      (format "<text x='%d' y='%d' font-family='sans-serif' font-size='12' fill='%s'>n=%d</text>"
                              605 (+ 40 (* idx 18)) color n))))]
    (html-view
     (str "<svg width='" width "' height='" height "' viewBox='0 0 " width " " height
          "' xmlns='http://www.w3.org/2000/svg'>"
          "<rect width='100%' height='100%' fill='white'/>"
          "<text x='" left "' y='16' font-family='sans-serif' font-size='14'>"
          (html-escape title)
          "</text>"
          (format "<line x1='%d' y1='%d' x2='%d' y2='%d' stroke='black'/>"
                  left (+ top plot-height) (+ left plot-width) (+ top plot-height))
          (format "<line x1='%d' y1='%d' x2='%d' y2='%d' stroke='black'/>"
                  left top left (+ top plot-height))
          (format "<line x1='%d' y1='%.2f' x2='%d' y2='%.2f' stroke='black' stroke-dasharray='4 4' opacity='0.35'/>"
                  left (y-scale (/ 1.0 n-classes)) (+ left plot-width)
                  (y-scale (/ 1.0 n-classes)))
          series-svg
          "<text x='326' y='346' text-anchor='middle' font-family='sans-serif' font-size='12'>radius / cluster variance</text>"
          "<text x='14' y='180' transform='rotate(-90 14 180)' text-anchor='middle' font-family='sans-serif' font-size='12'>accuracy</text>"
          "</svg>")
     {:title title :key key})))

(defn diagnostic-heatmaps
  [summary]
  [(summary-heatmap summary :nearest-center-accuracy
                    {:title "nearest-center baseline accuracy"
                     :key "nearest-center-accuracy"
                     :vmin 0.0
                     :vmax 1.0})
   (summary-heatmap summary :candidate-success-rate
                    {:title "candidate compression success rate"
                     :key "candidate-success-rate"
                     :vmin 0.0
                     :vmax 1.0})
   (summary-heatmap summary :all-failed-rate
                    {:title "test points with no finite candidate"
                     :key "all-failed-rate"
                     :vmin 0.0
                     :vmax 1.0})
   (summary-heatmap summary :mean-score-margin
                    {:title "mean residual-DL margin"
                     :key "mean-score-margin"})
   (summary-heatmap summary :tie-rate
                    {:title "residual-DL tie rate"
                     :key "tie-rate"
                     :vmin 0.0
                     :vmax 1.0})])

(defn progress-view
  [job-or-progress]
  (let [progress (if (instance? clojure.lang.IAtom job-or-progress)
                   @job-or-progress
                   @(or (:progress job-or-progress) (atom job-or-progress)))
        total (long (or (:total progress) 0))
        completed (long (or (:completed progress) 0))
        pct (if (pos? total)
              (* 100.0 (/ completed (double total)))
              0.0)
        last-row (:last-row progress)
        current-heap (or (:heap-current progress) last-row)]
    (html-view
     (str "<div style='font:13px sans-serif;max-width:640px;color:#111;background:#fff'>"
          "<div><b>Status:</b> " (html-escape (name (or (:status progress) :unknown))) "</div>"
          "<div style='height:18px;background:#eeeeee;border:1px solid #bbbbbb;margin:8px 0'>"
          "<div style='height:100%;width:" (format "%.2f" pct) "%;background:#4e79a7'></div>"
          "</div>"
          "<div>" completed " / " total " candidate runs (" (format "%.1f" pct) "%)</div>"
          (when current-heap
            (str "<div><b>Heap:</b> " (html-escape (heap-summary current-heap)) "</div>"))
          (when (:condition progress)
            (str "<div><b>Condition:</b> " (html-escape (:condition progress)) "</div>"))
          (when last-row
            (str "<div><b>Last:</b> test=" (:test-index last-row)
                 " label=" (:candidate-label last-row)
                 " accepted=" (:accepted last-row)
                 " elapsed=" (fmt (:elapsed-s last-row)) "s"
                 (when (:heap-observed-used-mib last-row)
                   (str " observed-heap=" (fmt (:heap-observed-used-mib last-row)) " MiB"
                        (when (:heap-observed-event last-row)
                          (str " at " (html-escape (:heap-observed-event last-row))))))
                 "</div>"))
          (when (:error progress)
            (str "<pre style='color:#111;background:#fff;border:1px solid #ddd;padding:6px'>"
                 (html-escape (:error progress))
                 "</pre>"))
          "</div>")
     {:title "progress"
      :key "progress"})))

(defn cached-condition-options
  [condition-graph-cache]
  (->> condition-graph-cache
       (map (fn [[key entry]]
              (merge {:condition-key key}
                     (:metadata entry)
                     {:cached-results (count (:graphs-by-key entry))
                      :score-rows (count (:scores entry))
                      :prediction-rows (count (:predictions entry))})))
       (sort-by (juxt :separation :n-train-per-class :repeat))
       vec))

(defn select-cached-condition
  [condition-graph-cache {:keys [condition-key separation n-train-per-class repeat]
                          :or {repeat 0}}]
  (if condition-key
    [condition-key (get condition-graph-cache condition-key)]
    (first
     (filter (fn [[_ entry]]
               (let [m (:metadata entry)]
                 (and (or (nil? separation)
                          (= (double separation) (double (:separation m))))
                      (or (nil? n-train-per-class)
                          (= (int n-train-per-class)
                             (int (:n-train-per-class m))))
                      (= (int repeat) (int (:repeat m))))))
             condition-graph-cache))))

(defn- file->data-uri
  [path]
  (let [bytes (Files/readAllBytes (Paths/get (str path) (into-array String [])))
        encoded (.encodeToString (Base64/getEncoder) bytes)]
    (str "data:image/png;base64," encoded)))

(defn- safe-file-part
  [x]
  (let [s (if (nil? x) "none" (str x))]
    (or (not-empty (str/replace s #"[^A-Za-z0-9_.-]+" "_"))
        "none")))

(defn- render-expression-fragment!
  [expr output-path label]
  (try
    (let [{g :graph root :root} (dsl/from-expr expr {:registry classifier-registry})
          result (render-graph/render-png! g output-path {:label label
                                                          :selected-root root})]
      (if (contains? #{:ok :rendered} (:status result))
        (str "<figure><figcaption>" (html-escape label) "</figcaption>"
             "<img style='max-width:100%;border:1px solid #ddd' src='"
             (file->data-uri (:png-path result))
             "'/></figure>")
        (str "<h4>" (html-escape label) "</h4>"
             "<p>Graphviz PNG rendering unavailable: "
             (html-escape (pr-str (:status result)))
             (when (:reason result)
               (str " (" (html-escape (pr-str (:reason result))) ")"))
             "</p>"
             (when (:dot-path result)
               (str "<p><b>DOT:</b> " (html-escape (:dot-path result)) "</p>")))))
    (catch Throwable t
      (str "<h4>" (html-escape label) "</h4>"
           "<p>Render failed: " (html-escape (or (.getMessage t) (str (class t)))) "</p>"
           (when-let [data (ex-data t)]
             (str (when (:dot-path data)
                    (str "<p><b>DOT:</b> " (html-escape (:dot-path data)) "</p>"))
                  (when (:err data)
                    (str "<pre style='color:#111;background:#fff;border:1px solid #ddd;padding:6px'>"
                         (html-escape (:err data))
                         "</pre>"))))))))

(defn render-cached-condition!
  [condition-graph-cache
   {:keys [condition-key separation n-train-per-class repeat render-dir mode]
    :or {repeat 0
         render-dir "tmp/minimal_classifier_2d_three_cluster_onehot_sweep/cached_solution_graphs"
         mode :winning}}]
  (let [[key entry] (select-cached-condition
                    condition-graph-cache
                    {:condition-key condition-key
                     :separation separation
                     :n-train-per-class n-train-per-class
                     :repeat repeat})]
    (if-not entry
      (html-view "<p>No cached condition matched the requested selection.</p>"
                 {:title "cached graphs" :key "cached-graphs"})
      (let [dir (io/file render-dir)
            _ (.mkdirs dir)
            predictions (:predictions entry)
            scores (:scores entry)
            graphs-by-key (:graphs-by-key entry)
            graph-items
            (case mode
              :all
              (for [row scores
                    :when (:accepted row)]
                [(:test-index row) (:candidate-label row) row])

              :true
              (for [row (sort-by :test-index predictions)]
                [(:test-index row) (:true-label row) row])

              :winning
              (for [row (sort-by :test-index predictions)]
                [(:test-index row) (:predicted-label row) row])

              (throw (ex-info "Unknown render mode"
                              {:mode mode
                               :allowed #{:winning :true :all}})))
            fragments
            (apply str
                   (for [[test-index candidate-label row] graph-items
                         :let [result (get graphs-by-key [test-index candidate-label])
                               expr (get-in result [:selected
                                                    (keyword (str "target" candidate-label))])
                               filename (io/file dir
                                                 (format "%s_test%d_label%d.png"
                                                         (safe-file-part (str/join "_" key))
                                                         (int test-index)
                                                         (int candidate-label)))
                               label (format "test=%d true=%s pred=%s rendered-label=%d"
                                             (int test-index)
                                             (:true-label row)
                                             (:predicted-label row)
                                             (int candidate-label))]]
                     (if expr
                       (render-expression-fragment! expr (.getPath filename) label)
                       (str "<h4>" (html-escape label) "</h4>"
                            "<p>No successful cached result for this test point/label.</p>"))))]
        (html-view
         (str "<div style='font:13px sans-serif;color:#111;background:#fff'>"
              "<h3>Cached condition</h3>"
              "<p><b>key:</b> " (html-escape (pr-str key)) "</p>"
              "<p><b>mode:</b> " (html-escape (name mode)) "</p>"
              (view-html
               (table-view (take 8 predictions)
                           {:columns [:test-index
                                      :true-label
                                      :predicted-label
                                      :correct
                                      :best-residual-dl
                                      :best-graph-dl
                                      :score-margin
                                      :tie-count
                                      :all-failed]
                            :title "prediction rows"
                            :key "cached-graph-predictions"}))
              fragments
              "</div>")
         {:title "cached graphs"
          :key "cached-graphs"})))))
