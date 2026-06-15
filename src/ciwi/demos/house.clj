(ns ciwi.demos.house
  (:refer-clojure :exclude [line])
  (:require [ciwi.alice.wunderbaum :as alice-wb]
            [ciwi.dense.core :as dense]
            [ciwi.operator.core :as operator]
            [ciwi.render.graph :as render-graph]
            [ciwi.value :as value]
            [clojure.java.io :as io])
  (:import [java.awt.image BufferedImage]
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
  (let [color (mapv double (color-values color))]
    (mapv (fn [point]
            {:color color
             :point (point-values point)})
          (point-list points))))

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
         flat (reduce (fn [flat {:keys [color point]}]
                        (let [[row column] (map long (point-values point))]
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
                      colored-points)]
     (dense/from-flat flat shape {:dtype :float64}))))

(defn concat-point-lists
  [left right]
  (vec (concat (point-list left) (point-list right))))

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
  (draw (concat (dye blue (filled-polygon roof-points))
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
            (let [colored? (or (and (sequential? left)
                                    (seq left)
                                    (map? (first left)))
                               (and (sequential? right)
                                    (seq right)
                                    (map? (first right))))
                  result (if colored?
                           (vec (concat left right))
                           (concat-point-lists left right))
                  spec (if (and (seq result)
                                (map? (first result))
                                (contains? (first result) :color))
                         :colored-point-list
                         :point-list)]
              (spec-value result spec)))
    :inverse (fn [output cond-inputs cond]
               (when (= 1 (count cond))
                 (let [known (first cond-inputs)
                       output (vec output)
                       known (vec known)]
                   (case (first cond)
                     0 (when (= known (subvec output 0 (count known)))
                         [[(spec-value (subvec output (count known))
                                       (if (and (seq output)
                                                (map? (first output)))
                                         :colored-point-list
                                         :point-list))]])
                     1 (let [split (- (count output) (count known))]
                         (when (and (<= 0 split)
                                    (= known (subvec output split)))
                           [[(spec-value (subvec output 0 split)
                                         (if (and (seq output)
                                                  (map? (first output)))
                                           :colored-point-list
                                           :point-list))]]))
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
                           points (keep (fn [{point-color :color
                                              :keys [point]}]
                                          (when (= color (mapv double
                                                               (color-values point-color)))
                                            point))
                                        output)]
                       (when (= (count points) (count output))
                         [[(spec-value (vec points) :point-list)]]))
                 nil))}))

(defn draw-operator
  ([] (draw-operator {}))
  ([opts]
   (operator/operator
    {:id :draw
     :conditions [[0]]
     :call (fn [[colored-points]]
             (spec-value (draw colored-points opts) :rgb-image))
     :inverse (fn [output cond-inputs cond]
                (when (= [0] (vec cond))
                  (let [colored-points (first cond-inputs)]
                    (when (same-image? output (draw colored-points opts))
                      [[]]))))})))

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

(defn- op-ids
  [summary]
  (set (keep (fn [[_ node]]
               (when (= :operator (:kind node))
                 (:id (:operator node))))
             (:nodes (:graph summary)))))

(defn solution-prefix?
  "Guide the demo toward image residual plus rendered colored geometry."
  [summary]
  (let [ids (op-ids summary)]
    (every? ids [:add :draw :dye :fill :line])))

(defn guided-options
  []
  {:registry (registry)
   :ops-with-counts (operator-declarations)
   :max-dag-dl 40
   :max-popped 5000
   :max-yields 20
   :candidate-predicate solution-prefix?})

(defn run-guided-compression
  "Run the guided house compression step with the demo-local primitive basis."
  ([]
   (run-guided-compression {}))
  ([opts]
   (alice-wb/compression-step-candidate
    (house-value)
    (free-values)
    (merge (guided-options) opts))))

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
