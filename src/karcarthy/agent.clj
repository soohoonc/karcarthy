(ns karcarthy.agent
  "Flat, model-backed Agent values."
  (:refer-clojure :exclude [agent])
  (:require [karcarthy.schema :as schema]))

(def ^:private config-keys
  #{:name :description :model :instructions :context-schema :input-schema
    :tools :agents :output-schema :max-turns :input-guardrails
    :output-guardrails :limits})

(defn ^:no-doc normalize-model
  "Lower a model ID to the default OpenAI Responses configuration."
  [model]
  (if (string? model)
    {:transport :responses :provider :openai :id model}
    model))

(defn ^:no-doc make-agent
  "Implementation constructor used by `agent` and `defagent`."
  [config definition expansion definition-ns]
  (when-not (map? config)
    (schema/fail! :schema :configuration
                    "Agent configuration must be a map" {:value config}))
  (schema/reject-unknown! "Agent" config-keys config)
  (when-not (and (string? (:name config)) (seq (:name config)))
    (schema/fail! :schema :configuration
                    "Agent :name must be a non-empty string" {:config config}))
  (when (or (nil? (:model config)) (nil? (:instructions config)))
    (schema/fail! :schema :configuration
                    "An Agent requires :model and :instructions"
                    {:name (:name config)}))
  (assoc (update config :model normalize-model)
         :karcarthy/type :agent
         :definition-ns definition-ns
         :definition definition
         :expansion expansion))

(defmacro agent
  "Construct a model-backed Agent."
  [config]
  (let [source &form
        expansion `(karcarthy.agent/make-agent
                    ~config '~source nil '~(ns-name *ns*))]
    `(karcarthy.agent/make-agent
      ~config '~source '~expansion '~(ns-name *ns*))))

(defmacro defagent
  "Define an Agent, deriving its name from the var when omitted."
  [sym config]
  (let [source &form
        expansion `(def ~sym
                     (karcarthy.agent/make-agent
                      (assoc ~config :name ~(name sym))
                      '~source nil '~(ns-name *ns*)))]
    `(def ~sym
       (let [config# ~config
             config# (if (contains? config# :name)
                       config#
                       (assoc config# :name ~(name sym)))]
         (karcarthy.agent/make-agent
          config# '~source '~expansion '~(ns-name *ns*))))))

(defn agent? [value]
  (and (map? value)
       (= :agent (:karcarthy/type value))
       (string? (:name value))
       (some? (:model value))
       (some? (:instructions value))))

(defn definition
  "Return the Clojure definition retained by an Agent."
  [value]
  (:definition value))

(defn expansion
  "Return the macroexpansion retained by an Agent."
  [value]
  (:expansion value))
