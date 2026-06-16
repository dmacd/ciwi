(ns ciwi.render.graph
  (:require [ciwi.dense.core :as dense]
            [ciwi.graph :as graph]
            [ciwi.mdl :as mdl]
            [ciwi.value :as value]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private value-col "#555555")
(def ^:private op-col "#555555")
(def ^:private edge-col "#888888")
(def ^:private selected-col "#6699ff")
(def ^:private frontier-col "#222222")
(def ^:private bg-col "#ffffff")

(defn- dot-escape
  [x]
  (-> (str x)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- html-escape
  [x]
  (-> (str x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- html-attr
  [s]
  {::html s})

(defn- html-attr?
  [x]
  (and (map? x) (contains? x ::html)))

(defn- dot-id
  [prefix id]
  (str (name prefix) "_" (dot-escape (pr-str id))))

(defn- dot-ref
  [prefix id]
  (str "\"" (dot-id prefix id) "\""))

(defn- attr
  [[k v]]
  (if (html-attr? v)
    (str (name k) "=<" (::html v) ">")
    (str (name k) "=\"" (dot-escape v) "\"")))

(defn- attrs
  [m]
  (str "[" (str/join ", " (map attr m)) "]"))

(defn- format-dl
  [x]
  (when (number? x)
    (format "%.2f" (double x))))

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

(defn- safe-value-dl
  [v]
  (try
    (double (value/desc-len v))
    (catch Exception _
      Double/NaN)))

(defn- safe-node-dl
  [g id]
  (try
    (double (:dl (mdl/node-dl g id {})))
    (catch Exception _
      Double/NaN)))

(defn- finite?
  [x]
  (and (number? x) (Double/isFinite (double x))))

(defn- sum-finite
  [xs]
  (reduce + 0.0 (filter finite? xs)))

(defn- graph-description
  [g opts]
  (when (or (not= false (:show-dl? opts))
            (and (not= false (:show-selected? opts))
                 (not (contains? opts :selected-operator-ids))))
    (try
      (mdl/graph-description g {})
      (catch Exception _
        nil))))

(defn- selected-operator-ids
  [g opts description]
  (set (or (:selected-operator-ids opts)
           (when (not= false (:show-selected? opts))
             (or (:selected description)
                 (try
                   (mapcat #(mdl/selected-operators g % {}) (graph/roots g))
                   (catch Exception _
                     [])))))))

(defn- section-value-ids
  [g]
  (let [roots (seq (graph/roots g))]
    (if roots
      roots
      (graph/value-ids g))))

(defn- reachable-ids
  [g]
  (let [starts (section-value-ids g)]
    (->> starts
         (mapcat #(graph/walk g %))
         distinct
         vec)))

(defn- frontier-ids
  [g]
  (let [roots (section-value-ids g)]
    (->> roots
         (mapcat #(graph/leaves g %))
         distinct
         (sort-by pr-str)
         vec)))

(defn- graph-dl-stats
  [g opts selected description leaf-ids]
  (let [roots (section-value-ids g)
        reachable (reachable-ids g)
        zero-arity-selected (filter (fn [id]
                                      (empty? (:children (graph/node g id))))
                                    selected)
        value-leaf-dls (map #(safe-value-dl (get-in g [:nodes % :value]))
                            leaf-ids)
        selected-leaf-op-dls (map #(double (or (:dl (:operator (graph/node g %)))
                                             0.0))
                                  zero-arity-selected)
        leaves-dl (+ (sum-finite value-leaf-dls)
                     (sum-finite selected-leaf-op-dls))
        max-leaf-dl (if-let [leaf-dls (seq (filter finite? value-leaf-dls))]
                      (apply max leaf-dls)
                      0.0)
        section-dl (or (some-> description :dl double)
                       (try
                         (double (mdl/graph-dl g {}))
                         (catch Exception _
                           (sum-finite (map #(safe-node-dl g %) roots)))))]
    {:section-dl section-dl
     :leaves-dl leaves-dl
     :model-dl (- leaves-dl max-leaf-dl)
     :max-leaf-dl max-leaf-dl
     :section-nodes (count roots)
     :value-nodes (count (filter #(graph/value-node? (graph/node g %))
                                 reachable))
     :op-nodes (count (filter #(graph/operator-node? (graph/node g %))
                              reachable))
     :leaves (count leaf-ids)
     :leaves-with-ops (+ (count leaf-ids)
                         (count zero-arity-selected))
     :depth (graph/graph-depth g)}))

(defn- stats-lines
  [stats]
  [(str "section DL: " (format-dl (:section-dl stats)))
   (str "leaves DL: " (format-dl (:leaves-dl stats)))
   (str "model DL: " (format-dl (:model-dl stats)))
   (str "max leaf DL: " (format-dl (:max-leaf-dl stats)))
   (str "nodes: " (:value-nodes stats) " value / " (:op-nodes stats) " op")
   (str "leaves: " (:leaves stats) " value / "
        (:leaves-with-ops stats) " incl ops")
   (str "depth: " (:depth stats))])

(defn- graph-label-html
  [g opts stats]
  (let [provided (:label opts)
        parts (cond-> []
                provided (into (str/split-lines (str provided)))
                stats (into (stats-lines stats))
                (:compression-rate opts)
                (conj (str "compression="
                           (format "%.4f" (double (:compression-rate opts))))))]
    (when (seq parts)
      (let [rows (map-indexed
                  (fn [idx line]
                    (str "<TR><TD ALIGN=\"LEFT\">"
                         (if (and (zero? idx) provided)
                           (str "<B>" (html-escape line) "</B>")
                           (html-escape line))
                         "</TD></TR>"))
                  parts)]
        (str "<TABLE BORDER=\"1\" CELLBORDER=\"0\" CELLSPACING=\"0\" "
             "CELLPADDING=\"4\">"
             (str/join "" rows)
             "</TABLE>")))))

(defn- node-order
  [g]
  (sort-by (comp pr-str first) (:nodes g)))

(defn- value-node-label
  [g id node opts]
  (let [raw-dl (when (:show-node-dl? opts)
                 (try (format-dl (value/desc-len (:value node)))
                      (catch Exception _ nil)))
        best-dl (when (:show-node-dl? opts)
                  (try (format-dl (:dl (mdl/node-dl g id {})))
                       (catch Exception _ nil)))
        prefix (if (:permeable? (:value node)) "*" "")
        lines (cond-> [(str prefix (pr-str id))]
                (some #{id} (graph/roots g)) (conj "root")
                (:spec (:value node)) (conj (str "spec=" (:spec (:value node))))
                raw-dl (conj (str "raw=" raw-dl))
                best-dl (conj (str "best=" best-dl))
                true (conj (value-summary (:value node))))]
    (str/join "\n" lines)))

(defn- value-node-style
  [g id]
  {:shape "box"
   :color (if (some #{id} (graph/roots g)) selected-col value-col)
   :height "0.02"
   :width "0.01"
   :fontsize "10"
   :fontcolor (if (some #{id} (graph/roots g)) selected-col value-col)})

(defn- operator-name
  [node]
  (name (:id (:operator node))))

(defn- operator-label-html
  [node color]
  (let [arity (count (:children node))
        op-name (html-escape (operator-name node))
        port-cells (map-indexed
                    (fn [idx _]
                      (str "<TD PORT=\"arg" idx "\">"
                           "<FONT POINT-SIZE=\"9\">arg" idx "</FONT>"
                           "</TD>"))
                    (:children node))
        colspan (when (> arity 1)
                  (str " COLSPAN=\"" arity "\""))]
    (str "<TABLE BORDER=\"1\" CELLBORDER=\"1\" CELLSPACING=\"0\" "
         "CELLPADDING=\"4\" COLOR=\"" color "\">"
         "<TR><TD" (or colspan "") "><FONT POINT-SIZE=\"10\">"
         op-name
         "</FONT></TD></TR>"
         (when (pos? arity)
           (str "<TR>" (str/join "" port-cells) "</TR>"))
         "</TABLE>")))

(defn- operator-node-style
  [id selected node opts]
  (let [selected? (contains? selected id)
        color (if selected? selected-col op-col)
        arity (count (:children node))
        base {:color color
              :height "0.02"
              :width "0.01"
              :fontsize "10"
              :fontcolor color}]
    (cond-> base
      (and (:show-node-ids? opts true) (not= false (:show-node-xlabels? opts)))
      (assoc :xlabel (pr-str id))

      (and (pos? arity) (not= false (:show-operator-ports? opts)))
      (assoc :label (html-attr (operator-label-html node color))
             :shape "plain")

      (or (zero? arity) (= false (:show-operator-ports? opts)))
      (assoc :label (operator-name node)
             :shape "oval"))))

(defn- node-line
  [g selected [id node] opts]
  (let [node-attrs (if (graph/value-node? node)
                     (assoc (value-node-style g id)
                            :label (value-node-label g id node opts))
                     (operator-node-style id selected node opts))]
    (str "  " (dot-ref (:kind node) id) " " (attrs node-attrs) ";")))

(defn- option-edge-lines
  [id node selected opts]
  (for [op-id (:options node)]
    (str "  " (dot-ref :value id) " -> "
         (dot-ref :operator op-id) " "
         (attrs (cond-> {:arrowsize "0.5"
                         :arrowhead "none"
                         :color edge-col}
                  (:label-option-edges? opts)
                  (assoc :label "option")

                  (contains? selected op-id)
                  (assoc :penwidth "2.2"
                         :color selected-col)))
         ";")))

(defn- operator-port-ref
  [id idx opts]
  (if (not= false (:show-operator-ports? opts))
    (str (dot-ref :operator id) ":arg" idx)
    (dot-ref :operator id)))

(defn- child-edge-lines
  [id node selected opts]
  (for [[idx child-id] (map-indexed vector (:children node))]
    (str "  " (operator-port-ref id idx opts) " -> "
         (dot-ref :value child-id) " "
         (attrs (cond-> {:arrowsize "0.5"
                         :arrowhead "none"
                         :color edge-col}
                  (contains? selected id)
                  (assoc :penwidth "2.2"
                         :color selected-col)))
         ";")))

(defn- edge-lines
  [selected opts [id node]]
  (cond
    (graph/value-node? node)
    (option-edge-lines id node selected opts)

    (graph/operator-node? node)
    (child-edge-lines id node selected opts)

    :else
    []))

(defn- frontier-cluster-lines
  [leaf-ids opts]
  (when (not= false (:show-frontier? opts))
    (for [[idx id] (map-indexed vector leaf-ids)]
      (str "  subgraph cluster_frontier_" idx " {\n"
           "    label=\"\";\n"
           "    style=\"rounded,dashed\";\n"
           "    color=\"" frontier-col "\";\n"
           "    penwidth=\"2\";\n"
           "    margin=\"8\";\n"
           "    " (dot-ref :value id) ";\n"
           "  }"))))

(defn graph->dot
  "Render any CIWI graph to deterministic Graphviz DOT text."
  ([g]
   (graph->dot g {}))
  ([g opts]
   (let [description (graph-description g opts)
         selected (selected-operator-ids g opts description)
         leaf-ids (frontier-ids g)
         stats (when (not= false (:show-dl? opts))
                 (graph-dl-stats g opts selected description leaf-ids))
         label (graph-label-html g opts stats)
         graph-attrs (cond-> {:bgcolor bg-col}
                       label (assoc :labelloc "t"
                                    :labeljust "l"
                                    :label (html-attr label)))
         header (cond-> ["digraph tree {"
                         (str "  graph " (attrs graph-attrs) ";")
                         "  node [fontname=\"Helvetica\", fontsize=\"10\"];"
                         "  edge [fontname=\"Helvetica\", fontsize=\"9\"];"])
         nodes (map #(node-line g selected % opts) (node-order g))
         edges (mapcat #(edge-lines selected opts %) (node-order g))
         frontier (frontier-cluster-lines leaf-ids opts)]
     (str (str/join "\n" (concat header nodes edges frontier ["}"])) "\n"))))

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
