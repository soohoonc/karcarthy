(ns karcarthy.schema
  "Schema values for karcarthy data.

  These are plain data, not a separate runtime. Use them for documentation,
  generators, CLIs, and tests that need the public EDN/JSON shapes without
  reaching into implementation namespaces.")

(def edn-schema
  "Plain EDN description of the public karcarthy data model.

  This is intentionally small and declarative: the executable validator remains
  `workflow?`; this value is the reference shape."
  {:agent
   {:karcarthy/type :agent
    :required        {:name :string
                      :instructions :string}
    :optional        {:description :string
                      :model :string
                      :tools [:vector :string]
                      :runner :keyword
                      :config :map}}

   :subagent
   {:karcarthy/type :subagent
    :required        {:name :string
                      :description :string
                      :instructions :string}
    :optional        {:model :string
                      :tools [:vector :string]
                      :disallowed-tools [:vector :string]
                      :permission-mode :keyword-or-string
                      :sandbox-mode :keyword-or-string
                      :mcp-servers :any
                      :max-turns :integer
                      :skills [:vector :string]
                      :initial-prompt :string
                      :memory :keyword-or-string
                      :effort :keyword-or-string
                      :reasoning-effort :keyword-or-string
                      :background? :boolean
                      :isolation :keyword-or-string
                      :color :keyword-or-string
                      :nicknames [:vector :string]
                      :hooks :any
                      :config :map}
    :runner-config?  true
    :workflow-node?  false}

   :result
   {:karcarthy/type :result
    :required        {:ok? :boolean}
    :optional        {:agent :string
                      :text :string
                      :error :any
                      :raw :any}}

   :workflow
   {:one-of [:agent :step :pipe :branch :delegate
             :reduce :revise :route :continue :dynamic]}

   :pipe
   {:karcarthy/type :pipe
    :required        {:steps [:vector :workflow]}}

   :step
   {:karcarthy/type :step
    :required        {:f :fn}
    :optional        {:name :string
                      :context? :boolean}
    :clojure-only?   true
    :serializable?   false}

   :branch
   {:karcarthy/type :branch
    :required        {:branches [:vector :workflow]}
    :optional        {:max-concurrency :integer}}

   :delegate
   {:karcarthy/type :delegate
    :required        {:planner :workflow
                      :worker :workflow}
    :optional        {:max-concurrency :integer}}

   :reduce
   {:karcarthy/type :reduce
    :required        {:source :workflow
                      :reducer :workflow}}

   :revise
   {:karcarthy/type :revise
    :required        {:worker :workflow
                      :evaluator :workflow}
    :optional        {:max-rounds :integer}}

   :route
   {:karcarthy/type :route
    :required        {:source :workflow
                      :routes [:object :any :workflow]}
    :optional        {:default :workflow}}

   :continue
   {:karcarthy/type :continue
    :required        {:source :workflow
                      :to :workflow}
    :optional        {:prompt :string}}

   :dynamic
   {:karcarthy/type :dynamic
    :required        {:agent :agent}
    :optional        {:max-steps :integer}}

   :agent-ref
   {:karcarthy/type :agent-ref
    :required        {:name :string}
    :dynamic-only?   true}

   :workflow-ref
   {:karcarthy/type :workflow-ref
    :required        {:name :string}
    :dynamic-only?   true}})

(def json-schema
  "JSON Schema for CLI workflow objects.

  The CLI JSON shape mirrors workflow EDN but uses a `type` string instead of
  `:karcarthy/type`, and string values for runner ids."
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "$id" "https://karcarthy.dev/schema/workflow.json"
   "title" "karcarthy workflow"
   "description" "A karcarthy workflow JSON object accepted by the CLI bridge."
   "$ref" "#/$defs/workflow"
   "$defs"
   {"workflow"
    {"oneOf" [{"$ref" "#/$defs/agent"}
              {"$ref" "#/$defs/pipe"}
              {"$ref" "#/$defs/branch"}
              {"$ref" "#/$defs/delegate"}
              {"$ref" "#/$defs/reduce"}
              {"$ref" "#/$defs/revise"}
              {"$ref" "#/$defs/route"}
              {"$ref" "#/$defs/continue"}
              {"$ref" "#/$defs/dynamic"}]}

    "agent"
    {"type" "object"
     "required" ["type" "name" "instructions"]
     "additionalProperties" false
     "properties" {"type" {"const" "agent"}
	                   "name" {"type" "string" "minLength" 1}
	                   "description" {"type" "string"}
	                   "instructions" {"type" "string"}
	                   "model" {"type" "string"}
	                   "tools" {"type" "array" "items" {"type" "string"}}
	                   "runner" {"type" "string"}
	                   "config" {"type" "object"}}}

    "pipe"
    {"type" "object"
     "required" ["type" "steps"]
     "additionalProperties" false
     "properties" {"type" {"const" "pipe"}
                   "steps" {"type" "array"
                            "items" {"$ref" "#/$defs/workflow"}}}}

    "branch"
    {"type" "object"
     "required" ["type" "branches"]
     "additionalProperties" false
     "properties" {"type" {"const" "branch"}
                   "branches" {"type" "array"
                               "items" {"$ref" "#/$defs/workflow"}}
                   "max-concurrency" {"type" "integer" "minimum" 1}}}

    "delegate"
    {"type" "object"
     "required" ["type" "planner" "worker"]
     "additionalProperties" false
     "properties" {"type" {"const" "delegate"}
                   "planner" {"$ref" "#/$defs/workflow"}
                   "worker" {"$ref" "#/$defs/workflow"}
                   "max-concurrency" {"type" "integer" "minimum" 1}}}

    "reduce"
    {"type" "object"
     "required" ["type" "source" "reducer"]
     "additionalProperties" false
     "properties" {"type" {"const" "reduce"}
                   "source" {"$ref" "#/$defs/workflow"}
                   "reducer" {"$ref" "#/$defs/workflow"}}}

    "revise"
    {"type" "object"
     "required" ["type" "worker" "evaluator"]
     "additionalProperties" false
     "properties" {"type" {"const" "revise"}
                   "worker" {"$ref" "#/$defs/workflow"}
                   "evaluator" {"$ref" "#/$defs/workflow"}
                   "max-rounds" {"type" "integer" "minimum" 1}}}

    "route"
    {"type" "object"
     "required" ["type" "source" "routes"]
     "additionalProperties" false
     "properties" {"type" {"const" "route"}
                   "source" {"$ref" "#/$defs/workflow"}
                   "routes" {"type" "object"
                             "additionalProperties" {"$ref" "#/$defs/workflow"}}
                   "default" {"$ref" "#/$defs/workflow"}}}

    "continue"
    {"type" "object"
     "required" ["type" "source" "to"]
     "additionalProperties" false
     "properties" {"type" {"const" "continue"}
                   "source" {"$ref" "#/$defs/workflow"}
                   "to" {"$ref" "#/$defs/workflow"}
                   "prompt" {"type" "string"}}}

    "dynamic"
    {"type" "object"
     "required" ["type" "agent"]
     "additionalProperties" false
     "properties" {"type" {"const" "dynamic"}
                   "agent" {"$ref" "#/$defs/agent"}
                   "max-steps" {"type" "integer" "minimum" 1}}}}})
