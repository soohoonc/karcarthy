(ns karcarthy.agent
  "Agent data and construction."
  (:refer-clojure :exclude [agent])
  (:require [karcarthy.core :as core]))

(defmacro agent [config]
  `(core/agent ~config))

(defmacro defagent [sym config]
  `(core/defagent ~sym ~config))

(def agent? core/agent?)
(def definition core/definition)
(def expansion core/expansion)
