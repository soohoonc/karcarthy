(ns karcarthy.rewrite
  "Rewrite workflows before running them.

  This is the Clojure-native payoff: a workflow is plain EDN, so host code can
  inspect it, stamp config onto it, and mechanically rewrite it before
  `karcarthy.orchestrate/run` interprets it. Rewrites do not call adapters and
  do not use `clojure.core/eval`."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

(defn- workflow! [label workflow]
  (when-not (o/workflow? workflow)
    (throw (ex-info (str label " expects workflow data")
                    {:workflow workflow})))
  workflow)

(defn- string! [label x]
  (when-not (string? x)
    (throw (ex-info (str label " expects a string")
                    {:value x})))
  x)

(defn- keyword! [label x]
  (when-not (keyword? x)
    (throw (ex-info (str label " expects a keyword")
                    {:value x})))
  x)

(defn- map! [label x]
  (when-not (map? x)
    (throw (ex-info (str label " expects a map")
                    {:value x})))
  x)

(defn agents
  "Return the valid agent leaves in `workflow`, in traversal order."
  [workflow]
  (workflow! "agents" workflow)
  (let [found (atom [])]
    (walk/postwalk (fn [x]
                     (when (k/agent? x)
                       (swap! found conj x))
                     x)
                   workflow)
    @found))

(defn over
  "Apply `f` to every value in `workflow` where `pred` returns true.

  The input workflow is validated before traversal, and the rewritten workflow
  is validated before return."
  [pred f workflow]
  (workflow! "over" workflow)
  (let [workflow' (walk/postwalk
                   (fn [x]
                     (if (pred x)
                       (f x)
                       x))
                   workflow)]
    (workflow! "over result" workflow')
    workflow'))

(def ^:private config-keys
  #{:adapter :model :instructions/suffix})

(defn- known-config! [opts]
  (let [unknown (seq (remove config-keys (keys opts)))]
    (when unknown
      (throw (ex-info "config contains unknown keys"
                      {:unknown (vec unknown)
                       :supported (vec config-keys)}))))
  opts)

(defn config
  "Apply agent config to a workflow in one pass.

  Supported keys:
    :adapter               adapter registry key
    :model                 model selector string
    :instructions/suffix   text appended to every agent instruction"
  [opts workflow]
  (let [opts (known-config! (map! "config" opts))
        {:keys [adapter model instructions/suffix]} opts]
    (when (contains? opts :adapter)
      (keyword! "config :adapter" adapter))
    (when (contains? opts :model)
      (string! "config :model" model))
    (when (contains? opts :instructions/suffix)
      (string! "config :instructions/suffix" suffix))
    (over k/agent?
          (fn [agent]
            (cond-> agent
              (contains? opts :adapter) (assoc :adapter adapter)
              (contains? opts :model) (assoc :model model)
              (contains? opts :instructions/suffix)
              (update :instructions str "\n\n" (str/trim suffix))))
          workflow)))
