(ns hooks.karcarthy
  (:require [clj-kondo.hooks-api :as api]))

(defn- form [symbol & children]
  (api/list-node (cons (api/token-node symbol) children)))

(defn agent [{:keys [node]}]
  (let [[_ config] (:children node)]
    {:node config}))

(defn defagent [{:keys [node]}]
  (let [[_ name config] (:children node)]
    {:node (form 'def name config)}))

(defn tool [{:keys [node]}]
  (let [[_ config bindings & body] (:children node)]
    {:node (form 'do config
                 (apply form 'fn bindings body))}))

(defn deftool [{:keys [node]}]
  (let [[_ name config bindings & body] (:children node)]
    {:node (form 'def name
                 (form 'do config
                       (apply form 'fn bindings body)))}))
