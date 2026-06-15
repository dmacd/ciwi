(ns ciwi.render.graph
  (:require [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.value :as value]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- dot-escape
  [x]
  (-> (str x)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- dot-id
  [prefix id]
  (str (name prefix) "_" (dot-escape (pr-str id))))

(defn- attr
  [[k v]]
  (str (name k) "=\"" (dot-escape v) "\""))

(defn- attrs
  [m]
  (str "[" (str/join ", " (map attr m)) "]"))

(defn- format-dl
  [x]
  (when (number? x)
    (format "%.3f" (double x))))

(defn- take-summary
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- dense-summary
  [x]
  (let [shape (str/join "x" (dense/shape x))
        flat (dense/ravel x)
        sample (take 6 flat)]
    (str "array<" (name (dense/dtype x)) ">[" shape "] "
         (take-summary (pr-str (vec sample)) 80))))

(defn value-summary
  "Return a compact one-line summary of a graph value."
  [x]
  (let [x (value/datum x)]
    (cond
      (dense/ndarray? x)
      (dense-summary x)

      (vector? x)
      (str "vector[" (count x) "] " (take-summary (pr-str (take 8 x)) 80))

      (seq? x)
      (str "seq " (take-summary (pr-str (take 8 x)) 80))

      :else
      (take-summary (pr-str x) 100))))

(defn- selected-operator-ids
  [g opts]
  (set (or (:selected-operator-ids opts)
           (when (not= false (:show-selected? opts))
             (try
               (mapcat #(mdl/selected-operators g % {}) (graph/roots g))
               (catch Exception _
                 []))))))

(defn- graph-label
  [g opts]
  (let [provided (:label opts)
        dl (when (not= false (:show-dl? opts))
             (try (mdl/graph-dl g {}) (catch Exception _ nil)))
        parts (cond-> []
                provided (conj provided)
                dl (conj (str "graph dl=" (format-dl dl)))
                (:compression-rate opts)
                (conj (str "compression="
                           (format "%.4f" (double (:compression-rate opts))))))]
    (when (seq parts)
      (str/join "\n" parts))))

(defn- node-order
  [g]
  (sort-by (comp pr-str first) (:nodes g)))

(defn- value-node-label
  [g id node opts]
  (let [raw-dl (when (not= false (:show-dl? opts))
                 (try (format-dl (value/desc-len (:value node)))
                      (catch Exception _ nil)))
        best-dl (when (not= false (:show-dl? opts))
                  (try (format-dl (:dl (mdl/node-dl g id {})))
                       (catch Exception _ nil)))
        lines (cond-> [(pr-str id)]
                (some #{id} (graph/roots g)) (conj "root")
                (:spec (:value node)) (conj (str "spec=" (:spec (:value node))))
                raw-dl (conj (str "raw=" raw-dl))
                best-dl (conj (str "best=" best-dl))
                true (conj (value-summary (:value node))))]
    (str/join "\n" lines)))

(defn- value-node-style
  [g id]
  (if (some #{id} (graph/roots g))
    {:shape "box"
     :style "rounded,bold,filled"
     :fillcolor "#fff7ed"
     :color "#c2410c"}
    {:shape "box"
     :style "rounded,filled"
     :fillcolor "#f8fafc"
     :color "#64748b"}))

(defn- operator-node-label
  [node]
  (let [operator (:operator node)]
    (str/join "\n"
              (cond-> [(pr-str (:id node))
                       (str (:id operator))]
                (:dl operator) (conj (str "dl=" (format-dl (:dl operator))))))))

(defn- operator-node-style
  [id selected]
  (if (contains? selected id)
    {:shape "ellipse"
     :style "bold,filled"
     :fillcolor "#dcfce7"
     :color "#15803d"}
    {:shape "ellipse"
     :style "filled"
     :fillcolor "#e0f2fe"
     :color "#0369a1"}))

(defn- node-line
  [g selected [id node] opts]
  (let [attrs (if (graph/value-node? node)
                (assoc (value-node-style g id)
                       :label (value-node-label g id node opts))
                (assoc (operator-node-style id selected)
                       :label (operator-node-label node)))]
    (str "  \"" (dot-id (:kind node) id) "\" " (attrs attrs) ";")))

(defn- option-edge-lines
  [id node selected]
  (for [op-id (:options node)]
    (str "  \"" (dot-id :operator op-id) "\" -> \""
         (dot-id :value id) "\" "
         (attrs (cond-> {:label "option"
                         :color "#94a3b8"}
                  (contains? selected op-id)
                  (assoc :penwidth "2.2"
                         :color "#16a34a")))
         ";")))

(defn- child-edge-lines
  [id node]
  (for [[idx child-id] (map-indexed vector (:children node))]
    (str "  \"" (dot-id :value child-id) "\" -> \""
         (dot-id :operator id) "\" "
         (attrs {:label idx
                 :color "#94a3b8"})
         ";")))

(defn- edge-lines
  [selected [id node]]
  (cond
    (graph/value-node? node)
    (option-edge-lines id node selected)

    (graph/operator-node? node)
    (child-edge-lines id node)

    :else
    []))

(defn graph->dot
  "Render any CIWI graph to deterministic Graphviz DOT text."
  ([g]
   (graph->dot g {}))
  ([g opts]
   (let [selected (selected-operator-ids g opts)
         label (graph-label g opts)
         header (cond-> ["digraph ciwi {"
                         "  graph [rankdir=\"BT\", labelloc=\"t\"];"]
                  label (conj (str "  label=\"" (dot-escape label) "\";"))
                  true (conj "  node [fontname=\"Helvetica\", fontsize=\"10\"];")
                  true (conj "  edge [fontname=\"Helvetica\", fontsize=\"9\"];"))
         nodes (map #(node-line g selected % opts) (node-order g))
         edges (mapcat #(edge-lines selected %) (node-order g))]
     (str (str/join "\n" (concat header nodes edges ["}"])) "\n"))))

(defn write-dot!
  "Write a CIWI graph DOT file and return the output path."
  ([g path]
   (write-dot! g path {}))
  ([g path opts]
   (let [file (io/file path)]
     (io/make-parents file)
     (spit file (graph->dot g opts))
     (.getPath file))))

(defn dot-available?
  []
  (try
    (zero? (:exit (sh/sh "dot" "-V")))
    (catch java.io.IOException _
      false)))

(defn render-png!
  "Render a CIWI graph to PNG with Graphviz `dot` when available.

  The sibling DOT file is always written and returned in the result map.
  "
  ([g png-path]
   (render-png! g png-path {}))
  ([g png-path opts]
   (let [png-file (io/file png-path)
         dot-path (or (:dot-path opts)
                      (str (.getPath png-file) ".dot"))
         dot-path (write-dot! g dot-path opts)]
     (if-not (dot-available?)
       {:status :unavailable
        :reason :dot-not-found
        :dot-path dot-path
        :png-path (.getPath png-file)}
       (let [result (sh/sh "dot" "-Tpng" dot-path "-o" (.getPath png-file))]
         (if (zero? (:exit result))
           {:status :ok
            :dot-path dot-path
            :png-path (.getPath png-file)}
           (throw (ex-info "Graphviz dot failed"
                           {:dot-path dot-path
                            :png-path (.getPath png-file)
                            :exit (:exit result)
                            :out (:out result)
                            :err (:err result)}))))))))
