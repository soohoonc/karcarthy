(ns karcarthy.schema
  "Schema values for karcarthy data.

  These are plain data, not a separate runtime. Use them for documentation,
  generators, CLIs, and tests that need the public EDN/JSON shapes without
  reaching into implementation namespaces."
  (:require [karcarthy.workflow :as wf]))

(def edn-schema
  "Plain EDN description of the public karcarthy data model.

  This is intentionally small and declarative: the executable validator remains
  `workflow?`; this value is the reference shape."
  (merge
   {:subagent
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
    {:one-of wf/workflow-types}

    :agent-ref
    {:karcarthy/type :agent-ref
     :required        {:name :string}
     :dynamic-only?   true
     :experimental?   true}

    :workflow-ref
    {:karcarthy/type :workflow-ref
     :required        {:name :string}
     :dynamic-only?   true
     :experimental?   true}}
   (into {}
         (map (fn [type] [type (wf/edn-node-schema type)]))
         wf/workflow-types)))

(def json-schema
  "JSON Schema for CLI workflow objects.

  The CLI JSON shape mirrors workflow EDN but uses a `type` string instead of
  `:karcarthy/type`."
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "$id" "https://karcarthy.dev/schema/workflow.json"
   "title" "karcarthy workflow"
   "description" "A karcarthy workflow JSON object accepted by the CLI bridge."
   "$ref" "#/$defs/workflow"
   "$defs"
   (let [json-types (filter #(:json? (wf/node-spec %)) wf/workflow-types)]
     (into
      {"workflow"
       {"oneOf" (mapv (fn [type] {"$ref" (str "#/$defs/" (name type))})
                       json-types)}}
      (map (fn [type] [(name type) (wf/json-node-schema type)]))
      json-types))})
