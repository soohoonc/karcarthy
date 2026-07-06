(ns karcarthy.workflow
  "Canonical grammar for karcarthy workflow data.

  This namespace is deliberately data-first. `node-specs` is the single source
  of truth for node fields, child positions, EDN reference shapes, JSON
  properties, and the core validator. Runtime behavior remains in
  `karcarthy.orchestrate`."
  (:require [clojure.string :as str]))

(def workflow-types
  "Workflow node types in their stable documentation/schema order."
  [:agent :step :pipe :branch :delegate :reduce :revise :route :continue
   :dynamic])

(def node-specs
  "Declarative workflow-node grammar.

  Field shapes are interpreted by `valid-core-node?`, `edn-node-schema`, and
  `json-node-schema`. Extension runtimes still own their behavioral validation;
  the grammar entries for them keep schemas and wire allowlists aligned."
  {:agent
   {:json? true
    :fields {:name         {:required? true :shape :non-empty-string}
             :description  {:shape :string}
             :instructions {:required? true :shape :string}
             :model        {:shape :string}
             :tools        {:shape :string-vector}
             :config       {:shape :map}}}

   :step
   {:core? true
    :fields {:f     {:required? true :shape :fn}
             :name  {:shape :string}
             :call? {:shape :boolean}}
    :edn-meta {:clojure-only? true :serializable? false}}

   :pipe
   {:core? true :json? true
    :fields {:steps {:required? true :shape :workflows-vector}}}

   :branch
   {:core? true :json? true
    :fields {:branches        {:required? true :shape :workflows-vector
                               :non-empty? true}
             :max-concurrency {:shape :positive-integer}}}

   :delegate
   {:core? true :json? true
    :fields {:planner         {:required? true :shape :workflow}
             :worker          {:required? true :shape :workflow}
             :max-concurrency {:shape :positive-integer}}}

   :reduce
   {:core? true :json? true
    :fields {:source  {:required? true :shape :workflow}
             :reducer {:required? true :shape :workflow}}}

   :revise
   {:core? true :json? true
    :fields {:worker     {:required? true :shape :workflow}
             :evaluator  {:required? true :shape :workflow}
             :max-rounds {:shape :positive-integer}}}

   :route
   {:core? true :json? true
    :fields {:source  {:required? true :shape :workflow}
             :routes  {:required? true :shape :workflow-map}
             :default {:shape :workflow}}}

   :continue
   {:core? true :json? true
    :fields {:source {:required? true :shape :workflow}
             :to     {:required? true :shape :workflow}
             :prompt {:shape :string}}}

   :dynamic
   {:json? true
    :fields {:agent     {:required? true :shape :agent}
             :max-steps {:shape :positive-integer}}
    :edn-meta {:experimental? true}
    :json-description
    "Experimental: the dynamic op protocol may change between releases."}})

(defn node-spec
  "Return the grammar entry for `type`, or nil for an extension-only node."
  [type]
  (get node-specs type))

(defn allowed-keys
  "Allowed EDN keys for `type`, including `:karcarthy/type`."
  [type]
  (when-let [spec (node-spec type)]
    (conj (set (keys (:fields spec))) :karcarthy/type)))

(defn known-keys?
  "True when `node` has no keys outside its canonical grammar entry."
  [node]
  (when-let [allowed (allowed-keys (:karcarthy/type node))]
    (every? allowed (keys node))))

(defn- shape-valid?
  [shape value valid-workflow?]
  (case shape
    :fn               (fn? value)
    :string           (string? value)
    :non-empty-string (and (string? value) (not (str/blank? value)))
    :boolean          (boolean? value)
    :positive-integer (and (integer? value) (pos? value))
    :string-vector    (and (vector? value) (every? string? value))
    :map              (map? value)
    :agent            (valid-workflow? value)
    :workflow         (valid-workflow? value)
    :workflows-vector (and (vector? value) (every? valid-workflow? value))
    :workflow-map     (and (map? value) (every? valid-workflow? (vals value)))
    false))

(defn- valid-field?
  [node valid-workflow? [key {:keys [required? shape non-empty?]}]]
  (if-not (contains? node key)
    (not required?)
    (let [value (get node key)]
      (and (shape-valid? shape value valid-workflow?)
           (or (not non-empty?) (seq value))))))

(defn valid-core-node?
  "Validate one core composite node using the canonical grammar.

  `valid-workflow?` recursively validates child workflow positions. Agent and
  extension-node validation remain with their owning namespaces."
  [node valid-workflow?]
  (let [spec (node-spec (:karcarthy/type node))]
    (boolean
     (and (:core? spec)
          (known-keys? node)
          (every? #(valid-field? node valid-workflow? %) (:fields spec))))))

(defn portable?
  "True when host functions occur only inside explicit `:step` nodes.

  Extension nodes are open to third parties, so this tree-wide guard preserves
  the data-only contract even when an extension validator is permissive."
  [workflow]
  (not-any? fn?
            (tree-seq (fn [x]
                        (and (coll? x)
                             (not= :step (:karcarthy/type x))))
                      seq
                      workflow)))

(defn json-node-keys
  "Map JSON node type names to their accepted property-name sets."
  []
  (into {}
        (for [type workflow-types
              :let [spec (node-spec type)]
              :when (:json? spec)]
          [(name type)
           (into #{"type"} (map (comp name key)) (:fields spec))])))

(def ^:private edn-shapes
  {:fn :fn
   :string :string
   :non-empty-string :string
   :boolean :boolean
   :positive-integer :positive-integer
   :string-vector [:vector :string]
   :map :map
   :agent :agent
   :workflow :workflow
   :workflows-vector [:vector :workflow]
   :workflow-map [:object :any :workflow]})

(defn edn-node-schema
  "Derive one public EDN reference shape from the canonical grammar."
  [type]
  (let [{:keys [fields edn-meta]} (node-spec type)
        categorized (group-by (comp boolean :required? val) fields)
        render #(into {} (map (fn [[key {:keys [shape]}]]
                                [key (get edn-shapes shape)])) %)]
    (cond-> {:karcarthy/type type}
      (seq (get categorized true))
      (assoc :required (render (get categorized true)))

      (seq (get categorized false))
      (assoc :optional (render (get categorized false)))

      edn-meta (merge edn-meta))))

(defn- json-field-schema
  [shape]
  (case shape
    :fn               nil
    :string           {"type" "string"}
    :non-empty-string {"type" "string" "minLength" 1}
    :boolean          {"type" "boolean"}
    :positive-integer {"type" "integer" "minimum" 1}
    :string-vector    {"type" "array" "items" {"type" "string"}}
    :map              {"type" "object"}
    :agent            {"$ref" "#/$defs/agent"}
    :workflow         {"$ref" "#/$defs/workflow"}
    :workflows-vector {"type" "array"
                       "items" {"$ref" "#/$defs/workflow"}}
    :workflow-map     {"type" "object"
                       "additionalProperties" {"$ref" "#/$defs/workflow"}}))

(defn json-node-schema
  "Derive one JSON Schema node definition from the canonical grammar."
  [type]
  (let [{:keys [fields json-description]} (node-spec type)
        required (into ["type"]
                       (keep (fn [[key field]]
                               (when (:required? field) (name key))))
                       fields)
        properties (into {"type" {"const" (name type)}}
                         (map (fn [[key {:keys [shape non-empty?]}]]
                                [(name key)
                                 (cond-> (json-field-schema shape)
                                   non-empty? (assoc "minItems" 1))]))
                         fields)]
    (cond-> {"type" "object"
             "required" required
             "additionalProperties" false
             "properties" properties}
      json-description (assoc "description" json-description))))
