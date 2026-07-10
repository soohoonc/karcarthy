(ns karcarthy.core
  "The karcarthy kernel: executable Agents and Tools, the model/Tool loop,
  contracts, limits, and observation."
  (:refer-clojure :exclude [agent await run!])
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [karcarthy.prompt :as prompt]
            [karcarthy.session :as session])
  (:import [java.util UUID]
           [java.util.concurrent Callable ExecutionException Executors Future
            Semaphore TimeUnit TimeoutException]))

;; ---------------------------------------------------------------------------
;; Failures and contracts
;; ---------------------------------------------------------------------------

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
  "Throw an ExceptionInfo carrying a stable karcarthy failure map."
  ([kind phase message]
   (fail! kind phase message nil nil))
  ([kind phase message data]
   (fail! kind phase message data nil))
  ([kind phase message data cause]
   (let [f (failure kind phase message data cause)]
     (throw (if cause
              (ex-info message f cause)
              (ex-info message f))))))

(defn throwable->failure
  "Convert any Throwable into the public failure shape."
  [^Throwable t]
  (let [data (ex-data t)]
    (if (= :failure (:karcarthy/type data))
      (dissoc data :cause)
      (failure :program :execute (or (ex-message t) (str t))
               {:class (.getName (class t))}))))

(defn- json-schema? [x]
  (and (map? x)
       (or (contains? x :type) (contains? x "type")
           (contains? x :properties) (contains? x "properties"))))

(declare contract-valid?)

(defn- json-schema-valid?
  [schema value]
  (let [g #(or (get schema %) (get schema (name %)))
        type (g :type)
        properties (or (g :properties) {})
        required (set (map keyword (or (g :required) [])))]
    (and
     (case type
       "object" (map? value)
       :object (map? value)
       "array" (sequential? value)
       :array (sequential? value)
       "string" (string? value)
       :string (string? value)
       "number" (number? value)
       :number (number? value)
       "integer" (integer? value)
       :integer (integer? value)
       "boolean" (instance? Boolean value)
       :boolean (instance? Boolean value)
       "null" (nil? value)
       :null (nil? value)
       true)
     (if (and (map? value) (seq required))
       (every? #(contains? value %) required)
       true)
     (if (and (map? value) (seq properties))
       (every? (fn [[k child-schema]]
                 (let [kk (keyword (name k))]
                   (or (not (contains? value kk))
                       (json-schema-valid? child-schema (get value kk)))))
               properties)
       true))))

(defn contract-valid?
  "Return true when `value` satisfies a Clojure spec, predicate, class, set,
  or basic JSON Schema contract. Nil means unconstrained."
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

(defn explain-contract
  "Return a human-readable contract failure."
  [contract value]
  (cond
    (keyword? contract) (s/explain-str contract value)
    (s/spec? contract) (s/explain-str contract value)
    :else (str "value " (pr-str value) " does not satisfy " (pr-str contract))))

(def ^:private predicate-json-types
  {`clojure.core/string? "string"
   `clojure.core/integer? "integer"
   `clojure.core/int? "integer"
   `clojure.core/number? "number"
   `clojure.core/boolean? "boolean"
   `clojure.core/map? "object"
   `clojure.core/vector? "array"
   `clojure.core/sequential? "array"})

(declare contract->json-schema)

(defn- spec-form->json-schema [form]
  (cond
    (symbol? form)
    (when-let [type (get predicate-json-types form)] {:type type})

    (keyword? form)
    (contract->json-schema form)

    (seq? form)
    (let [op (name (first form))
          args (rest form)]
      (case op
        "keys"
        (let [opts (apply hash-map args)
              req (concat (:req opts) (:req-un opts))
              opt (concat (:opt opts) (:opt-un opts))
              fields (distinct (concat req opt))]
          {:type "object"
           :properties
           (into {}
                 (map (fn [k]
                        [(name k) (or (contract->json-schema k) {})]))
                 fields)
           :required (mapv name req)
           :additionalProperties false})

        "coll-of"
        {:type "array"
         :items (or (contract->json-schema (first args)) {})}

        "nilable"
        {:anyOf [(or (contract->json-schema (first args)) {})
                 {:type "null"}]}

        "and"
        {:allOf (mapv #(or (contract->json-schema %) {}) args)}

        "or"
        {:anyOf (->> args (partition 2) (map second)
                     (mapv #(or (contract->json-schema %) {})))}
        nil))
    :else nil))

(defn contract->json-schema
  "Best-effort JSON Schema derivation for common clojure.spec contracts."
  [contract]
  (cond
    (nil? contract) nil
    (json-schema? contract) contract
    (keyword? contract) (some-> (s/get-spec contract) s/form spec-form->json-schema)
    (s/spec? contract) (some-> contract s/form spec-form->json-schema)
    (= contract string?) {:type "string"}
    (= contract integer?) {:type "integer"}
    (= contract int?) {:type "integer"}
    (= contract number?) {:type "number"}
    (= contract boolean?) {:type "boolean"}
    (= contract map?) {:type "object"}
    (= contract vector?) {:type "array"}
    :else (spec-form->json-schema contract)))

(defn check-contract!
  [phase contract value]
  (when-not (contract-valid? contract value)
    (fail! :contract phase
           (str "Value does not satisfy contract for " (name phase))
           {:contract contract
            :value value
            :explain (explain-contract contract value)}))
  value)

;; ---------------------------------------------------------------------------
;; Agents and tools
;; ---------------------------------------------------------------------------

(def ^:private agent-config-keys
  #{:name :description :model :instructions :context :input :input-schema
    :tools :agents :output :output-schema :max-turns :stop-when :guardrails
    :limits :metadata})

(def ^:private tool-config-keys
  #{:name :description :input :input-schema :output :output-schema :approval
    :enabled? :guardrails :timeout-ms :retry :to-model-output :metadata})

(declare run-agent!)

(def ^:dynamic ^:no-doc *run-context* nil)

(defn ^:no-doc current-run-context
  "Return the internal context for the Agent or Tool currently running."
  []
  (or *run-context*
      (fail! :program :agent
             "This operation is only available while an Agent is running")))

(defn- reject-unknown!
  [label supported m]
  (when-let [unknown (seq (remove supported (keys m)))]
    (fail! :contract :configuration
           (str label " contains unknown configuration keys")
           {:unknown (vec unknown) :supported (vec (sort supported))}))
  m)

(defn make-agent
  "Implementation constructor used by `agent` and `defagent`."
  ([config definition expansion program]
   (make-agent config definition expansion program nil))
  ([config definition expansion program definition-ns]
   (when-not (map? config)
     (fail! :contract :configuration "Agent configuration must be a map"
            {:value config}))
   (reject-unknown! "Agent" agent-config-keys config)
   (when-not (and (string? (:name config)) (seq (:name config)))
     (fail! :contract :configuration "Agent :name must be a non-empty string"
            {:config config}))
   (when (and (nil? program)
              (or (nil? (:model config)) (nil? (:instructions config))))
     (fail! :contract :configuration
            (str "An Agent without a Clojure program requires "
                 ":model and :instructions")
            {:name (:name config)}))
   {:karcarthy/type :agent
    :name (:name config)
    :config config
    :definition-ns definition-ns
    :definition definition
    :expansion expansion
    :body program}))

(defn- body-function-form [label bindings body]
  (when-not (and (vector? bindings) (= 1 (count bindings)))
    (throw (IllegalArgumentException.
            (str label " body requires [input]"))))
  (when-not (seq body)
    (throw (IllegalArgumentException.
            (str label " body cannot be empty"))))
  `(fn [run-context# input#]
     (let [~(first bindings) input#]
       ~@body)))

(defmacro agent
  "Construct an executable Agent. A configured Agent with no body uses the
  native model/tool loop; `[input] body ...` supplies a Clojure program."
  [config & body]
  (let [source &form
        program (when (seq body)
                  (body-function-form "agent" (first body) (next body)))
        expansion `(karcarthy.core/make-agent ~config '~source nil ~program '~(ns-name *ns*))]
    `(karcarthy.core/make-agent ~config '~source '~expansion ~program '~(ns-name *ns*))))

(defmacro defagent
  "Define a var containing an Agent. The symbol supplies the default :name."
  [sym config & body]
  (let [source &form
        program (when (seq body)
                  (body-function-form "defagent" (first body) (next body)))
        expansion `(def ~sym
                     (karcarthy.core/make-agent
                      (assoc ~config :name ~(name sym)) '~source nil ~program '~(ns-name *ns*)))]
    `(def ~sym
       (let [config# ~config
             config# (if (contains? config# :name)
                       config#
                       (assoc config# :name ~(name sym)))]
         (karcarthy.core/make-agent
          config# '~source '~expansion ~program '~(ns-name *ns*))))))

(defn agent?
  [x]
  (and (map? x)
       (= :agent (:karcarthy/type x))
       (string? (:name x))
       (map? (:config x))
       (or (nil? (:body x)) (fn? (:body x)))))

(defn definition
  "Return the Clojure definition that created an Agent or Tool."
  [agent-or-tool]
  (:definition agent-or-tool))

(defn expansion
  "Return the macroexpanded Clojure definition for an Agent or Tool."
  [agent-or-tool]
  (:expansion agent-or-tool))

(defn make-tool
  "Implementation constructor used by `tool` and `deftool`."
  [config definition expansion execute]
  (when-not (map? config)
    (fail! :contract :configuration "Tool configuration must be a map"
           {:value config}))
  (reject-unknown! "Tool" tool-config-keys config)
  (doseq [[k pred message] [[:name #(and (string? %) (seq %))
                             "Tool :name must be a non-empty string"]
                            [:description string?
                             "Tool :description must be a string"]]]
    (when-not (pred (get config k))
      (fail! :contract :configuration message {:config config})))
  (when-not (contains? config :input)
    (fail! :contract :configuration "Tool :input contract is required"
           {:config config}))
  {:karcarthy/type :tool
   :name (:name config)
   :description (:description config)
   :config config
   :definition definition
   :expansion expansion
   :execute execute})

(defmacro tool
  "Construct a contracted Tool backed by Clojure code."
  [config bindings & body]
  (let [source &form
        execute (body-function-form "tool" bindings body)
        expansion `(karcarthy.core/make-tool ~config '~source nil ~execute)]
    `(karcarthy.core/make-tool ~config '~source '~expansion ~execute)))

(defmacro deftool
  "Define a var containing a Tool. The symbol supplies the default :name."
  [sym config bindings & body]
  (let [source &form
        execute (body-function-form "deftool" bindings body)
        expansion `(def ~sym
                     (karcarthy.core/make-tool
                      (assoc ~config :name ~(name sym)) '~source nil ~execute))]
    `(def ~sym
       (let [config# ~config
             config# (if (contains? config# :name)
                       config#
                       (assoc config# :name ~(name sym)))]
         (karcarthy.core/make-tool
          config# '~source '~expansion ~execute)))))

(defn tool?
  [x]
  (and (map? x)
       (= :tool (:karcarthy/type x))
       (string? (:name x))
       (fn? (:execute x))))

(defn hosted-tool
  "Describe a transport-executed tool. The transport adapter lowers `spec` to
  its native API; karcarthy never pretends to execute it locally."
  [transport spec]
  (when-not (keyword? transport)
    (fail! :contract :configuration
           "Hosted Tool transport must be a keyword"
           {:transport transport}))
  (when-not (map? spec)
    (fail! :contract :configuration
           "Hosted Tool spec must be a map"
           {:spec spec}))
  {:karcarthy/type :hosted-tool
   :transport transport
   :spec spec})

(defn hosted-tool? [x]
  (and (map? x)
       (= :hosted-tool (:karcarthy/type x))
       (keyword? (:transport x))
       (map? (:spec x))))

(def ^:private agent-request-schema
  {:type "object"
   :properties
   {"source"
    {:type "string"
     :description
     (str "Exactly one complete Clojure (agent ...) form following the "
          "generation grammar in this Tool description.")}
    "input"
    {:description
     (str "The complete input value passed to the generated Agent. Include "
          "all task-specific information it needs.")}}
   :required ["source" "input"]
   :additionalProperties false})

(def ^:private generated-symbol-reservations
  #{"agent" "tool" "run!" "context" "model!" "emit!" "definition"
    "expansion" "compile-agent!"})

(defn- generated-symbol [kind value-name]
  (let [clean (-> (str value-name)
                  (str/replace #"[^A-Za-z0-9*+!_?.-]+" "-")
                  (str/replace #"^-+|-+$" ""))
        clean (if (or (str/blank? clean)
                      (re-find #"^[0-9]" clean)
                      (contains? generated-symbol-reservations clean))
                (str (name kind) "-" (if (str/blank? clean) "value" clean))
                clean)]
    (symbol clean)))

(defn- hosted-tool-name [hosted]
  (let [spec (:spec hosted)]
    (or (:name spec) (get spec "name")
        (:type spec) (get spec "type")
        "hosted-tool")))

(defn- capability-entry [kind value]
  (let [config (:config value)
        value-name (case kind
                     :agent (:name value)
                     :tool (:name value)
                     :hosted-tool (hosted-tool-name value))
        description
        (case kind
          :agent (or (:description config) "No description provided.")
          :tool (or (:description value) "No description provided.")
          :hosted-tool "Provider-hosted capability.")
        schema
        (case kind
          :agent (or (:input-schema config)
                     (contract->json-schema (:input config)))
          :tool (or (:input-schema config)
                    (contract->json-schema (:input config)))
          :hosted-tool (:spec value))]
    {:kind kind
     :name (str value-name)
     :symbol (generated-symbol kind value-name)
     :description description
     :schema schema
     :value value}))

(defn- capability-entries [tools agents]
  (let [entries
        (concat
         (map #(capability-entry (if (hosted-tool? %) :hosted-tool :tool) %)
              tools)
         (map #(capability-entry :agent %) agents))
        duplicate-symbols
        (->> entries
             (map :symbol)
             frequencies
             (keep (fn [[sym n]] (when (> n 1) sym)))
             sort
             vec)]
    (when (seq duplicate-symbols)
      (fail! :contract :configuration
             "Available Tools and Agents produce duplicate Clojure symbols"
             {:symbols duplicate-symbols}))
    (vec entries)))

(defn- model-source-config [model]
  (when (map? model)
    (let [config (select-keys model [:transport :provider :id :reasoning])]
      (cond-> config
        (not (keyword? (:transport config))) (dissoc :transport)))))

(defn- catalog-lines [kinds entries]
  (let [kinds (if (set? kinds) kinds #{kinds})
        entries (filter #(contains? kinds (:kind %)) entries)]
    (if (seq entries)
      (str/join
       "\n"
       (map (fn [{:keys [name symbol description schema]}]
              (str "- `" symbol "` (model name `" name "`) — " description
                   (when schema (str " Input: `" (pr-str schema) "`"))))
            entries))
      "- None.")))

(defn- agent-capability-description [model entries]
  (let [model-config (model-source-config model)]
    (prompt/agent-tool-prompt
     {:model-configuration
      (if (seq model-config)
        (str "```clojure\n" (pr-str model-config) "\n```")
        (str "No reusable printable model configuration is available. Use a "
             "Clojure-program Agent or an explicitly configured model."))
      :tools (catalog-lines #{:tool :hosted-tool} entries)
      :agents (catalog-lines :agent entries)})))

(defn ^:no-doc agent-capability
  "Tool that evaluates and runs an Agent definition in the current Run."
  [model tools agents]
  (let [entries (capability-entries tools agents)
        bindings (into {} (map (juxt :symbol :value)) entries)]
    (make-tool
     {:name "agent"
      :description (agent-capability-description model entries)
      :input agent-request-schema
      :input-schema agent-request-schema
      :output any?
      :approval :never}
     '(agent)
     '(karcarthy.core/agent-capability)
     (fn [rt {:keys [source input]}]
       (let [compile-agent! (requiring-resolve
                             'karcarthy.eval/compile-agent-in-run!)
             generated (compile-agent!
                        (assoc rt :evaluation-bindings bindings)
                        source)]
         (run-agent! rt generated input {}))))))

(def ^:private agent-call-schema
  {:type "object"
   :properties
   {"input" {:description "The complete input value passed to this Agent."}}
   :required ["input"]
   :additionalProperties false})

(defn- object-schema? [schema]
  (contains? #{"object" :object}
             (or (:type schema) (get schema "type"))))

(defn- agent-tool
  [agent]
  (when-not (agent? agent)
    (fail! :contract :configuration
           "Agent :agents must contain Agent values"
           {:value agent}))
  (let [config (:config agent)
        schema (or (:input-schema config)
                   (contract->json-schema (:input config)))
        structured-input? (object-schema? schema)
        tool-schema (if structured-input? schema agent-call-schema)
        tool-input (if structured-input?
                     (or (:input config) any?)
                     agent-call-schema)]
    (make-tool
     {:name (:name agent)
      :description
      (str (or (:description config)
               (str "Ask " (:name agent) " to complete a task."))
           " This Agent starts without the parent conversation and receives "
           (if structured-input?
             "the Tool input object directly."
             "only the value in the `input` field.")
           " Its final output returns as the Tool result.")
      :input tool-input
      :input-schema tool-schema
      :output (:output config)
      :approval :never}
     nil
     nil
     (fn [rt input]
       (run-agent! rt agent
                   (if structured-input? input (:input input))
                   {})))))

;; ---------------------------------------------------------------------------
;; Run context, limits, and observation
;; ---------------------------------------------------------------------------

(def default-limits
  {:model-calls 100
   :input-tokens Long/MAX_VALUE
   :output-tokens Long/MAX_VALUE
   :agent-depth 8
   :parallelism 16
   :generated-forms 20
   :deadline-ms nil})

(def ^:private run-option-keys
  #{:context :session :limits :observe :approval :cancel :metadata
    :model-transports :evaluation-namespace})

(def ^:private agent-call-option-keys #{:context :limits})

(defn- validate-limits!
  [limits]
  (when-not (map? limits)
    (fail! :contract :configuration "Run limits must be a map"
           {:value limits}))
  (reject-unknown! "Limits" (set (keys default-limits)) limits)
  (doseq [resource [:model-calls :input-tokens :output-tokens
                    :agent-depth :generated-forms]]
    (let [value (get limits resource)]
      (when-not (and (integer? value) (not (neg? value)))
        (fail! :contract :configuration
               (str (name resource) " must be a non-negative integer")
               {:resource resource :value value}))))
  (when-not (and (integer? (:parallelism limits))
                 (pos? (:parallelism limits)))
    (fail! :contract :configuration
           ":parallelism must be a positive integer"
           {:value (:parallelism limits)}))
  (when-let [deadline-ms (:deadline-ms limits)]
    (when-not (and (integer? deadline-ms) (not (neg? deadline-ms)))
      (fail! :contract :configuration
             ":deadline-ms must be nil or a non-negative integer"
             {:value deadline-ms})))
  limits)

(defn- id [prefix]
  (str prefix (UUID/randomUUID)))

(defn context
  "Return the local, non-model-visible context for the current Agent call."
  ([]
   (context (current-run-context)))
  ([run-context]
   (:context run-context)))

(defn- call-metadata
  [rt]
  {:run-id (:run-id rt)
   :agent-id (:agent-id rt)
   :parent-id (:parent-id rt)
   :depth (:depth rt)
   :context (:context rt)
   :limits (:limits rt)
   :usage @(:usage rt)
   :agent (:agent rt)})

(defn emit!
  "Record one observation event and notify the run observer."
  ([event]
   (emit! (current-run-context) event))
  ([rt event]
   (let [event (merge {:karcarthy/type :event
                       :time-ms (System/currentTimeMillis)
                       :run-id (:run-id rt)}
                      (select-keys rt [:agent-id :parent-id :depth])
                      event)]
     (swap! (:events rt) conj event)
     (doseq [observer (when-let [f (:observe rt)] [f])]
       (try (observer event) (catch Throwable _ nil)))
     event)))

(defn events
  "Return the events recorded by a Run."
  [run]
  (let [v (:events run)]
    (if (instance? clojure.lang.IDeref v) @v (vec v))))

(defn- cancellation-requested? [cancel]
  (cond
    (nil? cancel) false
    (fn? cancel) (boolean (cancel))
    (instance? clojure.lang.IDeref cancel) (boolean @cancel)
    :else (boolean cancel)))

(defn ^:no-doc check-run!
  [rt]
  (when (cancellation-requested? (:cancel rt))
    (fail! :cancellation :run "Run was cancelled"))
  (when-let [deadline (:deadline-ns rt)]
    (when (>= (System/nanoTime) deadline)
      (fail! :deadline :run "Run deadline was exceeded")))
  rt)

(defn- limit-value [rt k]
  (get (:limits rt) k Long/MAX_VALUE))

(defn consume!
  "Atomically consume a run-tree resource budget."
  [rt k n]
  (check-run! rt)
  (loop []
    (let [before @(:usage rt)
          after (update before k (fnil + 0) n)
          limit (limit-value rt k)]
      (when (and limit (> (get after k) limit))
        (fail! :budget :run
               (str "Run limit exceeded: " (name k))
               {:resource k :used (get before k) :requested n :limit limit}))
      (if (compare-and-set! (:usage rt) before after)
        (get after k)
        (recur)))))

(defn- narrower-limits [parent child]
  (merge-with (fn [a b]
                (cond
                  (nil? a) b
                  (nil? b) a
                  :else (min a b)))
              parent (or child {})))

(defn- run-guardrails!
  [rt phase guards value]
  (doseq [guard (or guards [])]
    (let [result (guard (call-metadata rt) value)]
      (when (or (false? result)
                (and (map? result) (false? (:ok? result))))
        (fail! :guardrail phase
               (str "Guardrail rejected " (name phase))
               {:result result :value value}))))
  value)

;; ---------------------------------------------------------------------------
;; Model transport
;; ---------------------------------------------------------------------------

(defn fake-model
  "Deterministic in-process model transport for tests and examples."
  [respond]
  (when-not (fn? respond)
    (throw (IllegalArgumentException. "fake-model requires a function")))
  {:karcarthy/type :model-transport
   :complete respond})

(defn- resolve-value [value rt]
  (if (fn? value) (value (call-metadata rt)) value))

(defn- resolve-transport [rt model]
  (let [configured (:transport model)
        transport
        (if (keyword? configured)
          (or (get (:model-transports rt) configured)
              (when (= :responses configured)
                (deref (requiring-resolve
                        'karcarthy.model.responses/transport))))
          configured)]
    (or transport
        (fail! :model :configuration
               "No model transport is configured"
               {:transport configured :model model}))))

(defn- call-transport [transport request emit-delta!]
  (cond
    (and (map? transport)
         (not= false (get-in request [:model :stream]))
         (fn? (:stream transport)))
    ((:stream transport) request emit-delta!)

    (and (map? transport) (fn? (:complete transport)))
    ((:complete transport) request)

    (ifn? transport) (transport request)

    :else
    (fail! :model :configuration "Invalid model transport"
           {:transport transport})))

(defn- record-model-delta! [rt delta]
  (check-run! rt)
  (case (:type delta)
    :text-delta
    (emit! rt {:type :model/text-delta :delta (:delta delta)})

    :tool-call-delta
    (emit! rt (assoc (dissoc delta :type) :type :model/tool-call-delta))

    (emit! rt {:type :model/stream-event :event delta})))

(defn- normalize-model-response [response]
  (cond
    (string? response) {:type :final :output response}
    (not (map? response)) {:type :final :output response}
    (= :final (:type response)) response
    (= :tool-calls (:type response)) response
    (seq (:tool-calls response))
    (assoc response :type :tool-calls :calls (:tool-calls response))
    (contains? response :output) (assoc response :type :final)
    :else (fail! :model :response "Model transport returned an invalid response"
                 {:response response})))

(defn model!
  "Call the active model transport with one normalized request."
  ([request]
   (model! (current-run-context) request))
  ([rt request]
   (check-run! rt)
   (consume! rt :model-calls 1)
   (let [model (resolve-value (:model request) rt)
         transport (resolve-transport rt model)
         request (assoc request :model model)
         started (System/nanoTime)]
     (emit! rt {:type :model/requested
                :model (select-keys model [:provider :transport :id])})
     (try
       (let [response (normalize-model-response
                       (call-transport transport request
                                       #(record-model-delta! rt %)))
             usage (:usage response)]
         (when-let [n (or (:input-tokens usage) (:input_tokens usage))]
           (consume! rt :input-tokens n))
         (when-let [n (or (:output-tokens usage) (:output_tokens usage))]
           (consume! rt :output-tokens n))
         (emit! rt {:type :model/completed
                    :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                    :response-type (:type response)
                    :usage usage})
         response)
       (catch Throwable t
         (emit! rt {:type :model/failed
                    :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                    :error (throwable->failure t)})
         (if (= :failure (:karcarthy/type (ex-data t)))
           (throw t)
           (fail! :model :request (or (ex-message t) (str t)) nil t)))))))

;; ---------------------------------------------------------------------------
;; Tool execution and the native model loop
;; ---------------------------------------------------------------------------

(defn- default-json-schema []
  {:type "object" :properties {} :additionalProperties true})

(defn- tool-descriptor [tool]
  (cond
    (tool? tool)
    (let [config (:config tool)]
      {:kind :function
       :name (:name tool)
       :description (:description tool)
       :parameters (or (:input-schema config)
                       (when (json-schema? (:input config)) (:input config))
                       (contract->json-schema (:input config))
                       (default-json-schema))})

    (hosted-tool? tool)
    {:kind :hosted
     :transport (:transport tool)
     :spec (:spec tool)}

    :else
    (fail! :tool :configuration "Agent contains an invalid Tool"
           {:value tool})))

(defn- approval-policy
  [policy rt input]
  (if (fn? policy) (policy (call-metadata rt) input) policy))

(defn- request-approval!
  [rt tool input policy]
  (emit! rt {:type :tool/approval-requested
             :tool (:name tool) :input input :policy policy})
  (let [allowed?
        (if-let [handler (:approval rt)]
          (boolean (handler (assoc (call-metadata rt)
                                   :tool tool
                                   :input input)))
          false)]
    (emit! rt {:type :tool/approval-resolved
               :tool (:name tool) :approved? allowed? :policy policy})
    allowed?))

(defn- approved?
  [rt tool input]
  (let [policy (approval-policy (get-in tool [:config :approval] :never)
                                rt input)]
    (cond
      (not (contains? #{true :always :once} policy)) true
      (not= :once policy) (request-approval! rt tool input policy)
      :else
      (let [approvals (:approvals rt)
            approval-key [:tool (:name tool)]]
        (locking approvals
          (if (contains? @approvals approval-key)
            (do
              (emit! rt {:type :tool/approval-reused
                         :tool (:name tool) :policy policy})
              true)
            (let [allowed? (request-approval! rt tool input policy)]
              (when allowed? (swap! approvals conj approval-key))
              allowed?)))))))

(defn- retry-count [tool]
  (let [retry (get-in tool [:config :retry] 0)]
    (cond
      (integer? retry) (max 0 retry)
      (map? retry) (max 0 (long (or (:max-retries retry) 0)))
      :else 0)))

(defn- execute-tool-body! [rt tool input]
  (loop [attempt 0]
    (let [outcome (try
                    {:value (binding [*run-context* rt]
                              ((:execute tool) rt input))}
                    (catch Throwable t {:error t}))]
      (if-let [error (:error outcome)]
        (if (< attempt (retry-count tool))
          (recur (inc attempt))
          (throw error))
        (:value outcome)))))

(defn- execute-with-timeout!
  [rt timeout-ms f]
  (if-not timeout-ms
    (f)
    (let [^Future future (.submit (:executor rt) ^Callable (reify Callable
                                                             (call [_] (f))))]
      (try
        (.get future (long timeout-ms) TimeUnit/MILLISECONDS)
        (catch TimeoutException t
          (.cancel future true)
          (fail! :tool :timeout "Tool timed out" {:timeout-ms timeout-ms} t))
        (catch ExecutionException e
          (throw (or (.getCause e) e)))))))

(defn- run-tool!
  [rt tool call]
  (when-not (tool? tool)
    (fail! :tool :configuration "Agent contains an invalid Tool"
           {:value tool}))
  (let [config (:config tool)
        input (:input call)
        call-id (or (:id call) (id "tool_"))
        enabled? (:enabled? config)]
    (when (and enabled? (not (enabled? (call-metadata rt))))
      (fail! :tool :disabled "Tool is disabled in the current Run"
             {:tool (:name tool)}))
    (check-contract! :tool-input (:input config) input)
    (run-guardrails! rt :tool-input (get-in config [:guardrails :input]) input)
    (when-not (approved? rt tool input)
      (fail! :approval :tool "Tool approval was denied"
             {:tool (:name tool) :input input}))
    (emit! rt {:type :tool/started :tool (:name tool)
               :tool-call-id call-id :input input})
    (let [started (System/nanoTime)]
      (try
        (let [output (execute-with-timeout!
                      rt (:timeout-ms config)
                      #(execute-tool-body! rt tool input))
              _ (check-contract! :tool-output (:output config) output)
              _ (run-guardrails! rt :tool-output
                                 (get-in config [:guardrails :output]) output)
              model-output (if-let [project (:to-model-output config)]
                             (project output)
                             output)
              result {:id call-id
                      :name (:name tool)
                      :output output
                      :model-output model-output}]
          (emit! rt {:type :tool/completed :tool (:name tool)
                     :tool-call-id call-id
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :output output})
          result)
        (catch Throwable t
          (emit! rt {:type :tool/failed :tool (:name tool)
                     :tool-call-id call-id
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :error (throwable->failure t)})
          (if (= :failure (:karcarthy/type (ex-data t)))
            (throw t)
            (fail! :tool :execute (or (ex-message t) (str t))
                   {:tool (:name tool)} t)))))))

(defn- submit-limited! [rt f]
  (let [^Semaphore semaphore (:semaphore rt)]
    (when-not (.tryAcquire semaphore)
      (fail! :budget :parallelism "Run parallelism limit was reached"
             {:limit (get-in rt [:limits :parallelism])}))
    (try
      (.submit (:executor rt)
               ^Callable
               (reify Callable
                 (call [_]
                   (try (f) (finally (.release semaphore))))))
      (catch Throwable t
        (.release semaphore)
        (throw t)))))

(defn- await-future!
  [^Future child]
  (try
    (.get child)
    (catch ExecutionException e
      (throw (or (.getCause e) e)))))

(defn- await-futures!
  [children]
  (mapv await-future! children))

(defn- maybe-json-output [output contract]
  (if (and contract (string? output)
           (re-find #"^\s*[\[{]" output))
    (try (json/read-str output :key-fn keyword)
         (catch Throwable _ output))
    output))

(def ^:private message-roles #{:system :user :assistant :tool})

(defn- valid-message? [message]
  (and (map? message)
       (contains? message-roles (:role message))))

(defn- session-items! [active-session]
  (if-not active-session
    []
    (let [items (try
                  (session/get-items active-session)
                  (catch Throwable error
                    (fail! :session :read
                           (or (ex-message error) "Session read failed")
                           nil error)))]
      (when-not (sequential? items)
        (fail! :session :read "Session items must be sequential"
               {:value items}))
      (doseq [item items]
        (when-not (valid-message? item)
          (fail! :session :read "Session contains an invalid message"
                 {:value item})))
      (vec items))))

(defn- append-session-items! [rt items]
  (let [items (vec items)]
    (when (and (:session rt) (seq items))
      (try
        (session/add-items! (:session rt) items)
        (catch Throwable error
          (fail! :session :write
                 (or (ex-message error) "Session write failed")
                 nil error)))
      (emit! rt {:type :session/updated
                 :session-id (session/session-id (:session rt))
                 :items (count items)})))
  nil)

(defn- instructions! [rt value]
  (let [resolved
        (try
          (resolve-value value rt)
          (catch clojure.lang.ExceptionInfo error
            (if (= :failure (:karcarthy/type (ex-data error)))
              (throw error)
              (fail! :instructions :resolve
                     (or (ex-message error) "Instructions resolution failed")
                     nil error)))
          (catch Throwable error
            (fail! :instructions :resolve
                   (or (ex-message error) "Instructions resolution failed")
                   nil error)))]
    (when-not (string? resolved)
      (fail! :instructions :resolve
             "Agent :instructions must resolve to a string"
             {:value resolved}))
    resolved))

(defn- model-instructions! [rt value]
  (let [base (prompt/system-prompt)
        configured (instructions! rt value)]
    (if (str/starts-with? configured base)
      configured
      (str base "\n\n## Agent instructions\n\n" configured))))

(defn- unique-tools! [tools]
  (let [duplicates (->> tools
                        (filter tool?)
                        (map :name)
                        frequencies
                        (keep (fn [[name n]] (when (> n 1) name)))
                        sort
                        vec)]
    (when (seq duplicates)
      (fail! :contract :configuration
             "Agent contains duplicate Tool or Agent names"
             {:names duplicates}))
    tools))

(defn- default-loop!
  [rt agent input]
  (let [config (:config agent)
        max-turns (or (:max-turns config) 20)
        model (resolve-value (:model config) rt)
        tools-value (:tools config)
        direct-tools (vec (or (resolve-value tools-value rt) []))
        agents-value (:agents config)
        available-agents (vec (or (resolve-value agents-value rt) []))
        _ (doseq [tool direct-tools]
            (when-not (or (tool? tool) (hosted-tool? tool))
              (fail! :tool :configuration
                     "Agent :tools must contain Tool values"
                     {:value tool})))
        known-agent-tools (mapv agent-tool available-agents)
        tools (unique-tools!
               (conj (into direct-tools known-agent-tools)
                     (agent-capability model direct-tools available-agents)))
        tool-map (into {} (comp (filter tool?) (map (juxt :name identity))) tools)
        instructions (model-instructions! rt (:instructions config))
        prior (session-items! (:session rt))
        user-message {:role :user :content input}
        messages (conj prior user-message)]
    (loop [turn 1
           messages messages
           pending messages
           provider-state nil
           unpersisted [user-message]]
      (check-run! rt)
      (when (> turn max-turns)
        (fail! :budget :model-loop "Agent exceeded :max-turns"
               {:agent (:name agent) :max-turns max-turns}))
      (let [request {:agent agent
                     :model model
                     :instructions instructions
                     :messages pending
                     :provider-state provider-state
                     :tools (mapv tool-descriptor tools)
                     :output-schema (or (:output-schema config)
                                        (contract->json-schema
                                         (:output config)))
                     :turn turn}
            response (model! rt request)]
        (case (:type response)
          :final
          (let [output (maybe-json-output (:output response) (:output config))]
            (when-let [stop-when (:stop-when config)]
              (when-not (stop-when (call-metadata rt)
                                   {:response response :messages messages})
                (fail! :model :stop-condition
                       "Model returned final output before the stop condition"
                       {:agent (:name agent)})))
            (reset! (:pending-session-items rt)
                    (conj (vec unpersisted)
                          {:role :assistant :content output}))
            output)

          :tool-calls
          (let [calls (vec (:calls response))
                jobs (mapv (fn [call]
                             (let [tool (get tool-map (:name call))]
                               (when-not tool
                                 (fail! :tool :not-found
                                        (str "Unknown tool: " (:name call))
                                        {:known (vec (keys tool-map))}))
                               (submit-limited! rt #(run-tool! rt tool call))))
                           calls)
                results (await-futures! jobs)
                assistant-message {:role :assistant :tool-calls calls}
                result-messages
                (mapv (fn [{:keys [id name model-output]}]
                        {:role :tool
                         :tool-call-id id
                         :name name
                         :content model-output})
                      results)
                new-items (into [assistant-message] result-messages)]
            (let [messages (into messages new-items)]
              (append-session-items! rt (into (vec unpersisted) new-items))
              (recur (inc turn)
                     messages
                     result-messages
                     (:provider-state response)
                     [])))

          (fail! :model :response "Unsupported model response type"
                 {:response response}))))))

;; ---------------------------------------------------------------------------
;; Agent execution and composition
;; ---------------------------------------------------------------------------

(defn- run-agent!
  [parent-rt agent input options]
  (when-not (agent? agent)
    (fail! :contract :agent "run! requires an Agent" {:value agent}))
  (let [options (or options {})]
    (reject-unknown! "Nested Agent options" agent-call-option-keys options))
  (let [depth (inc (:depth parent-rt))
        limits (validate-limits!
                (narrower-limits (:limits parent-rt)
                                 (merge (get-in agent [:config :limits])
                                        (:limits options))))]
    (when (> depth (:agent-depth limits))
      (fail! :budget :agent-depth "Agent depth limit was reached"
             {:depth depth :limit (:agent-depth limits)}))
    (let [local-deadline-ns
          (when-let [ms (:deadline-ms limits)]
            (+ (System/nanoTime) (* 1000000 (long ms))))
          deadline-ns
          (let [parent-deadline (:deadline-ns parent-rt)]
            (cond
              (nil? parent-deadline) local-deadline-ns
              (nil? local-deadline-ns) parent-deadline
              :else (min parent-deadline local-deadline-ns)))
          active-session (when (zero? depth) (:root-session parent-rt))
          rt (assoc parent-rt
                    :agent-id (id "agent_")
                    :parent-id (:agent-id parent-rt)
                    :depth depth
                    :agent agent
                    :context (if (contains? options :context)
                               (:context options)
                               (:context parent-rt))
                    :session active-session
                    :pending-session-items (atom nil)
                    :limits limits
                    :deadline-ns deadline-ns)
          config (:config agent)
          started (System/nanoTime)]
      (check-contract! :context (:context config) (:context rt))
      (check-contract! :agent-input (:input config) input)
      (run-guardrails! rt :agent-input
                       (get-in config [:guardrails :input]) input)
      (emit! rt {:type :agent/started :agent (:name agent) :input input})
      (try
        (let [output (binding [*run-context* rt]
                       (if-let [program (:body agent)]
                         (program rt input)
                         (default-loop! rt agent input)))
              output (maybe-json-output output (:output config))]
          (check-run! rt)
          (check-contract! :agent-output (:output config) output)
          (run-guardrails! rt :agent-output
                           (get-in config [:guardrails :output]) output)
          (when-let [items @(:pending-session-items rt)]
            (append-session-items! rt items))
          (emit! rt {:type :agent/completed :agent (:name agent)
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :output output})
          output)
        (catch Throwable t
          (emit! rt {:type :agent/failed :agent (:name agent)
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :error (throwable->failure t)})
          (if (= :failure (:karcarthy/type (ex-data t)))
            (throw t)
            (fail! :program :execute (or (ex-message t) (str t))
                   {:agent (:name agent)} t)))))))

(defn run!
  "Start a root Run, call an Agent, and return the complete Run value."
  ([agent input]
   (run! agent input {}))
  ([agent input options]
   (let [options (or options {})
         _ (reject-unknown! "Run options" run-option-keys options)
         _ (when (and (some? (:session options))
                      (not (session/session? (:session options))))
             (fail! :contract :session
                    "Run :session must implement karcarthy.session/Session"
                    {:value (:session options)}))
         limits (validate-limits! (merge default-limits (:limits options)))
         executor (Executors/newVirtualThreadPerTaskExecutor)
         events* (atom [])
         usage* (atom {:model-calls 0
                       :input-tokens 0
                       :output-tokens 0
                       :generated-forms 0})
         run-id (id "run_")
         started (System/nanoTime)
         deadline-ns (when-let [ms (:deadline-ms limits)]
                       (+ started (* 1000000 (long ms))))
         rt {:karcarthy/type :run-context
             :run-id run-id
             :agent-id nil
             :parent-id nil
             :depth -1
             :agent nil
             :context (:context options)
             :limits limits
             :usage usage*
             :events events*
             :root-session (:session options)
             :observe (:observe options)
             :approval (:approval options)
             :approvals (atom #{})
             :cancel (:cancel options)
             :metadata (:metadata options)
             :model-transports (:model-transports options)
             :executor executor
             :semaphore (Semaphore. (int (:parallelism limits)) true)
             :deadline-ns deadline-ns
             :evaluation-parent-namespace
             (or (:evaluation-namespace options)
                 (:definition-ns agent))
             :evaluation-namespace
             (symbol
              (str (or (:evaluation-namespace options) 'karcarthy.generated)
                   ".run_" (str/replace run-id "-" "_")))
             :evaluation-counter (atom 0)}]
     (emit! rt {:type :run/started :agent (:name agent) :input input})
     (try
       (let [output (run-agent! rt agent input {})
             result {:karcarthy/type :run
                     :id run-id
                     :status :completed
                     :agent (:name agent)
                     :input input
                     :output output
                     :usage (assoc @usage*
                                   :duration-ms
                                   (/ (double (- (System/nanoTime) started)) 1000000.0))
                     :events @events*
                     :error nil}]
         (emit! rt {:type :run/completed :agent (:name agent)
                    :output output :usage @usage*})
         (assoc result :events @events*))
       (catch Throwable t
         (let [cancelled? (= :cancellation (:kind (throwable->failure t)))
               result {:karcarthy/type :run
                       :id run-id
                       :status (if cancelled? :cancelled :failed)
                       :agent (:name agent)
                       :input input
                       :output nil
                       :usage (assoc @usage*
                                     :duration-ms
                                     (/ (double (- (System/nanoTime) started)) 1000000.0))
                       :error (throwable->failure t)}]
           (emit! rt {:type (if cancelled? :run/cancelled :run/failed)
                      :agent (:name agent) :error (:error result)})
           (assoc result :events @events*)))
       (finally
         (.close executor))))))
