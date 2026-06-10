(ns ciwi.wunderbaum.attachment
  (:require [ciwi.graph :as graph]))

(defn- op-carrying-roots
  [g primary-root-id]
  (filterv (fn [root-id]
             (and (not= primary-root-id root-id)
                  (seq (:options (graph/node g root-id)))))
           (graph/roots g)))

(defn- descendant-set
  [g root-id]
  (set (graph/walk g root-id {:above? false
                              :below? true
                              :values? true
                              :operators? true
                              :include-self? true})))

(defn- ancestor-set
  [g root-id]
  (set (graph/walk g root-id {:above? true
                              :below? false
                              :values? true
                              :operators? true
                              :include-self? true})))

(defn context
  [g root-id free-root-ids]
  (let [root-id (or root-id (first (graph/roots g)))
        free-root-ids (vec free-root-ids)
        op-roots (op-carrying-roots g root-id)]
    {:primary-root-id root-id
     :free-root-ids free-root-ids
     :primary-descendants (when root-id
                            (descendant-set g root-id))
     :free-ancestors (into #{}
                           (mapcat #(ancestor-set g %))
                           free-root-ids)
     :op-roots op-roots
     :primary-leaves (when (seq op-roots)
                       (graph/leaves g root-id))
     :op-root-descendants (into {}
                                (map (fn [op-root-id]
                                       [op-root-id
                                        (descendant-set g op-root-id)]))
                                op-roots)}))

(defn- leaves-below-primary-outside-op-root?
  [{:keys [primary-leaves op-root-descendants]} op-root-id]
  (boolean
   (some #(not (contains? (get op-root-descendants op-root-id) %))
         primary-leaves)))

(defn invalid?
  ([g gen-cond conditioned-nodes]
   (invalid? g
             gen-cond
             conditioned-nodes
             (first (graph/roots g))
             (rest (graph/roots g))))
  ([g gen-cond conditioned-nodes root-id free-root-ids]
   (invalid? g
             gen-cond
             conditioned-nodes
             (context g root-id free-root-ids)))
  ([g gen-cond conditioned-nodes {:keys [primary-root-id
                                         primary-descendants
                                         free-ancestors
                                         op-roots]
                                  :as context}]
   (let [root-id primary-root-id
         input-attachment? (some #(not= -1 %) gen-cond)]
     (boolean
      (or
       (some (fn [[position node-id]]
               (cond
                 (and (= -1 position)
                      (seq (:options (graph/node g node-id))))
                 true

                 (and (= -1 position)
                      root-id
                      (not (contains? primary-descendants node-id)))
                 true

                 (and (not= -1 position)
                      (not (contains? free-ancestors node-id)))
                 true

                 (and (not= -1 position)
                      (= root-id node-id))
                 true

                 :else false))
             (map vector gen-cond conditioned-nodes))
       (> (count op-roots) 1)
       (and (seq op-roots)
            input-attachment?
            (let [op-root-id (first op-roots)]
              (or (not (some #{op-root-id} conditioned-nodes))
                  (not (leaves-below-primary-outside-op-root?
                        context
                        op-root-id))))))))))
