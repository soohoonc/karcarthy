(ns karcarthy.tool
  "Flat, contracted Tool values."
  (:require [karcarthy.contract :as contract]))

(def ^:private config-keys
  #{:name :description :input :input-schema :output :output-schema :approval
    :enabled? :guardrails :timeout-ms :retry :to-model-output :metadata})

(defn- function-form [label bindings body]
  (when-not (and (vector? bindings) (= 1 (count bindings)))
    (throw (IllegalArgumentException. (str label " body requires [input]"))))
  (when-not (seq body)
    (throw (IllegalArgumentException. (str label " body cannot be empty"))))
  `(fn [run-context# input#]
     (let [~(first bindings) input#]
       ~@body)))

(defn ^:no-doc make-tool
  "Implementation constructor used by `tool` and `deftool`."
  [config definition expansion execute]
  (when-not (map? config)
    (contract/fail! :contract :configuration
                    "Tool configuration must be a map" {:value config}))
  (contract/reject-unknown! "Tool" config-keys config)
  (doseq [[key predicate message]
          [[:name #(and (string? %) (seq %))
            "Tool :name must be a non-empty string"]
           [:description string? "Tool :description must be a string"]]]
    (when-not (predicate (get config key))
      (contract/fail! :contract :configuration message {:config config})))
  (when-not (contains? config :input)
    (contract/fail! :contract :configuration
                    "Tool :input contract is required" {:config config}))
  (when-not (or (:input-schema config)
                (contract/json-schema? (:input config))
                (contract/json-schema (:input config)))
    (contract/fail! :contract :configuration
                    "Tool needs :input-schema when :input cannot be expressed as JSON Schema"
                    {:tool (:name config) :input (:input config)}))
  (assoc config
         :karcarthy/type :tool
         :definition definition
         :expansion expansion
         :execute execute))

(defmacro tool
  "Construct a contracted Tool backed by Clojure code."
  [config bindings & body]
  (let [source &form
        execute (function-form "tool" bindings body)
        expansion `(karcarthy.tool/make-tool ~config '~source nil ~execute)]
    `(karcarthy.tool/make-tool ~config '~source '~expansion ~execute)))

(defmacro deftool
  "Define a Tool, deriving its name from the var when omitted."
  [sym config bindings & body]
  (let [source &form
        execute (function-form "deftool" bindings body)
        expansion `(def ~sym
                     (karcarthy.tool/make-tool
                      (assoc ~config :name ~(name sym))
                      '~source nil ~execute))]
    `(def ~sym
       (let [config# ~config
             config# (if (contains? config# :name)
                       config#
                       (assoc config# :name ~(name sym)))]
         (karcarthy.tool/make-tool
          config# '~source '~expansion ~execute)))))

(defn tool? [value]
  (and (map? value)
       (= :tool (:karcarthy/type value))
       (string? (:name value))
       (fn? (:execute value))))

(defn hosted-tool
  "Describe a provider-executed Tool."
  [transport spec]
  (when-not (keyword? transport)
    (contract/fail! :contract :configuration
                    "Hosted Tool transport must be a keyword"
                    {:transport transport}))
  (when-not (map? spec)
    (contract/fail! :contract :configuration
                    "Hosted Tool spec must be a map" {:spec spec}))
  {:karcarthy/type :hosted-tool :transport transport :spec spec})

(defn hosted-tool? [value]
  (and (map? value)
       (= :hosted-tool (:karcarthy/type value))
       (keyword? (:transport value))
       (map? (:spec value))))

(defn definition
  "Return the Clojure definition retained by a Tool."
  [value]
  (:definition value))

(defn expansion
  "Return the macroexpansion retained by a Tool."
  [value]
  (:expansion value))
