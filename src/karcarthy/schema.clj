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
    :optional        {:model :string
                      :tools [:vector :string]
                      :adapter :keyword}}

   :result
   {:karcarthy/type :result
    :required        {:ok? :boolean}
    :optional        {:agent :string
                      :text :string
                      :error :any
                      :raw :any}}

   :workflow
   {:one-of [:agent :pipe :map-branches :map-planner-worker
             :reduce :iterate :bind-routes :bind-continuation]}

   :pipe
   {:karcarthy/type :pipe
    :required        {:steps [:vector :workflow]}}

   :map-branches
   {:karcarthy/type :map
    :required        {:branches [:vector :workflow]}
    :optional        {:max-concurrency :integer}}

   :map-planner-worker
   {:karcarthy/type :map
    :required        {:planner :workflow
                      :worker :workflow}
    :optional        {:max-concurrency :integer}}

   :reduce
   {:karcarthy/type :reduce
    :required        {:mapped :workflow
                      :reducer :workflow}}

   :iterate
   {:karcarthy/type :iterate
    :required        {:worker :workflow
                      :evaluator :workflow}
    :optional        {:max-rounds :integer}}

   :bind-routes
   {:karcarthy/type :bind
    :required        {:source :workflow
                      :routes [:map :any :workflow]}
    :optional        {:default :workflow}}

   :bind-continuation
   {:karcarthy/type :bind
    :required        {:source :workflow
                      :to :workflow}
    :optional        {:prompt :string}}})

(def json-schema
  "JSON Schema for CLI workflow objects.

  The CLI JSON shape mirrors workflow EDN but uses a `type` string instead of
  `:karcarthy/type`, and string values for adapter ids."
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "$id" "https://karcarthy.dev/schema/workflow.json"
   "title" "karcarthy workflow"
   "description" "A karcarthy workflow JSON object accepted by the CLI bridge."
   "$ref" "#/$defs/workflow"
   "$defs"
   {"workflow"
    {"oneOf" [{"$ref" "#/$defs/agent"}
              {"$ref" "#/$defs/pipe"}
              {"$ref" "#/$defs/mapBranches"}
              {"$ref" "#/$defs/mapPlannerWorker"}
              {"$ref" "#/$defs/reduce"}
              {"$ref" "#/$defs/iterate"}
              {"$ref" "#/$defs/bindRoutes"}
              {"$ref" "#/$defs/bindContinuation"}]}

    "agent"
    {"type" "object"
     "required" ["type" "name" "instructions"]
     "additionalProperties" false
     "properties" {"type" {"const" "agent"}
                   "name" {"type" "string" "minLength" 1}
                   "instructions" {"type" "string"}
                   "model" {"type" "string"}
                   "tools" {"type" "array" "items" {"type" "string"}}
                   "adapter" {"type" "string"}}}

    "pipe"
    {"type" "object"
     "required" ["type" "steps"]
     "additionalProperties" false
     "properties" {"type" {"const" "pipe"}
                   "steps" {"type" "array"
                            "items" {"$ref" "#/$defs/workflow"}}}}

    "mapBranches"
    {"type" "object"
     "required" ["type" "branches"]
     "additionalProperties" false
     "properties" {"type" {"const" "map"}
                   "branches" {"type" "array"
                               "items" {"$ref" "#/$defs/workflow"}}
                   "max-concurrency" {"type" "integer" "minimum" 1}}}

    "mapPlannerWorker"
    {"type" "object"
     "required" ["type" "planner" "worker"]
     "additionalProperties" false
     "properties" {"type" {"const" "map"}
                   "planner" {"$ref" "#/$defs/workflow"}
                   "worker" {"$ref" "#/$defs/workflow"}
                   "max-concurrency" {"type" "integer" "minimum" 1}}}

    "reduce"
    {"type" "object"
     "required" ["type" "mapped" "reducer"]
     "additionalProperties" false
     "properties" {"type" {"const" "reduce"}
                   "mapped" {"$ref" "#/$defs/workflow"}
                   "reducer" {"$ref" "#/$defs/workflow"}}}

    "iterate"
    {"type" "object"
     "required" ["type" "worker" "evaluator"]
     "additionalProperties" false
     "properties" {"type" {"const" "iterate"}
                   "worker" {"$ref" "#/$defs/workflow"}
                   "evaluator" {"$ref" "#/$defs/workflow"}
                   "max-rounds" {"type" "integer" "minimum" 1}}}

    "bindRoutes"
    {"type" "object"
     "required" ["type" "source" "routes"]
     "additionalProperties" false
     "properties" {"type" {"const" "bind"}
                   "source" {"$ref" "#/$defs/workflow"}
                   "routes" {"type" "object"
                             "additionalProperties" {"$ref" "#/$defs/workflow"}}
                   "default" {"$ref" "#/$defs/workflow"}}}

    "bindContinuation"
    {"type" "object"
     "required" ["type" "source" "to"]
     "additionalProperties" false
     "properties" {"type" {"const" "bind"}
                   "source" {"$ref" "#/$defs/workflow"}
                   "to" {"$ref" "#/$defs/workflow"}
                   "prompt" {"type" "string"}}}}})
