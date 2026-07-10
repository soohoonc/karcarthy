(ns karcarthy
  "The public karcarthy API: a native, homoiconic Clojure agent harness."
  (:refer-clojure :exclude [agent await run!])
  (:require [karcarthy.acp :as acp]
            [karcarthy.core :as core]
            [karcarthy.eval :as keval]
            [karcarthy.mcp :as mcp]
            [karcarthy.model.responses :as responses]
            [karcarthy.prompt :as prompt]
            [karcarthy.session :as session]
            [karcarthy.tools :as tools]))

;; Agent and Tool macros are forwarding macros so callers need one alias.
(defmacro agent
  [config & body]
  `(core/agent ~config ~@body))

(defmacro defagent [sym config & body]
  `(core/defagent ~sym ~config ~@body))

(defmacro tool [config bindings & body]
  `(core/tool ~config ~bindings ~@body))

(defmacro deftool [sym config bindings & body]
  `(core/deftool ~sym ~config ~bindings ~@body))

(def agent? core/agent?)
(def tool? core/tool?)
(def hosted-tool core/hosted-tool)
(def hosted-tool? core/hosted-tool?)
(def definition core/definition)
(def expansion core/expansion)
(def contract-valid? core/contract-valid?)
(def explain-contract core/explain-contract)
(def contract->json-schema core/contract->json-schema)

(def run! core/run!)
(defn context [] (core/context))
(defn model! [request] (core/model! request))
(defn emit! [event] (core/emit! event))
(def events core/events)

(def fake-model core/fake-model)
(def memory-session session/memory-session)
(def session? session/session?)
(def session-id session/session-id)
(def get-items session/get-items)
(def add-items! session/add-items!)
(def pop-item! session/pop-item!)
(def clear-session! session/clear-session!)
(def local-tools tools/local)
(def prompt prompt/prompt)
(def prompt-file prompt/prompt-file)
(def system-prompt prompt/system-prompt)
(def responses-web-search responses/web-search)
(def connect-mcp! mcp/connect!)
(def mcp-tools mcp/tools)
(def close-mcp! mcp/close!)
(def serve-acp! acp/serve!)

(defn read-agent-form [source] (keval/read-agent-form source))
(defn check-agent-form! [form] (keval/check-agent-form! form))
(defn eval-agent-form! [checked] (keval/eval-agent-form! checked))
(defn compile-agent! [source] (keval/compile-agent! source))
