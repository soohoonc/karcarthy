(ns karcarthy
  "The public karcarthy API: a native, homoiconic Clojure agent harness."
  (:refer-clojure :exclude [agent await run!])
  (:require [karcarthy.acp :as acp]
            [karcarthy.core :as core]
            [karcarthy.eval :as keval]
            [karcarthy.mcp :as mcp]
            [karcarthy.model.openai :as openai]
            [karcarthy.prompt :as prompt]
            [karcarthy.tools :as tools]))

;; Agent and Tool macros are forwarding macros so callers need one alias.
(defmacro agent
  ([] `(core/agent))
  ([config & body] `(core/agent ~config ~@body)))

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
(def source-form core/source-form)
(def expanded-form core/expanded-form)
(def contract-valid? core/contract-valid?)
(def explain-contract core/explain-contract)
(def contract->json-schema core/contract->json-schema)

(def run! core/run!)
(def invoke! core/invoke!)
(def spawn! core/spawn!)
(def await! core/await!)
(def await-all! core/await-all!)
(def handoff! core/handoff!)
(def as-tool core/as-tool)
(def context core/context)
(def model! core/model!)
(def emit! core/emit!)
(def events core/events)

(def model-transport core/model-transport)
(def fake-model core/fake-model)
(def workspace-tools tools/workspace)
(def workspace-prompt prompt/workspace)
(def openai-web-search openai/web-search)
(def connect-mcp! mcp/connect!)
(def mcp-tools mcp/tools)
(def close-mcp! mcp/close!)
(def serve-acp! acp/serve!)

(def read-agent-form keval/read-agent-form)
(def check-agent-form! keval/check-agent-form!)
(def eval-agent-form! keval/eval-agent-form!)
(def compile-agent! keval/compile-agent!)
