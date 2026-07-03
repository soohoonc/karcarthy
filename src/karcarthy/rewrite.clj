(ns karcarthy.rewrite
  "Rewrite workflows before running them.

  This is the Clojure-native payoff: a workflow is plain EDN, so host code can
  inspect it, stamp configuration onto it, and mechanically rewrite it before
  `karcarthy.orchestrate/run` interprets it. Rewrites do not call runners and
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

(defn- map! [label x]
  (when-not (map? x)
    (throw (ex-info (str label " expects a map")
                    {:value x})))
  x)

(defn agents
  "Return the valid agent leaves in `workflow`, in traversal order."
  [workflow]
  (workflow! "agents" workflow)
  (filterv k/agent? (tree-seq coll? seq workflow)))

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

(def ^:private configure-keys
  #{:model :instructions/suffix})

(defn- configure! [opts]
  (let [unknown (seq (remove configure-keys (keys opts)))]
    (when unknown
      (throw (ex-info "configure contains unknown keys"
                      {:unknown (vec unknown)
                       :supported (vec configure-keys)}))))
  opts)

(defn configure
  "Apply agent configuration to a workflow in one pass.

  Supported keys:
    :model                 model selector string
    :instructions/suffix   text appended to every agent instruction"
  [opts workflow]
  (let [opts (configure! (map! "configure" opts))
        {:keys [model instructions/suffix]} opts]
    (when (contains? opts :model)
      (string! "configure :model" model))
    (when (contains? opts :instructions/suffix)
      (string! "configure :instructions/suffix" suffix))
    (over k/agent?
          (fn [agent]
            (cond-> agent
              (contains? opts :model) (assoc :model model)
              (contains? opts :instructions/suffix)
              (update :instructions str "\n\n" (str/trim suffix))))
          workflow)))
