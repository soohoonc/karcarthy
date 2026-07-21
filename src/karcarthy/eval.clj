(ns karcarthy.eval
  "The eval Tool and same-process evaluation of one Clojure expression."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [karcarthy.agent :as agent]
            [karcarthy.schema :as schema]
            [karcarthy.run :as run]
            [karcarthy.tool :as tool])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

(def ^:private request-schema
  {:type "object"
   :properties
   {"code" {:type "string"
            :description "Exactly one complete Clojure expression to evaluate."}}
   :required ["code"]
   :additionalProperties false})

(def ^:private description-template
  "Evaluate one Clojure expression. The expression may create and run Agents. Its
value is returned to you.

## When to use

Use `eval` when ordinary Clojure composition materially helps: create a focused
Agent, run several Agents concurrently, transform their results, or choose the
next step from data. Call a directly available Tool or Agent when no composition
is needed.

## Tool input

- `code` is exactly one complete Clojure expression, without Markdown fences.
- The current Agent input is already bound to the symbol `input`.

## Creating an Agent

`agent` is a macro that accepts one configuration map. The map requires
`:name`, `:model`, and `:instructions`; `:input-schema` and `:output-schema`
are optional. `(run! agent-value agent-input)` runs the resulting Agent and
returns a Run map.

Use `let`, `if`, `mapv`, `future`, `deref`, `agent`, and `run!` normally. A
`run!` call returns a Run map; its Agent output is at `:output`.

## Run behavior

The first `run!` establishes a run. Every `run!` called from this evaluation,
including calls in `future`, participates in that same run and shares limits,
usage, cancellation, approvals, events, context, and run id. Each Agent starts
with only its explicit input and a fresh model conversation.

The expression runs in this JVM. It does not start a Clojure subprocess. Model
transports and process-backed Tools may still perform their normal external I/O.

## Example: parallel specialists

```clojure
(let [reviewers (mapv (fn [[name instructions]]
                        (agent {:name name
                                :model \"MODEL_ID\"
                                :instructions instructions
                                :input-schema string?
                                :output-schema string?}))
                      [[\"analyst\" \"Analyze one part of the task.\"]
                       [\"planner\" \"Propose a practical next step.\"]])
      tasks (mapv #(future (run! % input)) reviewers)]
  (mapv (comp :output deref) tasks))
```

Use the model configuration listed below in place of the example's model value.

## Available model configuration

{{MODEL_CONFIGURATION}}

## Available Tools

{{AVAILABLE_TOOLS}}

## Available Agents

{{AVAILABLE_AGENTS}}
")

(def ^:private reserved-symbols
  #{"agent" "defagent" "tool" "deftool" "run!" "context" "definition"
    "expansion" "eval" "input"})

(defn- clojure-symbol [kind value-name]
  (let [clean (-> (str value-name)
                  (str/replace #"[^A-Za-z0-9*+!_?.-]+" "-")
                  (str/replace #"^-+|-+$" ""))
        clean (if (or (str/blank? clean)
                      (re-find #"^[0-9]" clean)
                      (contains? reserved-symbols clean))
                (str (name kind) "-" (if (str/blank? clean) "value" clean))
                clean)]
    (symbol clean)))

(defn- hosted-name [hosted]
  (let [spec (:spec hosted)]
    (or (:name spec) (get spec "name")
        (:type spec) (get spec "type")
        "hosted-tool")))

(defn- available-entry [kind value]
  (let [value-name (case kind
                     :agent (:name value)
                     :tool (:name value)
                     :hosted-tool (hosted-name value))
        description (case kind
                      :agent (or (:description value) "No description provided.")
                      :tool (or (:description value) "No description provided.")
                      :hosted-tool "Provider-hosted Tool.")
        schema (case kind
                 :agent (schema/json-schema (:input-schema value))
                 :tool (schema/json-schema (:input-schema value))
                 :hosted-tool (:spec value))]
    {:kind kind
     :name (str value-name)
     :symbol (clojure-symbol kind value-name)
     :description description
     :schema schema
     :value value}))

(defn- available-entries [tools agents]
  (let [entries (concat
                 (map #(available-entry
                        (if (tool/hosted-tool? %) :hosted-tool :tool) %)
                      tools)
                 (map #(available-entry :agent %) agents))
        duplicates (->> entries
                        (map :symbol)
                        frequencies
                        (keep (fn [[sym n]] (when (> n 1) sym)))
                        sort
                        vec)]
    (when (seq duplicates)
      (schema/fail!
       :schema :configuration
       "Available Tools and Agents produce duplicate Clojure symbols"
       {:symbols duplicates}))
    (vec entries)))

(defn- printable-model [model]
  (when (map? model)
    (let [config (select-keys model [:transport :provider :id :reasoning])]
      (cond-> config
        (not (keyword? (:transport config))) (dissoc :transport)))))

(defn- catalog [kinds entries]
  (let [kinds (if (set? kinds) kinds #{kinds})
        entries (filter #(contains? kinds (:kind %)) entries)]
    (if (seq entries)
      (str/join
       "\n"
       (map (fn [{:keys [kind name symbol description schema]}]
              (str "- `" symbol "` — "
                   (case kind
                     :agent "Agent"
                     :tool "Tool"
                     :hosted-tool "hosted Tool")
                   " `" name "`: " description
                   (when schema (str " Input: `" (pr-str schema) "`"))))
            entries))
      "- None.")))

(defn- description [model entries]
  (let [model (printable-model model)]
    (-> description-template
        (str/replace
         "{{MODEL_CONFIGURATION}}"
         (if (seq model)
           (str "```clojure\n" (pr-str model) "\n```")
           "No reusable model configuration is available. Configure a model explicitly."))
        (str/replace "{{AVAILABLE_TOOLS}}"
                     (catalog #{:tool :hosted-tool} entries))
        (str/replace "{{AVAILABLE_AGENTS}}" (catalog :agent entries)))))

(defn- deepest-message [^Throwable t]
  (loop [current t]
    (if-let [cause (.getCause current)]
      (recur cause)
      (or (ex-message current) (str current)))))

(defn read-expression
  "Read exactly one Clojure expression with reader evaluation disabled."
  [source]
  (when-not (string? source)
    (schema/fail! :read :eval "Eval code must be a string" {:value source}))
  (try
    (let [reader (LineNumberingPushbackReader. (StringReader. source))
          eof (Object.)
          expression (binding [*read-eval* false] (read {:eof eof} reader))
          extra (binding [*read-eval* false] (read {:eof eof} reader))]
      (when (identical? eof expression)
        (schema/fail! :read :eval "Eval code is empty"))
      (when-not (identical? eof extra)
        (schema/fail! :read :eval
                        "Eval code must contain exactly one top-level expression"
                        {:extra extra}))
      expression)
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Throwable t
      (schema/fail! :read :eval (or (ex-message t) (str t)) nil t))))

(defn- eval-ns!
  [rt]
  (let [ns-sym (:eval-namespace rt)
        existing (find-ns ns-sym)
        ns-obj (or existing (create-ns ns-sym))]
    (binding [*ns* ns-obj]
      (when-not existing
        (clojure.core/refer 'clojure.core)
        (when-let [parent-sym (:eval-parent-namespace rt)]
          (when-let [parent (find-ns parent-sym)]
            (let [available (->> (keys (ns-publics parent))
                                 (remove #(ns-resolve ns-obj %))
                                 vec)]
              (when (seq available)
                (clojure.core/refer parent-sym :only available)))))
        (doseq [sym '[agent defagent tool deftool run! context definition
                      expansion]]
          (when (ns-resolve ns-obj sym)
            (ns-unmap ns-obj sym)))
        (clojure.core/refer
         'karcarthy.agent
         :only '[agent defagent definition expansion])
        (clojure.core/refer 'karcarthy.tool :only '[tool deftool])
        (clojure.core/refer 'karcarthy.run :only '[run! context]))
      (doseq [[sym value] (:eval-bindings rt)]
        (when (ns-resolve ns-obj sym)
          (ns-unmap ns-obj sym))
        (intern ns-obj sym value)))
    ns-obj))

(defn- macroexpand-expression [form]
  (let [expanded (macroexpand form)]
    (if (and (seq? expanded)
             (contains? #{'quote 'clojure.core/quote} (first expanded)))
      expanded
      (walk/walk macroexpand-expression identity expanded))))

(declare model-value)

(defn- model-map [value]
  (into {}
        (map (fn [[k v]]
               [(cond
                  (or (string? k) (keyword? k)) k
                  (symbol? k) (str k)
                  :else (schema/fail!
                         :evaluation :output
                         "Eval returned a map with an unsupported key" {:key k}))
                (model-value v)]))
        value))

(defn- model-value
  "Restrict an eval Tool result to values a model transport can encode."
  [value]
  (cond
    (or (nil? value) (string? value) (number? value)
        (instance? Boolean value)) value
    (keyword? value) (name value)
    (symbol? value) (str value)
    (agent/agent? value) {:karcarthy/type "agent" :name (:name value)}
    (tool/tool? value) {:karcarthy/type "tool" :name (:name value)}
    (map? value) (model-map value)
    (sequential? value) (mapv model-value value)
    (set? value) (mapv model-value value)
    :else (schema/fail!
           :evaluation :output
           "Eval returned a value that cannot be sent to the model"
           {:class (.getName (class value))})))

(defn ^:no-doc eval-in-run!
  "Evaluate one Clojure expression in `rt`, with Agent input available."
  [rt code]
  (run/consume! rt :evals 1)
  (let [input (:agent-input rt)
        ordinal (swap! (:eval-counter rt) inc)
        rt (assoc rt :eval-namespace
                  (symbol (str (:eval-namespace rt) ".expr_" ordinal)))
        started (System/nanoTime)]
    (run/emit! rt {:type :eval/started :code code :input input})
    (try
      (let [ns-obj (eval-ns! rt)
            expression (binding [*ns* ns-obj]
                         (read-expression code))
            expansion (binding [*ns* ns-obj]
                        (macroexpand-expression expression))]
        (run/emit! rt {:type :eval/expanded
                       :expression expression
                       :expansion expansion
                       :namespace (ns-name ns-obj)})
        (intern ns-obj 'input input)
        (let [value (binding [*ns* ns-obj run/*run* rt]
                      (clojure.core/eval expression))
              value (model-value value)]
          (run/emit! rt {:type :eval/completed
                         :duration-ms (/ (double (- (System/nanoTime) started))
                                         1000000.0)
                         :value value})
          value))
      (catch Throwable t
        (let [structured? (= :failure (:karcarthy/type (ex-data t)))
              failure (if structured?
                        (schema/throwable->failure t)
                        (schema/failure :evaluation :evaluation
                                          (deepest-message t) {:code code}))]
          (run/emit! rt {:type :eval/failed
                         :phase (:phase failure)
                         :duration-ms (/ (double (- (System/nanoTime) started))
                                         1000000.0)
                         :error failure})
          (if structured?
            (throw t)
            (schema/fail! :evaluation :evaluation (deepest-message t)
                            {:code code} t)))))))

(defn ^:no-doc eval-tool
  "Create the eval Tool documented for the supplied model, Tools, and Agents."
  [model tools agents]
  (let [entries (available-entries tools agents)
        bindings (into {} (map (juxt :symbol :value)) entries)]
    (tool/make-tool
     {:name "eval"
      :description (description model entries)
      :input-schema request-schema
      :output-schema any?
      :needs-approval :never}
     '(eval)
     '(karcarthy.eval/eval-tool)
     (fn [rt {:keys [code]}]
       (eval-in-run! (assoc rt :eval-bindings bindings) code)))))
