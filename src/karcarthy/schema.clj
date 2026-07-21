(ns karcarthy.schema
  "Schemas and structured failures shared by Agents, Tools, and Runs."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s])
  (:import [com.networknt.schema InputFormat SchemaLocation SchemaRegistry
            SpecificationVersion]))

(defn ^:no-doc failure
  "Create the stable data carried by harness exceptions and failed Runs."
  ([kind phase message]
   (failure kind phase message nil nil))
  ([kind phase message data]
   (failure kind phase message data nil))
  ([kind phase message data cause]
   (cond-> {:karcarthy/type :failure
            :kind kind
            :phase phase
            :message message
            :data data
            :recoverable? false}
     cause (assoc :cause cause))))

(defn ^:no-doc fail!
  "Throw an ExceptionInfo carrying a stable failure map."
  ([kind phase message]
   (fail! kind phase message nil nil))
  ([kind phase message data]
   (fail! kind phase message data nil))
  ([kind phase message data cause]
   (let [value (failure kind phase message data cause)]
     (throw (if cause
              (ex-info message value cause)
              (ex-info message value))))))

(defn ^:no-doc throwable->failure
  "Convert any Throwable into the public failure shape."
  [^Throwable error]
  (let [data (ex-data error)]
    (if (= :failure (:karcarthy/type data))
      (dissoc data :cause)
      (failure :execution :execute (or (ex-message error) (str error))
               {:class (.getName (class error))}))))

(defn ^:no-doc json-schema?
  "Return true for an explicit JSON Schema map."
  [value]
  (map? value))

(declare valid?)

(def ^:private schema-registry
  (delay (SchemaRegistry/withDefaultDialect
          SpecificationVersion/DRAFT_2020_12)))

(defn- make-json-schema-validator [schema]
  (try
    (let [registry ^SchemaRegistry @schema-registry
          dialect-id (or (get schema :$schema) (get schema "$schema"))
          version (if dialect-id
                    (.orElse (SpecificationVersion/fromDialectId dialect-id)
                             nil)
                    SpecificationVersion/DRAFT_2020_12)]
      (when-not version
        (fail! :schema :configuration "Unsupported JSON Schema dialect"
               {:schema schema :dialect dialect-id}))
      (let [source (json/write-str schema)
            meta-schema (.getSchema
                         registry
                         (SchemaLocation/of (.getDialectId version)))
            schema-errors (vec (.validate meta-schema source
                                          InputFormat/JSON))]
        (when (seq schema-errors)
          (fail! :schema :configuration "Invalid JSON Schema"
                 {:schema schema :errors (mapv str schema-errors)}))
        (.getSchema registry source InputFormat/JSON)))
    (catch Throwable error
      (if (= :failure (:karcarthy/type (ex-data error)))
        (throw error)
        (fail! :schema :configuration "Invalid JSON Schema"
               {:schema schema} error)))))

(defn- json-schema-errors [schema value]
  (let [validator (make-json-schema-validator schema)]
    (try
      (vec (.validate validator (json/write-str value) InputFormat/JSON))
      (catch Throwable error
        [(str "Value is not JSON-compatible: "
              (or (ex-message error) error))]))))

(defn valid?
  "Return true when value satisfies a spec, predicate, class, set, or JSON Schema."
  [schema value]
  (cond
    (nil? schema) true
    (keyword? schema) (s/valid? schema value)
    (s/spec? schema) (s/valid? schema value)
    (fn? schema) (boolean (schema value))
    (class? schema) (instance? schema value)
    (set? schema) (contains? schema value)
    (json-schema? schema) (empty? (json-schema-errors schema value))
    :else (= schema value)))

(defn explain
  "Return a human-readable schema failure."
  [schema value]
  (cond
    (keyword? schema) (s/explain-str schema value)
    (s/spec? schema) (s/explain-str schema value)
    (json-schema? schema) (str (mapv str (json-schema-errors schema value)))
    :else (str "value " (pr-str value) " does not satisfy "
               (pr-str schema))))

(def ^:private predicate-json-types
  {`clojure.core/string? "string"
   `clojure.core/integer? "integer"
   `clojure.core/int? "integer"
   `clojure.core/number? "number"
   `clojure.core/boolean? "boolean"
   `clojure.core/map? "object"
   `clojure.core/vector? "array"
   `clojure.core/sequential? "array"})

(declare json-schema)

(defn- spec-form->json-schema [form]
  (cond
    (symbol? form)
    (when-let [type (get predicate-json-types form)] {:type type})

    (keyword? form)
    (json-schema form)

    (seq? form)
    (let [op (name (first form))
          args (rest form)]
      (case op
        "keys"
        (let [options (apply hash-map args)
              required (concat (:req options) (:req-un options))
              optional (concat (:opt options) (:opt-un options))
              fields (distinct (concat required optional))]
          {:type "object"
           :properties (into {}
                             (map (fn [key]
                                    [(name key) (or (json-schema key) {})]))
                             fields)
           :required (mapv name required)
           :additionalProperties false})

        "coll-of"
        {:type "array" :items (or (json-schema (first args)) {})}

        "nilable"
        {:anyOf [(or (json-schema (first args)) {}) {:type "null"}]}

        "and"
        {:allOf (mapv #(or (json-schema %) {}) args)}

        "or"
        {:anyOf (->> args (partition 2) (map second)
                     (mapv #(or (json-schema %) {})))}
        nil))

    :else nil))

(defn json-schema
  "Best-effort JSON Schema derivation for common clojure.spec schemas."
  [schema]
  (cond
    (nil? schema) nil
    (json-schema? schema) (do (json-schema-errors schema nil) schema)
    (keyword? schema) (some-> (s/get-spec schema) s/form
                                spec-form->json-schema)
    (s/spec? schema) (some-> schema s/form spec-form->json-schema)
    (= schema string?) {:type "string"}
    (= schema integer?) {:type "integer"}
    (= schema int?) {:type "integer"}
    (= schema number?) {:type "number"}
    (= schema boolean?) {:type "boolean"}
    (= schema map?) {:type "object"}
    (= schema vector?) {:type "array"}
    :else (spec-form->json-schema schema)))

(defn ^:no-doc check!
  "Return value when it satisfies schema; otherwise throw a structured failure."
  [phase schema value]
  (when-not (valid? schema value)
    (fail! :schema phase
           (str "Value does not satisfy schema for " (name phase))
           {:schema schema :value value :explain (explain schema value)}))
  value)

(defn ^:no-doc reject-unknown!
  "Reject unsupported unqualified keys and return the map unchanged.

  Namespaced keyword keys are extension points, following common Clojure map
  conventions."
  [label supported value]
  (when-let [unknown (seq (remove #(or (supported %)
                                       (and (keyword? %)
                                            (namespace %)))
                                 (keys value)))]
    (fail! :schema :configuration
           (str label " contains unknown configuration keys")
           {:unknown (vec unknown) :supported (vec (sort supported))}))
  value)
