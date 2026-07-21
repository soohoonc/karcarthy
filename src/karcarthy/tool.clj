(ns karcarthy.tool
  "Tool data and construction."
  (:require [karcarthy.core :as core]))

(defmacro tool [config bindings & body]
  `(core/tool ~config ~bindings ~@body))

(defmacro deftool [sym config bindings & body]
  `(core/deftool ~sym ~config ~bindings ~@body))

(def tool? core/tool?)
(def hosted-tool core/hosted-tool)
(def hosted-tool? core/hosted-tool?)
(def definition core/definition)
(def expansion core/expansion)
