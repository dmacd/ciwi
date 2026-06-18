(ns ciwi.demos.house
  (:refer-clojure :exclude [line])
  (:require [ciwi.alice :as alice]
            [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.hashing :as hashing]
            [ciwi.operator.core :as operator]
            [ciwi.render.graph :as render-graph]
            [ciwi.render.movie :as movie]
            [ciwi.value :as value]
            [ciwi.wunderbaum :as wunderbaum]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.awt.image BufferedImage]
           [java.util IdentityHashMap]
           [javax.imageio ImageIO]))

(def red [255.0 100.0 100.0])
(def green [100.0 255.0 100.0])
(def blue [100.0 100.0 255.0])

(def image-shape [50 50 3])

(def ^:private mt-n 624)
(def ^:private mt-m 397)
(def ^:private matrix-a 0x9908b0df)
(def ^:private upper-mask 0x80000000)
(def ^:private lower-mask 0x7fffffff)
(def ^:private uint32-mask 0xffffffff)

(defn- uint32
  [x]
  (bit-and (long x) uint32-mask))

(defn random-state
  "Create a small NumPy RandomState-compatible MT19937 state."
  [seed]
  (let [mt (long-array mt-n)]
    (aset mt 0 (uint32 seed))
    (doseq [idx (range 1 mt-n)]
      (let [prev (aget mt (dec idx))
            mixed (bit-xor prev (unsigned-bit-shift-right prev 30))]
        (aset mt idx
              (uint32 (+ (* 1812433253 mixed) idx)))))
    {:mt mt
     :index (atom mt-n)}))

(defn- twist!
  [{:keys [^longs mt index]}]
  (doseq [idx (range mt-n)]
    (let [y (uint32 (bit-or (bit-and (aget mt idx) upper-mask)
                            (bit-and (aget mt (mod (inc idx) mt-n))
                                     lower-mask)))
          shifted (unsigned-bit-shift-right y 1)
          next-value (bit-xor (aget mt (mod (+ idx mt-m) mt-n))
                              shifted)
          next-value (if (odd? y)
                       (bit-xor next-value matrix-a)
                       next-value)]
      (aset mt idx (uint32 next-value))))
  (reset! index 0))

(defn- next-uint32!
  [{:keys [^longs mt index] :as state}]
  (when (>= @index mt-n)
    (twist! state))
  (let [idx @index
        _ (swap! index inc)
        y0 (aget mt idx)
        y1 (bit-xor y0 (unsigned-bit-shift-right y0 11))
        y2 (bit-xor y1 (bit-and (bit-shift-left y1 7) 0x9d2c5680))
        y3 (bit-xor y2 (bit-and (bit-shift-left y2 15) 0xefc60000))
        y4 (bit-xor y3 (unsigned-bit-shift-right y3 18))]
    (uint32 y4)))

(defn- next-double!
  [state]
  (let [a (unsigned-bit-shift-right (next-uint32! state) 5)
        b (unsigned-bit-shift-right (next-uint32! state) 6)]
    (/ (+ (* (double a) 67108864.0) (double b))
       9007199254740992.0)))

(defn standard-normal-seq
  "Return the first `n` NumPy RandomState-style standard normals for `seed`."
  [seed n]
  (let [state (random-state seed)]
    (loop [result []]
      (if (>= (count result) n)
        (subvec result 0 n)
        (let [u1 (- (* 2.0 (next-double! state)) 1.0)
              u2 (- (* 2.0 (next-double! state)) 1.0)
              radius-squared (+ (* u1 u1) (* u2 u2))]
          (if (or (zero? radius-squared)
                  (>= radius-squared 1.0))
            (recur result)
            (let [factor (Math/sqrt (/ (* -2.0 (Math/log radius-squared))
                                       radius-squared))]
              (recur (conj result (* u2 factor) (* u1 factor))))))))))

(defn- as-vector
  [x]
  (if (dense/ndarray? x)
    (vec (dense/ravel x))
    (vec x)))

(defn- point-values
  [point]
  (as-vector point))

(defn- color-values
  [color]
  (as-vector color))

(defn- point-list
  [points]
  (if (dense/ndarray? points)
    (mapv vec (partition 2 (dense/ravel points)))
    (mapv point-values points)))

(defn- colored-point-rows
  [colored-points]
  (cond
    (dense/ndarray? colored-points)
    (let [shape (dense/shape colored-points)]
      (when-not (and (= 2 (count shape))
                     (= 5 (second shape)))
        (throw (ex-info "Expected colored point list with rows [row col r g b]"
                        {:shape shape})))
      (mapv vec (partition 5 (dense/ravel colored-points))))

    :else
    (mapv (fn [entry]
            (if (map? entry)
              (let [[row column] (point-values (:point entry))
                    [r g b] (color-values (:color entry))]
                [(double row) (double column) r g b])
              (let [[row column r g b] (as-vector entry)]
                [(double row) (double column) r g b])))
          colored-points)))

(defn- colored-point-list?
  [x]
  (or (and (dense/ndarray? x)
           (= [5] (subvec (dense/shape x) 1)))
      (and (sequential? x)
           (seq x)
           (or (map? (first x))
               (= 5 (count (first x)))))))

(defn- colored-point-array
  [rows]
  (let [rows (vec rows)]
    (dense/from-flat (vec (mapcat identity rows))
                     [(count rows) 5]
                     {:dtype :float64})))

(defn- arange
  [start stop step]
  (let [more? (if (pos? step) < >)]
    (loop [x (double start)
           result []]
      (if (more? x (double stop))
        (recur (+ x step) (conj result x))
        result))))

(defn- round-range
  [a0 da step]
  (let [rounder (if (>= da 0.0) #(Math/floor %) #(Math/ceil %))]
    (mapv #(long (rounder %))
          (arange a0 (+ a0 da) step))))

(defn line
  "Return rasterized line points between two `[row col]` points."
  [p1 p2]
  (let [[p1 p2] (sort [(point-values p1) (point-values p2)])
        [y0 x0] p1
        dy0 (- (double (first p2)) (double y0))
        dx0 (- (double (second p2)) (double x0))
        dy (+ dy0 (if (zero? dy0) 1.0 (Math/signum dy0)))
        dx (+ dx0 (if (zero? dx0) 1.0 (Math/signum dx0)))
        sy0 (/ dy (Math/abs dx))
        sx0 (Math/signum dx)
        [sy sx] (if (< (Math/abs dx) (Math/abs dy))
                  [(Math/signum dy) (/ dx (Math/abs dy))]
                  [sy0 sx0])
        ys (round-range y0 dy sy)
        xs (round-range x0 dx sx)]
    (loop [remaining (map vector ys xs)
           result [[(long (Math/round (double y0)))
                    (long (Math/round (double x0)))]]]
      (if-let [p (first remaining)]
        (recur (rest remaining)
               (if (= p (peek result))
                 result
                 (conj result p)))
        result))))

(defn fill
  "Fill a polygon border using Python WILLIAM's line-scan filler."
  [border-points]
  (let [rows (->> (point-list border-points)
                  (group-by first)
                  (sort-by first))]
    (vec
     (mapcat (fn [[row points]]
               (let [columns (map second points)]
                 (mapv (fn [column] [(long row) (long column)])
                       (range (apply min columns)
                              (inc (apply max columns))))))
             rows))))

(defn dye
  "Attach one RGB color to every point."
  [color points]
  (let [[r g b] (mapv double (color-values color))
        rows (mapv (fn [point]
                     (let [[row column] (point-values point)]
                       [(double row) (double column) r g b]))
                   (point-list points))]
    (colored-point-array rows)))

(defn draw
  "Draw colored points onto an RGB background image."
  ([colored-points]
   (draw colored-points {}))
  ([colored-points {:keys [shape background]
                    :or {shape image-shape
                         background red}}]
   (let [[height width channels] shape
         background (color-values background)
         base (vec (mapcat identity
                           (repeat (* height width)
                                   (mapv double background))))
         flat (reduce (fn [flat [row column r g b]]
                        (let [color [r g b]
                              [row column] (map long [row column])]
                          (if (and (<= 0 row) (< row height)
                                   (<= 0 column) (< column width))
                            (reduce (fn [flat channel]
                                      (assoc flat
                                             (+ (* (+ (* row width) column)
                                                   channels)
                                                channel)
                                             (double (nth (color-values color) channel))))
                                    flat
                                    (range channels))
                            flat)))
                      base
                   (colored-point-rows colored-points))]
     (dense/from-flat flat shape {:dtype :float64}))))

(defn- draw-inverse-colored-points
  [image {:keys [shape background]
          :or {shape image-shape
               background red}}]
  (let [image (dense/asarray image)
        [height width channels] shape
        background (mapv double (color-values background))]
    (when (and (= shape (dense/shape image))
               (= channels (count background)))
      (let [flat (dense/ravel image)
            rows (vec
                  (for [row (range height)
                        column (range width)
                        :let [offset (* (+ (* row width) column) channels)
                              color (subvec flat offset (+ offset channels))]
                        :when (not= color background)]
                    (into [(double row) (double column)]
                          (map double color))))]
        (colored-point-array rows)))))

(defn concat-point-lists
  [left right]
  (vec (concat (point-list left) (point-list right))))

(defn concat-colored-point-lists
  [left right]
  (colored-point-array (concat (colored-point-rows left)
                               (colored-point-rows right))))

(defn- polygon-border
  [points]
  (vec (mapcat (fn [[p1 p2]]
                 (line p1 p2))
               (map vector points (concat (rest points) [(first points)])))))

(defn filled-polygon
  [points]
  (fill (polygon-border points)))

(def roof-points
  [[21 9] [21 39] [6 24]])

(def body-points
  [[21 9] [21 39] [45 39] [45 9]])

(defn base-house-image
  "Return the noiseless 50x50x3 RGB house image from the Python fixture."
  []
  (draw (concat-colored-point-lists
         (dye blue (filled-polygon roof-points))
         (dye green (filled-polygon body-points)))))

(defn- round2
  [x]
  (value/round-to-precision x 2))

(defn add-gaussian-noise
  "Add deterministic RandomState-style Gaussian noise and round to 2 decimals."
  ([image]
   (add-gaussian-noise image {}))
  ([image {:keys [seed scale]
           :or {seed 42
                scale 20.0}}]
   (let [image (dense/asarray image)
         shape (dense/shape image)
         flat (dense/ravel image)
         normals (standard-normal-seq seed (count flat))]
     (dense/from-flat (mapv (fn [pixel noise]
                              (round2 (+ (double pixel)
                                         (* (double scale) noise))))
                            flat
                            normals)
                      shape
                      {:dtype :float64}))))

(defn house-image
  "Return the noisy 50x50x3 RGB house image fixture."
  []
  (add-gaussian-noise (base-house-image)))

(defn house-fixture
  []
  {:image (house-image)
   :base-image (base-house-image)
   :roof-points roof-points
   :body-points body-points
   :colors {:red red
            :green green
            :blue blue}})

(defn- same-image?
  [left right]
  (let [left (dense/asarray left)
        right (dense/asarray right)]
    (and (= (dense/shape left) (dense/shape right))
         (every? (fn [[x y]]
                   (< (Math/abs (- (double x) (double y))) 1.0e-9))
                 (map vector (dense/ravel left) (dense/ravel right))))))

(declare spec-value)

(def point-add-operator
  (operator/operator
   {:id :point-add
    :conditions [[0] [1]]
    :call (fn [[point delta]]
            (spec-value (mapv + (point-values point) (as-vector delta))
                        :point))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   (case (first cond)
                     0 [[(spec-value (mapv - (point-values output)
                                            (point-values known))
                                      :vector)]]
                     1 [[(spec-value (mapv - (point-values output)
                                            (as-vector known))
                                      :point)]]
                     nil))))}))

(def line-operator
  (operator/operator
   {:id :line
    :commutative? true
    :call (fn [[p1 p2]]
            (spec-value (line p1 p2) :point-list))}))

(def fill-operator
  (operator/operator
   {:id :fill
    :call (fn [[points]]
            (spec-value (fill points) :point-list))}))

(def concat-operator
  (operator/operator
   {:id :concat
    :conditions [[0] [1]]
    :call (fn [[left right]]
            (let [colored? (or (colored-point-list? left)
                               (colored-point-list? right))
                  result (if colored?
                           (concat-colored-point-lists left right)
                           (concat-point-lists left right))
                  spec (if colored? :colored-point-list :point-list)]
              (spec-value result spec)))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)
                       colored? (colored-point-list? output)
                       output (vec (if colored?
                                     (colored-point-rows output)
                                     (point-list output)))
                       known (vec (if colored?
                                    (colored-point-rows known)
                                    (point-list known)))
                       spec (if colored? :colored-point-list :point-list)
                       make-result (fn [rows]
                                     (spec-value (if colored?
                                                   (colored-point-array rows)
                                                   (vec rows))
                                                 spec))]
                   (case (first cond)
                     0 (when (= known (subvec output 0 (count known)))
                         [[(make-result (subvec output (count known)))]])
                     1 (let [split (- (count output) (count known))]
                         (when (and (<= 0 split)
                                    (= known (subvec output split)))
                           [[(make-result (subvec output 0 split))]]))
                     nil))))}))

(def dye-operator
  (operator/operator
   {:id :dye
    :conditions [[0] [1]]
    :call (fn [[color points]]
            (spec-value (dye color points) :colored-point-list))
    :inverse (fn [output cond-inputs cond]
               (case (vec cond)
                 [0] (let [color (mapv double (color-values (first cond-inputs)))
                           rows (colored-point-rows output)
                           points (keep (fn [[row column r g b]]
                                          (when (= color [r g b])
                                            [(long row) (long column)]))
                                        rows)]
                       (when (= (count points) (count rows))
                         [[(spec-value (vec points) :point-list)]]))
                 [1] (let [points (point-list (first cond-inputs))
                           rows (colored-point-rows output)]
                       (when (and (seq rows)
                                  (= (count points) (count rows))
                                  (every? true?
                                          (map (fn [point [row column]]
                                                 (= (mapv long point)
                                                    [(long row) (long column)]))
                                               points
                                               rows)))
                         (let [colors (mapv (fn [[_row _column r g b]]
                                               [r g b])
                                             rows)
                               color (first colors)]
                           (when (every? #{color} colors)
                             [[(spec-value color :color)]]))))
                 nil))}))

(defn draw-operator
  ([] (draw-operator {}))
  ([opts]
   (operator/operator
    {:id :draw
     :conditions [[]]
     :call (fn [[colored-points]]
             (spec-value (draw colored-points opts) :rgb-image))
     :inverse (fn [output cond-inputs cond]
                (case (vec cond)
                  [] (when-let [colored-points
                                (draw-inverse-colored-points output opts)]
                       [[(spec-value colored-points :colored-point-list)]])
                  [0] (let [colored-points (first cond-inputs)]
                        (when (same-image? output (draw colored-points opts))
                          [[]]))
                  nil))})))

(def image-add-operator
  (operator/operator
   {:id :add
    :conditions [[0] [1]]
    :commutative? true
    :call (fn [[left right]]
            (spec-value (dense/add left right) :rgb-image))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)]
                   [[(spec-value (dense/subtract output known)
                                 :rgb-image)]])))}))

(defn registry
  "Return the house demo operator registry."
  []
  {:point-add point-add-operator
   :line line-operator
   :fill fill-operator
   :concat concat-operator
   :dye dye-operator
   :draw (draw-operator)
   :add image-add-operator})

(defn operator-declarations
  "Return demo-local Wunderbaum declarations for the house primitive basis."
  []
  (let [operator-dl (Math/ceil (value/jelias 7))]
    [{:op :point-add
      :input-specs [:point :vector]
      :output-spec :point
      :dl operator-dl}
     {:op :line
      :input-specs [:point :point]
      :output-spec :point-list
      :dl operator-dl}
     {:op :fill
      :input-specs [:point-list]
      :output-spec :point-list
      :dl operator-dl}
     {:op :concat
      :input-specs [:point-list :point-list]
      :output-spec :point-list
      :dl operator-dl}
     {:op :concat
      :input-specs [:colored-point-list :colored-point-list]
      :output-spec :colored-point-list
      :dl operator-dl}
     {:op :dye
      :input-specs [:color :point-list]
      :output-spec :colored-point-list
      :dl operator-dl}
     {:op :draw
      :input-specs [:colored-point-list]
      :output-spec :rgb-image
      :dl operator-dl}
     {:op :add
      :input-specs [:rgb-image :rgb-image]
      :output-spec :rgb-image
      :dl operator-dl}]))

(defn house-value
  []
  (value/value (house-image) {:spec :rgb-image
                              :permeable? false}))

(defn- spec-value
  [data spec]
  (value/value data {:spec spec}))

(defn free-values
  "Return the low-level constants used by the guided house demo."
  []
  (vec
   (concat [(spec-value red :color)
            (spec-value green :color)
            (spec-value blue :color)]
           (map #(spec-value % :point)
                (distinct (concat roof-points body-points))))))

(defn- spec-data
  [data spec]
  (value/datum (spec-value data spec)))

(defn- fingerprint
  [data]
  (hashing/content-fingerprint data))

(defn- cached-fingerprint
  [^IdentityHashMap cache data]
  (if-not cache
    (fingerprint data)
    (locking cache
      (if (.containsKey cache data)
        (.get cache data)
        (let [fp (fingerprint data)]
          (.put cache data fp)
          fp)))))

(defn- concat-point-list-data
  [& point-lists]
  (spec-data (vec (mapcat point-list point-lists)) :point-list))

(defn- memory-data
  [memory id]
  (value/datum (:value (get memory id))))

(defn- graph-value-data
  [g id]
  (value/datum (get-in g [:nodes id :value])))

(defn- canonical-solution*
  []
  (let [p-a (spec-data [21 9] :point)
        p-b (spec-data [21 39] :point)
        p-c (spec-data [6 24] :point)
        p-d (spec-data [45 39] :point)
        p-e (spec-data [45 9] :point)
        top (spec-data (line [21 9] [21 39]) :point-list)
        roof-right (spec-data (line [21 39] [6 24]) :point-list)
        roof-left (spec-data (line [6 24] [21 9]) :point-list)
        body-right (spec-data (line [21 39] [45 39]) :point-list)
        body-bottom (spec-data (line [45 39] [45 9]) :point-list)
        body-left (spec-data (line [45 9] [21 9]) :point-list)
        roof12 (concat-point-list-data top roof-right)
        roof123 (concat-point-list-data top roof-right roof-left)
        body12 (concat-point-list-data top body-right)
        body123 (concat-point-list-data top body-right body-bottom)
        body1234 (concat-point-list-data top body-right body-bottom body-left)
        roof-fill (spec-data (fill roof123) :point-list)
        body-fill (spec-data (fill body1234) :point-list)
        roof-colored (spec-data (dye blue roof-fill) :colored-point-list)
        body-colored (spec-data (dye green body-fill) :colored-point-list)
        colored-combo (spec-data (concat-colored-point-lists roof-colored
                                                             body-colored)
                                 :colored-point-list)
        base (spec-data (base-house-image) :rgb-image)
        target (spec-data (house-image) :rgb-image)
        step-order [:line-top
                    :line-roof-right
                    :line-roof-left
                    :concat-roof12
                    :concat-roof123
                    :fill-roof
                    :dye-roof
                    :line-body-right
                    :line-body-bottom
                    :line-body-left
                    :concat-body12
                    :concat-body123
                    :concat-body1234
                    :fill-body
                    :dye-body
                    :concat-colored
                    :draw-base
                    :add-target]
        step-inputs {:line-top {:op :line
                                :inputs [p-a p-b]
                                :commutative? true}
                     :line-roof-right {:op :line
                                       :inputs [p-b p-c]
                                       :commutative? true}
                     :line-roof-left {:op :line
                                      :inputs [p-c p-a]
                                      :commutative? true}
                     :concat-roof12 {:op :concat
                                     :inputs [top roof-right]}
                     :concat-roof123 {:op :concat
                                      :inputs [roof12 roof-left]}
                     :fill-roof {:op :fill
                                 :inputs [roof123]}
                     :dye-roof {:op :dye
                                :inputs [(spec-data blue :color) roof-fill]}
                     :line-body-right {:op :line
                                       :inputs [p-b p-d]
                                       :commutative? true}
                     :line-body-bottom {:op :line
                                        :inputs [p-d p-e]
                                        :commutative? true}
                     :line-body-left {:op :line
                                      :inputs [p-e p-a]
                                      :commutative? true}
                     :concat-body12 {:op :concat
                                     :inputs [top body-right]}
                     :concat-body123 {:op :concat
                                      :inputs [body12 body-bottom]}
                     :concat-body1234 {:op :concat
                                       :inputs [body123 body-left]}
                     :fill-body {:op :fill
                                 :inputs [body1234]}
                     :dye-body {:op :dye
                                :inputs [(spec-data green :color) body-fill]}
                     :concat-colored {:op :concat
                                      :inputs [roof-colored body-colored]}
                     :draw-base {:op :draw
                                 :inputs [colored-combo]}
                     :add-target {:op :add
                                  :inputs [target base]
                                  :commutative? true}}
        step-by-output {[:line (fingerprint top)] :line-top
                        [:line (fingerprint roof-right)] :line-roof-right
                        [:line (fingerprint roof-left)] :line-roof-left
                        [:concat (fingerprint roof12)] :concat-roof12
                        [:concat (fingerprint roof123)] :concat-roof123
                        [:fill (fingerprint roof-fill)] :fill-roof
                        [:dye (fingerprint roof-colored)] :dye-roof
                        [:line (fingerprint body-right)] :line-body-right
                        [:line (fingerprint body-bottom)] :line-body-bottom
                        [:line (fingerprint body-left)] :line-body-left
                        [:concat (fingerprint body12)] :concat-body12
                        [:concat (fingerprint body123)] :concat-body123
                        [:concat (fingerprint body1234)] :concat-body1234
                        [:fill (fingerprint body-fill)] :fill-body
                        [:dye (fingerprint body-colored)] :dye-body
                        [:concat (fingerprint colored-combo)] :concat-colored
                        [:draw (fingerprint base)] :draw-base
                        [:add (fingerprint target)] :add-target}]
    {:step-order step-order
     :step-inputs step-inputs
     :step-by-output step-by-output
     :target target
     :base base
     :roof-colored roof-colored
     :body-colored body-colored
     :colored-combo colored-combo}))

(def ^:private canonical-solution
  (delay (canonical-solution*)))

(defn- operator-output-step
  [g op-id]
  (let [node (graph/node g op-id)
        op-id* (:id (:operator node))
        output (graph-value-data g (:parent node))]
    (get (:step-by-output @canonical-solution)
         [op-id* (fingerprint output)])))

(defn prefix-steps
  "Return the canonical house solution prefix steps represented by `g`.

  Returns nil when the graph contains an operator outside the guided prefix.
  "
  [g]
  (let [steps (mapv #(operator-output-step g %) (graph/operator-ids g))
        step-set (set steps)
        k (count steps)
        expected-set (set (take k (:step-order @canonical-solution)))]
    (when (and (= k (count step-set))
               (every? some? steps)
               (= expected-set step-set))
      (vec (take k (:step-order @canonical-solution))))))

(defn solution-prefix?
  "Native guide for the house demo's intended low-level primitive expression."
  [summary]
  (boolean (prefix-steps (:graph summary))))

(defn- next-step
  [g]
  (let [steps (or (prefix-steps g) [])
        order (:step-order @canonical-solution)]
    (nth order (count steps) nil)))

(defn- expected-input-fingerprints
  ([step]
   (expected-input-fingerprints step nil))
  ([step cache]
   (mapv #(cached-fingerprint cache %)
         (get-in @canonical-solution [:step-inputs step :inputs]))))

(defn- node-fingerprint
  ([memory id]
   (node-fingerprint nil memory id))
  ([cache memory id]
   (cached-fingerprint cache (memory-data memory id))))

(defn- same-inputs?
  ([memory node-ids expected-fps commutative?]
   (same-inputs? nil memory node-ids expected-fps commutative?))
  ([cache memory node-ids expected-fps commutative?]
   (let [actual (mapv #(node-fingerprint cache memory %) node-ids)]
    (if commutative?
      (= (set expected-fps) (set actual))
      (= expected-fps actual)))))

(defn solution-frontier?
  "Pre-materialization version of the guided house solution-prefix predicate."
  ([info]
   (solution-frontier? info nil))
  ([{:keys [graph memory nodes element]} cache]
  (let [step (next-step graph)
        {:keys [op inputs commutative?]} (get-in @canonical-solution
                                                 [:step-inputs step])
        expected-fps (mapv #(cached-fingerprint cache %) inputs)]
    (and step
         (= op (:id (:operator element)))
         (case step
           (:line-top :line-roof-right :line-roof-left
            :line-body-right :line-body-bottom :line-body-left)
           (and (= [0 1] (:gen-cond element))
                (same-inputs? cache memory nodes expected-fps true))

           :add-target
           (and (some #{-1} (:gen-cond element))
                (same-inputs? cache memory nodes expected-fps true))

           (same-inputs? cache memory nodes expected-fps commutative?))))))

(defn preferred-prefix-nodes
  "Return value nodes that should be tried first for the next guided step."
  ([info]
   (preferred-prefix-nodes info nil))
  ([{:keys [graph memory]} cache]
  (let [step (next-step graph)
        expected (set (expected-input-fingerprints step cache))]
    (vec
     (keep (fn [[id entry]]
             (when (contains? expected
                              (cached-fingerprint cache
                                                  (value/datum (:value entry))))
               id))
           memory)))))

(defn guided-operator-declarations
  "Return house declarations with a demo-local search prior.

  Line creation remains the expensive primitive; composition/rendering
  operators are cheap so the guided prefix keeps building the current shape
  before opening unrelated branches.
  "
  []
  (let [dl-by-op {:line 1.0
                  :point-add 4.0
                  :concat 0.05
                  :fill 0.05
                  :dye 0.05
                  :draw 0.05
                  :add 0.05}]
    (mapv (fn [declaration]
            (assoc declaration :dl (get dl-by-op (:op declaration) 1.0)))
          (operator-declarations))))

(defn guided-options
  []
  (let [guide-fingerprint-cache (IdentityHashMap.)]
    {:registry (registry)
     :ops-with-counts (guided-operator-declarations)
     :max-dag-dl 20
     :max-popped 2000
     :max-node-tuples 32
     :max-yields 50
     :allow-multiple-op-roots? true
     :recent-roots-first? true
     :candidate-predicate solution-prefix?
     :frontier-predicate #(solution-frontier? % guide-fingerprint-cache)
     :preferred-node-fn #(preferred-prefix-nodes % guide-fingerprint-cache)}))

(defn unguided-options
  "Return house demo options without solution-prefix guidance.

  This keeps the same primitive basis and generic operator DL schedule as the
  guided run, but removes the native solution predicate and tuple scheduler.
  "
  []
  {:registry (registry)
   :ops-with-counts (guided-operator-declarations)
   :max-dag-dl 20
   :max-popped 2000
   :max-node-tuples 1000
   :max-yields 50
   :allow-multiple-op-roots? true})

(defn- with-search-stats
  [opts]
  (if-let [stats (:wunderbaum-stats-atom opts)]
    [opts stats]
    (if (:collect-wunderbaum-stats? opts)
      (let [stats (atom {})]
        [(assoc opts :wunderbaum-stats-atom stats) stats])
      [opts nil])))

(defn- attach-search-stats
  [result stats]
  (cond-> result
    stats (assoc :wunderbaum-stats @stats)))

(defn- parallel-search?
  [opts]
  (> (long (or (:parallelism opts)
               (:num-workers opts)
               1))
     1))

(defn- candidate-seq
  [wb inputs opts]
  (if (parallel-search? opts)
    (case (:parallel-strategy opts)
      :global-best-first
      (wunderbaum/iterate-global-best-first wb inputs opts)

      nil
      (wunderbaum/iterate-parallel wb inputs opts)

      :partitioned
      (wunderbaum/iterate-parallel wb inputs opts)

      (throw (ex-info "Unknown Wunderbaum parallel strategy"
                      {:parallel-strategy (:parallel-strategy opts)
                       :allowed #{:partitioned :global-best-first}})))
    (wunderbaum/iterate wb inputs opts)))

(defn run-guided-compression
  "Run the guided house compression search with the demo-local primitive basis."
  ([]
   (run-guided-compression {}))
  ([opts]
   (let [opts (merge (guided-options) opts)
         [opts stats] (with-search-stats opts)
         target (house-value)
         inputs (into [target] (free-values))
         wb (wunderbaum/wunderbaum opts)
         initial-dl (value/desc-len target)
         min-compression-rate (double (or (:min-compression-rate opts) 0.01))
         threshold-dl (* initial-dl (- 1.0 min-compression-rate))
         realize-selected? (boolean (:realize-selected? opts))
         prefixes (when (:collect-prefixes? opts) (atom []))
         prefix-limit (long (or (:prefix-limit opts) 64))
         t0 (System/nanoTime)
         candidates (candidate-seq wb inputs opts)]
     (loop [remaining candidates
            consumed 0
            last-prefix nil]
       (if-let [candidate (first remaining)]
         (let [consumed (inc consumed)
               last-prefix candidate
               _ (when (and prefixes (< (count @prefixes) prefix-limit))
                   (swap! prefixes conj candidate))
               rate (alice/compression-rate initial-dl (:dl candidate))]
           (if (>= rate min-compression-rate)
             (let [candidate (cond-> candidate
                               realize-selected?
                               wunderbaum/realize-selected)]
               (attach-search-stats
                (cond-> {:candidate candidate
                         :initial-dl initial-dl
                         :dl (:dl candidate)
                         :compression-rate rate
                         :candidates-consumed consumed
                         :stop-reason :threshold-reached
                         :search-elapsed-ms (/ (double (- (System/nanoTime) t0))
                                               1000000.0)
                         :prefix-steps (prefix-steps (:graph candidate))}
                  realize-selected?
                  (assoc :selected (get (:selected candidate) :target0))

                  prefixes (assoc :prefixes @prefixes))
                stats))
             (recur (rest remaining) consumed last-prefix)))
         (attach-search-stats
          (cond-> {:candidate nil
                   :initial-dl initial-dl
                   :candidates-consumed consumed
                   :stop-reason :exhausted
                   :compression-rate 0.0
                   :search-elapsed-ms (/ (double (- (System/nanoTime) t0))
                                         1000000.0)
                   :last-prefix last-prefix
                   :prefix-steps (some-> last-prefix :graph prefix-steps)}
            prefixes (assoc :prefixes @prefixes))
          stats))))))

(defn run-unguided-compression
  "Run the house compression search without solution-prefix guidance.

  Returns the first candidate that reaches `:min-compression-rate` when one is
  found, otherwise returns the best yielded candidate seen under the supplied
  bounds. This is a baseline runner for the unguided milestone; it does not add
  recognizers, proposals, or task-specific operators.
  "
  ([]
   (run-unguided-compression {}))
  ([opts]
   (let [opts (merge (unguided-options) opts)
         [opts stats] (with-search-stats opts)
         target (house-value)
         inputs (into [target] (free-values))
         wb (wunderbaum/wunderbaum opts)
         initial-dl (value/desc-len target)
         min-compression-rate (double (or (:min-compression-rate opts) 0.01))
         collected (when (:collect-candidates? opts) (atom []))
         candidate-limit (long (or (:candidate-limit opts) 64))
         t0 (System/nanoTime)
         candidates (candidate-seq wb inputs opts)]
     (loop [remaining candidates
            consumed 0
            best nil]
       (if-let [candidate (first remaining)]
         (let [consumed (inc consumed)
               rate (alice/compression-rate initial-dl (:dl candidate))
               candidate (assoc candidate :compression-rate rate)
               best (if (or (nil? best)
                            (< (:dl candidate) (:dl best)))
                      candidate
                      best)
               _ (when (and collected (< (count @collected) candidate-limit))
                   (swap! collected conj candidate))]
           (if (>= rate min-compression-rate)
             (attach-search-stats
              (cond-> {:candidate candidate
                       :best best
                       :initial-dl initial-dl
                       :dl (:dl candidate)
                       :compression-rate rate
                       :candidates-consumed consumed
                       :stop-reason :threshold-reached
                       :search-elapsed-ms (/ (double (- (System/nanoTime) t0))
                                             1000000.0)}
                collected (assoc :candidates @collected))
              stats)
             (recur (rest remaining) consumed best)))
         (attach-search-stats
          (cond-> {:candidate nil
                   :best best
                   :initial-dl initial-dl
                   :dl (:dl best)
                   :compression-rate (double (or (:compression-rate best) 0.0))
                   :candidates-consumed consumed
                   :stop-reason :exhausted
                   :search-elapsed-ms (/ (double (- (System/nanoTime) t0))
                                         1000000.0)}
            collected (assoc :candidates @collected))
          stats))))))

(defn- clamp-byte
  [x]
  (long (max 0 (min 255 (Math/round (double x))))))

(defn write-image-png!
  "Write an RGB image value to PNG and return the output path."
  [image path]
  (let [image (dense/asarray image)
        [height width channels] (dense/shape image)
        flat (dense/ravel image)
        file (io/file path)
        buffered (BufferedImage. width height BufferedImage/TYPE_INT_RGB)]
    (when-not (= 3 channels)
      (throw (ex-info "Expected RGB image" {:shape (dense/shape image)})))
    (io/make-parents file)
    (doseq [row (range height)
            column (range width)]
      (let [idx (* (+ (* row width) column) channels)
            r (clamp-byte (nth flat idx))
            g (clamp-byte (nth flat (inc idx)))
            b (clamp-byte (nth flat (+ idx 2)))
            rgb (bit-or (bit-shift-left r 16)
                        (bit-shift-left g 8)
                        b)]
        (.setRGB buffered column row rgb)))
    (ImageIO/write buffered "png" file)
    (.getPath file)))

(defn- write-edn!
  [data path]
  (let [file (io/file path)]
    (io/make-parents file)
    (spit file (pr-str data))
    (.getPath file)))

(defn- write-text!
  [text path]
  (let [file (io/file path)]
    (io/make-parents file)
    (spit file text)
    (.getPath file)))

(defn- clear-frame-dir!
  [dir]
  (let [dir (io/file dir)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (when (.isDirectory file)
          (throw (ex-info "Refusing to delete nested frame directory"
                          {:path (.getPath file)})))
        (io/delete-file file true)))
    (.mkdirs dir)
    dir))

(defn- result-stats
  [result]
  (select-keys result
               [:initial-dl
                :dl
                :compression-rate
                :candidates-consumed
                :stop-reason
                :search-elapsed-ms
                :prefix-steps
                :wunderbaum-stats]))

(defn- prefix-label
  [idx summary]
  (str "house guided partial graph "
       (inc (long idx))
       "\n"
       "found steps="
       (pr-str (prefix-steps (:graph summary)))))

(defn- line-step-points
  [step]
  (case step
    :line-top (line [21 9] [21 39])
    :line-roof-right (line [21 39] [6 24])
    :line-roof-left (line [6 24] [21 9])
    :line-body-right (line [21 39] [45 39])
    :line-body-bottom (line [45 39] [45 9])
    :line-body-left (line [45 9] [21 9])
    []))

(defn- available-lines
  [steps line-steps]
  (vec (mapcat line-step-points
               (filter (set steps) line-steps))))

(defn- roof-preview-points
  [steps]
  (let [steps (set steps)]
    (cond
      (or (contains? steps :fill-roof)
          (contains? steps :dye-roof)
          (contains? steps :concat-colored)
          (contains? steps :draw-base)
          (contains? steps :add-target))
      (filled-polygon roof-points)

      (contains? steps :concat-roof123)
      (available-lines steps [:line-top :line-roof-right :line-roof-left])

      (contains? steps :concat-roof12)
      (available-lines steps [:line-top :line-roof-right])

      :else
      (available-lines steps [:line-top :line-roof-right :line-roof-left]))))

(defn- body-preview-points
  [steps]
  (let [steps (set steps)]
    (cond
      (or (contains? steps :fill-body)
          (contains? steps :dye-body)
          (contains? steps :concat-colored)
          (contains? steps :draw-base)
          (contains? steps :add-target))
      (filled-polygon body-points)

      (contains? steps :concat-body1234)
      (available-lines steps [:line-top
                              :line-body-right
                              :line-body-bottom
                              :line-body-left])

      (contains? steps :concat-body123)
      (available-lines steps [:line-top :line-body-right :line-body-bottom])

      (contains? steps :concat-body12)
      (available-lines steps [:line-top :line-body-right])

      :else
      (available-lines steps [:line-body-right
                              :line-body-bottom
                              :line-body-left]))))

(defn prefix-preview-image
  "Return the image-frame preview for a guided partial house graph.

  Before the graph contains an actual `draw` image value, this intentionally
  visualizes discovered geometry over the red background. It is a demo preview,
  not a recognizer or a replacement for graph-level reconstruction.
  "
  [summary]
  (let [steps (set (prefix-steps (:graph summary)))]
    (cond
      (contains? steps :add-target)
      (:target @canonical-solution)

      (contains? steps :draw-base)
      (:base @canonical-solution)

      :else
      (draw (concat-colored-point-lists
             (dye blue (roof-preview-points steps))
             (dye green (body-preview-points steps)))))))

(defn- prefix-image
  [summary]
  (prefix-preview-image summary))

(defn- artifact-readme
  [result prefixes graph-frame-count]
  (let [complete? (= :threshold-reached (:stop-reason result))]
    (str/join
     "\n"
     ["# Guided House Demo Artifacts"
      ""
      (if complete?
        "These frames are a completed guided compression run for the current low-level house expression."
        "These frames are a bounded guided partial run, not a completed house compression yet.")
      "The guide accepts only graphs whose operators are the first N operations of the intended low-level expression. In earlier notes this was called prefix discovery; here it means partial expression discovery."
      ""
      "## Graph Frames"
      ""
      "- A box is a value node. A leading `*` marks a permeable/free value."
      "- An oval/table is an operator option: one possible way to describe its parent value."
      "- Read edges as `value -> operator option -> child values`. Operator ports `arg0`, `arg1`, etc. name the child positions."
      "- Blue edges/operators are the MDL-selected option path for the rendered roots. Gray options are present but not selected by the current graph-level DL choice."
      "- Dashed rounded boxes mark frontier leaves: values that still have no option underneath them and are encoded directly if the selected expression stops there."
      "- The table at the top follows the Python renderer convention: section DL, leaves DL, model DL, max leaf DL, node counts, frontier leaf counts, and graph depth."
      ""
      "## Image Frames"
      ""
      "- Image frames are partial-reconstruction previews. Before the graph reaches `draw`, the demo overlays the discovered roof/body geometry on the red background so progress is visible."
      "- Preview roof/body colors come from the guided demo's intended expression. They do not add a recognizer or proposal shortcut to search."
      "- Once `draw` or `add` is represented, the frame switches to the actual image value for that step."
      ""
      "## Current Run"
      ""
      (str "- Stop reason: `" (:stop-reason result) "`.")
      (str "- Candidates consumed: " (:candidates-consumed result) ".")
      (str "- Compression rate: " (format "%.4f" (double (:compression-rate result))) ".")
      (str "- Graph steps written: " (count prefixes) ".")
      (str "- Graph movie frames written: " graph-frame-count ".")
      (str "- Found steps: `" (pr-str (:prefix-steps result)) "`.")])))

(defn write-guided-artifacts!
  "Write stats, graph frames, image frames, and optional movies for a run."
  ([result output-dir]
   (write-guided-artifacts! result output-dir {}))
  ([result output-dir {:keys [framerate movies?]
                       :or {framerate 2
                            movies? true}}]
   (let [output-dir (io/file output-dir)
         prefixes (or (seq (:prefixes result))
                      (some-> (:candidate result) vector)
                      (some-> (:last-prefix result) vector)
                      [])
         graph-prefixes (vec (map-indexed (fn [frame-idx prefix-idx]
                                             [frame-idx
                                              prefix-idx
                                              (nth prefixes prefix-idx)])
                                           (range (count prefixes))))
         graph-frame-dir (io/file output-dir "graph-frames")
         image-frame-dir (io/file output-dir "image-frames")
         stats-path (.getPath (io/file output-dir "stats.edn"))
         readme-path (.getPath (io/file output-dir "README.md"))
         graph-movie-path (.getPath (io/file output-dir "graph-prefixes.mp4"))
         image-movie-path (.getPath (io/file output-dir "image-prefixes.mp4"))]
     (write-edn! (assoc (result-stats result)
                        :prefix-count (count prefixes)
                        :graph-frame-count (count graph-prefixes)
                        :candidate? (boolean (:candidate result)))
                 stats-path)
     (clear-frame-dir! graph-frame-dir)
     (clear-frame-dir! image-frame-dir)
     (write-text! (artifact-readme result prefixes (count graph-prefixes))
                  readme-path)
     (doseq [[frame-idx prefix-idx summary] graph-prefixes]
       (movie/write-graph-frame! (.getPath graph-frame-dir)
                                 frame-idx
                                 (:graph summary)
                                 {:label (prefix-label prefix-idx summary)
                                  :label-option-edges? true}))
     (doseq [[idx summary] (map-indexed vector prefixes)]
       (write-image-png! (prefix-image summary)
                         (movie/frame-path (.getPath image-frame-dir) idx)))
     {:stats-path stats-path
      :readme-path readme-path
      :graph-frame-dir (.getPath graph-frame-dir)
      :image-frame-dir (.getPath image-frame-dir)
      :graph-movie (when (and movies? (seq prefixes))
                     (movie/frames->mp4! (.getPath graph-frame-dir)
                                         graph-movie-path
                                         {:framerate framerate}))
      :image-movie (when (and movies? (seq prefixes))
                     (movie/frames->mp4! (.getPath image-frame-dir)
                                         image-movie-path
                                         {:framerate framerate}))})))

(defn run-guided-demo!
  "Run the bounded guided house demo and write prefix artifacts."
  ([]
   (run-guided-demo! {}))
  ([{:keys [output-dir]
     :or {output-dir "target/house-guided"}
     :as opts}]
   (let [result (run-guided-compression
                 (merge {:collect-prefixes? true
                         :prefix-limit 64}
                        (dissoc opts :output-dir)))
         artifacts (write-guided-artifacts! result output-dir)]
     (assoc result :artifacts artifacts))))

(defn write-candidate-artifacts!
  "Write graph and image artifacts for a house compression candidate."
  [candidate output-dir]
  (let [output-dir (io/file output-dir)
        graph-result (render-graph/render-png! (:graph candidate)
                                               (.getPath (io/file output-dir
                                                                  "graph.png"))
                                               {:label "house guided candidate"})]
    (doseq [[id node] (:nodes (:graph candidate))
            :when (and (= :value (:kind node))
                       (= :rgb-image (:spec (:value node))))]
      (write-image-png! (:data (:value node))
                        (.getPath (io/file output-dir
                                           (str "image-" (name id) ".png")))))
    graph-result))
