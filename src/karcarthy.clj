(ns karcarthy
  "The public karcarthy API: a native, homoiconic Clojure agent harness."
  (:refer-clojure :exclude [agent run!])
  (:require [karcarthy.acp :as acp]
            [karcarthy.agent :as agent-data]
            [karcarthy.schema :as schema]
            [karcarthy.mcp :as mcp]
            [karcarthy.monitor :as mon]
            [karcarthy.model.responses :as responses]
            [karcarthy.prompt :as prompt]
            [karcarthy.run :as run]
            [karcarthy.session :as session]
            [karcarthy.tool :as tool-data]
            [karcarthy.tools :as tools]))

;; Re-export the construction macros so most applications need one alias.
(defmacro agent [config]
  `(agent-data/agent ~config))

(defmacro defagent [sym config]
  `(agent-data/defagent ~sym ~config))

(defmacro tool [config bindings & body]
  `(tool-data/tool ~config ~bindings ~@body))

(defmacro deftool [sym config bindings & body]
  `(tool-data/deftool ~sym ~config ~bindings ~@body))

(def agent? agent-data/agent?)
(def tool? tool-data/tool?)
(def hosted-tool tool-data/hosted-tool)
(def hosted-tool? tool-data/hosted-tool?)
(def definition agent-data/definition)
(def expansion agent-data/expansion)
(def schema-valid? schema/valid?)
(def explain-schema schema/explain)
(def schema->json-schema schema/json-schema)

(def run! run/run!)
(defn context [] (run/context))
(defn model! [request] (run/model! request))
(defn emit! [event] (run/emit! event))
(def events run/events)
(def output run/output)
(def monitor mon/monitor)
(def monitor-state mon/monitor-state)

(def mock-model run/mock-model)
(def session session/session)
(def session? session/session?)
(def session-id session/session-id)
(def get-items session/get-items)
(def add-items! session/add-items!)
(def pop-item! session/pop-item!)
(def clear-session! session/clear-session!)
(def local-tools tools/local)
(def prompt prompt/prompt)
(def prompt-file prompt/prompt-file)
(def responses-web-search responses/web-search)
(def connect-mcp! mcp/connect!)
(def mcp-tools mcp/tools)
(def close-mcp! mcp/close!)
(def serve-acp! acp/serve!)
