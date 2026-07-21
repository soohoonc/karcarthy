(ns karcarthy.contract
  "Contracts and structured failures shared by Agents, Tools, and Runs."
  (:require [clojure.spec.alpha :as s]))

(defn failure
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

(defn fail!
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

(defn throwable->failure
  "Convert any Throwable into the public failure shape."
  [^Throwable error]
  (let [data (ex-data error)]
    (if (= :failure (:karcarthy/type data))
      (dissoc data :cause)
      (failure :program :execute (or (ex-message error) (str error))
               {:class (.getName (class error))}))))

(defn json-schema?
  "Return true for the JSON Schema shapes understood by karcarthy."
  [value]
  (and (map? value)
       (or (contains? value :type) (contains? value "type")
           (contains? value :properties) (contains? value "properties"))))

(declare valid?)

(defn- json-schema-valid? [schema value]
  (let [get-key #(or (get schema %) (get schema (name %)))
        type (get-key :type)
        properties (or (get-key :properties) {})
        required (set (map keyword (or (get-key :required) [])))]
    (and
     (cond
       (contains? #{"object" :object} type) (map? value)
       (contains? #{"array" :array} type) (sequential? value)
       (contains? #{"string" :string} type) (string? value)
       (contains? #{"number" :number} type) (number? value)
       (contains? #{"integer" :integer} type) (integer? value)
       (contains? #{"boolean" :boolean} type) (instance? Boolean value)
       (contains? #{"null" :null} type) (nil? value)
       :else true)
     (or (not (and (map? value) (seq required)))
         (every? #(contains? value %) required))
     (or (not (and (map? value) (seq properties)))
         (every? (fn [[key child-schema]]
                   (let [key (keyword (name key))]
                     (or (not (contains? value key))
                         (json-schema-valid? child-schema (get value key)))))
                 properties)))))

(defn valid?
  "Return true when value satisfies a spec, predicate, class, set, or JSON Schema."
  [contract value]
  (cond
    (nil? contract) true
    (keyword? contract) (s/valid? contract value)
    (s/spec? contract) (s/valid? contract value)
    (fn? contract) (boolean (contract value))
    (class? contract) (instance? contract value)
    (set? contract) (contains? contract value)
    (json-schema? contract) (json-schema-valid? contract value)
    :else (= contract value)))

(defn explain
  "Return a human-readable contract failure."
  [contract value]
  (cond
    (keyword? contract) (s/explain-str contract value)
    (s/spec? contract) (s/explain-str contract value)
    :else (str "value " (pr-str value) " does not satisfy "
               (pr-str contract))))

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
  "Best-effort JSON Schema derivation for common clojure.spec contracts."
  [contract]
  (cond
    (nil? contract) nil
    (json-schema? contract) contract
    (keyword? contract) (some-> (s/get-spec contract) s/form
                                spec-form->json-schema)
    (s/spec? contract) (some-> contract s/form spec-form->json-schema)
    (= contract string?) {:type "string"}
    (= contract integer?) {:type "integer"}
    (= contract int?) {:type "integer"}
    (= contract number?) {:type "number"}
    (= contract boolean?) {:type "boolean"}
    (= contract map?) {:type "object"}
    (= contract vector?) {:type "array"}
    :else (spec-form->json-schema contract)))

(defn check!
  "Return value when it satisfies contract; otherwise throw a structured failure."
  [phase contract value]
  (when-not (valid? contract value)
    (fail! :contract phase
           (str "Value does not satisfy contract for " (name phase))
           {:contract contract :value value :explain (explain contract value)}))
  value)

(defn reject-unknown!
  "Reject keys outside supported and return the map unchanged."
  [label supported value]
  (when-let [unknown (seq (remove supported (keys value)))]
    (fail! :contract :configuration
           (str label " contains unknown configuration keys")
           {:unknown (vec unknown) :supported (vec (sort supported))}))
  value)
