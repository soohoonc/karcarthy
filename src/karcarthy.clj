(ns karcarthy
  "The public karcarthy API: a native, homoiconic Clojure agent harness."
  (:refer-clojure :exclude [agent eval run!])
  (:require [karcarthy.acp :as acp]
            [karcarthy.agent :as agent-data]
            [karcarthy.eval :as eval-data]
            [karcarthy.schema :as schema]
            [karcarthy.mcp :as mcp]
            [karcarthy.monitor :as mon]
            [karcarthy.model.responses :as responses]
            [karcarthy.prompt :as prompt]
            [karcarthy.run :as run]
            [karcarthy.session :as session]
            [karcarthy.tool :as tool-data]
            [karcarthy.tools :as tools]))

(defmacro ^:private defexport [symbol source]
  (let [source-meta (meta (resolve source))
        export-meta (cond-> (select-keys source-meta [:doc :macro])
                      (:arglists source-meta)
                      (assoc :arglists (list 'quote (:arglists source-meta))))]
    `(def ~(with-meta symbol export-meta) (deref (var ~source)))))

;; Re-export the public API so most applications need one alias.
(defexport agent agent-data/agent)
(defexport defagent agent-data/defagent)
(defexport tool tool-data/tool)
(defexport deftool tool-data/deftool)
(defexport agent? agent-data/agent?)
(defexport tool? tool-data/tool?)
(defexport hosted-tool tool-data/hosted-tool)
(defexport hosted-tool? tool-data/hosted-tool?)
(defexport definition agent-data/definition)
(defexport expansion agent-data/expansion)
(defexport schema-valid? schema/valid?)
(defexport explain-schema schema/explain)
(defexport schema->json-schema schema/json-schema)
(defexport eval eval-data/eval)

(defexport run! run/run!)
(defexport context run/context)
(defexport model! run/model!)
(defexport emit! run/emit!)
(defexport events run/events)
(defexport output run/output)
(defexport monitor mon/monitor)
(defexport monitor-state mon/monitor-state)

(defexport mock-model run/mock-model)
(defexport session session/session)
(defexport session? session/session?)
(defexport session-id session/session-id)
(defexport get-items session/get-items)
(defexport add-items! session/add-items!)
(defexport pop-item! session/pop-item!)
(defexport clear-session! session/clear-session!)
(defexport local-tools tools/local)
(defexport prompt prompt/prompt)
(defexport prompt-file prompt/prompt-file)
(defexport responses-web-search responses/web-search)
(defexport connect-mcp! mcp/connect!)
(defexport mcp-tools mcp/tools)
(defexport close-mcp! mcp/close!)
(defexport serve-acp! acp/serve!)
