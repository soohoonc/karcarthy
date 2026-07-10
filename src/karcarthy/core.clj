(ns karcarthy.core
  "The karcarthy kernel: executable Agents and Tools, a native model/tool loop,
  structured child execution, contracts, limits, and observation."
  (:refer-clojure :exclude [agent await run!])
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str])
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
  #{:name :description :model :instructions :context :input :tools :output
    :output-schema :loop :memory :guardrails :limits :hooks :metadata})

(def ^:private tool-config-keys
  #{:name :description :input :input-schema :output :output-schema :approval
    :enabled? :guardrails :timeout-ms :retry :to-model-output :metadata})

(defn- reject-unknown!
  [label supported m]
  (when-let [unknown (seq (remove supported (keys m)))]
    (fail! :contract :configuration
           (str label " contains unknown configuration keys")
           {:unknown (vec unknown) :supported (vec (sort supported))}))
  m)

(defn make-agent
  "Implementation constructor used by `agent` and `defagent`."
  ([config source-form expanded-form program]
   (make-agent config source-form expanded-form program nil))
  ([config source-form expanded-form program definition-ns]
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
            "A model-backed Agent requires :model and :instructions"
            {:name (:name config)}))
   {:karcarthy/type :agent
    :name (:name config)
    :config config
    :definition-ns definition-ns
    :source-form source-form
    :expanded-form expanded-form
    :invoke program}))

(defmacro agent
  "With no arguments, return the model-facing Agent capability. With a config,
  construct an executable Agent. A configured Agent with no body uses the
  native model/tool loop; `[rt input] body ...` supplies a Clojure program."
  ([]
   `(karcarthy.core/agent-capability))
  ([config & body]
   (when (and (seq body) (not (vector? (first body))))
     (throw (IllegalArgumentException.
             "agent body must begin with a binding vector [rt input]")))
   (let [source &form
         program (when (seq body) `(fn ~(first body) ~@(next body)))
         expansion `(karcarthy.core/make-agent ~config '~source nil ~program '~(ns-name *ns*))]
     `(karcarthy.core/make-agent ~config '~source '~expansion ~program '~(ns-name *ns*)))))

(defmacro defagent
  "Define a var containing an Agent. The symbol supplies the default :name."
  [sym config & body]
  (when (and (seq body) (not (vector? (first body))))
    (throw (IllegalArgumentException.
            "defagent body must begin with a binding vector [rt input]")))
  (let [source &form
        program (when (seq body) `(fn ~(first body) ~@(next body)))
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
       (or (nil? (:invoke x)) (fn? (:invoke x)))))

(defn source-form [agent-or-tool]
  (:source-form agent-or-tool))

(defn expanded-form [agent-or-tool]
  (:expanded-form agent-or-tool))

(defn make-tool
  "Implementation constructor used by `tool` and `deftool`."
  [config source-form expanded-form execute]
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
   :source-form source-form
   :expanded-form expanded-form
   :execute execute})

(defmacro tool
  "Construct a contracted Tool backed by Clojure code."
  [config bindings & body]
  (when-not (vector? bindings)
    (throw (IllegalArgumentException.
            "tool requires a binding vector [rt input]")))
  (let [source &form
        execute `(fn ~bindings ~@body)
        expansion `(karcarthy.core/make-tool ~config '~source nil ~execute)]
    `(karcarthy.core/make-tool ~config '~source '~expansion ~execute)))

(defmacro deftool
  "Define a var containing a Tool. The symbol supplies the default :name."
  [sym config bindings & body]
  (when-not (vector? bindings)
    (throw (IllegalArgumentException.
            "deftool requires a binding vector [rt input]")))
  (let [source &form
        execute `(fn ~bindings ~@body)
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
  "Describe a provider-executed tool. The provider adapter lowers `spec` to
  its native API; karcarthy never pretends to execute it locally."
  [provider spec]
  (when-not (keyword? provider)
    (fail! :contract :configuration
           "Hosted Tool provider must be a keyword"
           {:provider provider}))
  (when-not (map? spec)
    (fail! :contract :configuration
           "Hosted Tool spec must be a map"
           {:spec spec}))
  {:karcarthy/type :hosted-tool
   :provider provider
   :spec spec})

(defn hosted-tool? [x]
  (and (map? x)
       (= :hosted-tool (:karcarthy/type x))
       (keyword? (:provider x))
       (map? (:spec x))))

(declare invoke!)

(def ^:private agent-request-schema
  {:type "object"
   :properties
   {"source" {:type "string"
              :description "Exactly one Clojure (agent ...) form."}
    "input" {:description "The input passed to the generated Agent."}}
   :required ["source" "input"]
   :additionalProperties false})

(defn ^:no-doc agent-capability
  "Implementation of zero-arity `(agent)`: a Tool that evaluates and invokes
  another ordinary Agent form in the current run tree."
  []
  (make-tool
   {:name "agent"
    :description
    (str "Define and run an Agent. Provide exactly one Clojure (agent ...) "
         "form as source and the value to pass to it as input.")
    :input agent-request-schema
    :input-schema agent-request-schema
    :output any?
    :approval :never}
   '(agent)
   '(karcarthy.core/agent-capability)
   (fn [rt {:keys [source input]}]
     (let [compile-agent! (requiring-resolve 'karcarthy.eval/compile-agent!)
           child (compile-agent! rt source)]
       (invoke! rt child input)))))

;; ---------------------------------------------------------------------------
;; Runtime, limits, and observation
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
  #{:context :limits :memory :observe :approval :cancel :metadata
    :model-transports :evaluation-namespace})

(def ^:private invocation-option-keys #{:context :limits})

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
  "Return the local dependency-injection value for a Runtime."
  [rt]
  (:context rt))

(defn runtime-view
  "Public, immutable view passed to resolver functions."
  [rt]
  {:runtime rt
   :run-id (:run-id rt)
   :agent-id (:agent-id rt)
   :parent-id (:parent-id rt)
   :depth (:depth rt)
   :context (:context rt)
   :limits (:limits rt)
   :usage @(:usage rt)
   :agent (:agent rt)})

(defn emit!
  "Record one observation event and notify the run observer and Agent hooks."
  [rt event]
  (let [event (merge {:karcarthy/type :event
                      :time-ms (System/currentTimeMillis)
                      :run-id (:run-id rt)}
                     (select-keys rt [:agent-id :parent-id :depth])
                     event)]
    (swap! (:events rt) conj event)
    (doseq [observer (concat (when-let [f (:observe rt)] [f])
                             (get-in rt [:agent :config :hooks]))]
      (try (observer event) (catch Throwable _ nil)))
    event))

(defn events
  "Return recorded events from a Run or Runtime."
  [run-or-runtime]
  (let [v (:events run-or-runtime)]
    (if (instance? clojure.lang.IDeref v) @v (vec v))))

(defn- cancellation-requested? [cancel]
  (cond
    (nil? cancel) false
    (fn? cancel) (boolean (cancel))
    (instance? clojure.lang.IDeref cancel) (boolean @cancel)
    :else (boolean cancel)))

(defn check-runtime!
  [rt]
  (when (cancellation-requested? (:cancel rt))
    (fail! :cancellation :runtime "Run was cancelled"))
  (when-let [deadline (:deadline-ns rt)]
    (when (>= (System/nanoTime) deadline)
      (fail! :deadline :runtime "Run deadline was exceeded")))
  rt)

(defn- limit-value [rt k]
  (get (:limits rt) k Long/MAX_VALUE))

(defn consume!
  "Atomically consume a run-tree resource budget."
  [rt k n]
  (check-runtime! rt)
  (loop []
    (let [before @(:usage rt)
          after (update before k (fnil + 0) n)
          limit (limit-value rt k)]
      (when (and limit (> (get after k) limit))
        (fail! :budget :runtime
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
    (let [result (guard (runtime-view rt) value)]
      (when (or (false? result)
                (and (map? result) (false? (:ok? result))))
        (fail! :guardrail phase
               (str "Guardrail rejected " (name phase))
               {:result result :value value}))))
  value)

;; ---------------------------------------------------------------------------
;; Model transport
;; ---------------------------------------------------------------------------

(defn model-transport
  "Construct a narrow model-I/O transport from a request function."
  [complete]
  (when-not (fn? complete)
    (throw (IllegalArgumentException. "model transport requires a function")))
  {:karcarthy/type :model-transport :complete complete})

(defn fake-model
  "Deterministic in-process model transport for tests and examples."
  [respond]
  (model-transport respond))

(defn- resolve-value [value rt]
  (if (fn? value) (value (runtime-view rt)) value))

(defn- resolve-transport [rt model]
  (let [transport (:transport model)
        provider (:provider model)
        transport (or transport
                      (get (:model-transports rt) provider)
                      (when (= :openai provider)
                        (requiring-resolve 'karcarthy.model.openai/complete!)))]
    (or transport
        (fail! :model :configuration
               "No model transport is configured"
               {:provider provider :model model}))))

(defn- call-transport [transport request]
  (cond
    (and (map? transport) (fn? (:complete transport)))
    ((:complete transport) request)
    (ifn? transport) (transport request)
    :else
    (fail! :model :configuration "Invalid model transport"
           {:transport transport})))

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
  [rt request]
  (check-runtime! rt)
  (consume! rt :model-calls 1)
  (let [model (resolve-value (:model request) rt)
        transport (resolve-transport rt model)
        request (assoc request :model model)
        started (System/nanoTime)]
    (emit! rt {:type :model/requested
               :model (select-keys model [:provider :id])})
    (try
      (let [response (normalize-model-response (call-transport transport request))
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
          (fail! :model :request (or (ex-message t) (str t)) nil t))))))

;; ---------------------------------------------------------------------------
;; Tool execution and the native model loop
;; ---------------------------------------------------------------------------

(defn- default-json-schema []
  {:type "object" :properties {} :additionalProperties true})

(defn- tool-descriptor [model tool]
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
    (if (= (:provider model) (:provider tool))
      {:kind :hosted
       :provider (:provider tool)
       :spec (:spec tool)}
      (fail! :tool :configuration
             "Hosted Tool provider does not match the Agent model"
             {:tool-provider (:provider tool)
              :model-provider (:provider model)}))

    :else
    (fail! :tool :configuration "Agent contains an invalid Tool"
           {:value tool})))

(defn- approval-policy
  [policy rt input]
  (if (fn? policy) (policy (runtime-view rt) input) policy))

(defn- request-approval!
  [rt tool input policy]
  (emit! rt {:type :tool/approval-requested
             :tool (:name tool) :input input :policy policy})
  (let [allowed?
        (if-let [handler (:approval rt)]
          (boolean (handler {:runtime (runtime-view rt)
                             :tool tool
                             :input input}))
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
                    {:value ((:execute tool) rt input)}
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
    (when (and enabled? (not (enabled? (runtime-view rt))))
      (fail! :tool :disabled "Tool is disabled in this Runtime"
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

(defn await!
  "Wait for a structured child/task handle and propagate its failure."
  [^Future child]
  (try
    (.get child)
    (catch ExecutionException e
      (throw (or (.getCause e) e)))))

(defn await-all!
  "Wait for child handles in input order."
  [children]
  (mapv await! children))

(defn- maybe-json-output [output contract]
  (if (and contract (string? output)
           (re-find #"^\s*[\[{]" output))
    (try (json/read-str output :key-fn keyword)
         (catch Throwable _ output))
    output))

(defn- default-loop!
  [rt agent input]
  (let [config (:config agent)
        loop-config (:loop config)
        max-turns (or (:max-turns loop-config) 20)
        tools-value (:tools config)
        tools (vec (or (resolve-value tools-value rt) []))
        tool-map (into {} (comp (filter tool?) (map (juxt :name identity))) tools)
        instructions (resolve-value (:instructions config) rt)
        model (resolve-value (:model config) rt)
        user-message {:role :user :content input}
        memory (let [configured (:memory config)]
                 (if (instance? clojure.lang.IAtom configured)
                   configured
                   (:memory rt)))
        prior (if (instance? clojure.lang.IDeref memory)
                (vec (get @memory (:name agent) []))
                [])]
    (loop [turn 1
           messages (conj prior user-message)
           pending (conj prior user-message)
           provider-state nil]
      (check-runtime! rt)
      (when (> turn max-turns)
        (fail! :budget :model-loop "Agent exceeded :max-turns"
               {:agent (:name agent) :max-turns max-turns}))
      (let [base-request {:agent agent
                          :model model
                          :instructions instructions
                          :messages messages
                          :input pending
                          :state provider-state
                          :tools (mapv #(tool-descriptor model %) tools)
                          :output-schema (or (:output-schema config)
                                             (contract->json-schema
                                              (:output config)))
                          :turn turn}
            prepared (if-let [prepare (:prepare-step loop-config)]
                       (merge base-request (or (prepare (runtime-view rt)
                                                       base-request) {}))
                       base-request)
            response (model! rt prepared)]
        (case (:type response)
          :final
          (let [output (maybe-json-output (:output response) (:output config))]
            (when (instance? clojure.lang.IAtom memory)
              (swap! memory assoc (:name agent)
                     (conj messages {:role :assistant :content output})))
            (when-let [stop? (:stop? loop-config)]
              (when-not (stop? (runtime-view rt)
                               {:response response :messages messages})
                (fail! :model :stop-condition
                       "Model returned final output before the stop condition"
                       {:agent (:name agent)})))
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
                results (await-all! jobs)
                assistant-message {:role :assistant :tool-calls calls}
                result-messages
                (mapv (fn [{:keys [id name model-output]}]
                        {:role :tool
                         :tool-call-id id
                         :name name
                         :content model-output})
                      results)]
            (when (instance? clojure.lang.IAtom memory)
              (swap! memory assoc (:name agent)
                     (into messages (cons assistant-message result-messages))))
            (recur (inc turn)
                   (into messages (cons assistant-message result-messages))
                   result-messages
                   (:state response)))

          (fail! :model :response "Unsupported model response type"
                 {:response response}))))))

;; ---------------------------------------------------------------------------
;; Agent execution and composition
;; ---------------------------------------------------------------------------

(defn- invoke-agent!
  [parent-rt agent input options]
  (when-not (agent? agent)
    (fail! :contract :agent "invoke! requires an Agent" {:value agent}))
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
          rt (assoc parent-rt
                    :agent-id (id "agent_")
                    :parent-id (:agent-id parent-rt)
                    :depth depth
                    :agent agent
                    :context (if (contains? options :context)
                               (:context options)
                               (:context parent-rt))
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
        (let [output (if-let [program (:invoke agent)]
                       (program rt input)
                       (default-loop! rt agent input))
              output (maybe-json-output output (:output config))]
          (check-runtime! rt)
          (check-contract! :agent-output (:output config) output)
          (run-guardrails! rt :agent-output
                           (get-in config [:guardrails :output]) output)
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

(defn invoke!
  "Execute a child Agent in an existing Runtime and return its typed output."
  ([rt agent input]
   (invoke! rt agent input {}))
  ([rt agent input options]
   (check-runtime! rt)
   (let [options (or options {})]
     (reject-unknown! "Invocation options" invocation-option-keys options)
     (invoke-agent! rt agent input options))))

(defn spawn!
  "Start a structured child invocation and return a Future handle."
  ([rt agent input]
   (spawn! rt agent input {}))
  ([rt agent input options]
   (check-runtime! rt)
   (submit-limited! rt #(invoke! rt agent input options))))

(defn handoff!
  "Transfer control to another Agent while retaining Runtime lineage."
  [rt agent input]
  (emit! rt {:type :agent/handoff
             :from (get-in rt [:agent :name])
             :to (:name agent)})
  (invoke! rt agent input))

(defn as-tool
  "Project an Agent as a Tool while the parent remains active."
  ([agent] (as-tool agent {}))
  ([agent options]
   (when-not (agent? agent)
     (fail! :contract :tool "as-tool requires an Agent" {:value agent}))
   (let [agent-config (:config agent)
         config {:name (or (:name options) (:name agent))
                 :description (or (:description options)
                                  (:description agent-config)
                                  (str "Invoke Agent " (:name agent)))
                 :input (or (:input options) (:input agent-config) any?)
                 :output (or (:output options) (:output agent-config))
                 :approval (or (:approval options) :never)}]
     (make-tool config
                `(as-tool ~(source-form agent) ~options)
                nil
                (fn [rt input]
                  (invoke! rt agent input
                           (cond-> {}
                             (:context options)
                             (assoc :context ((:context options)
                                              (context rt) input)))))))))

(defn run!
  "Create a root Runtime, execute an Agent, and return a complete Run record."
  ([agent input]
   (run! agent input {}))
  ([agent input options]
   (let [options (or options {})
         _ (reject-unknown! "Run options" run-option-keys options)
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
         rt {:karcarthy/type :runtime
             :run-id run-id
             :agent-id nil
             :parent-id nil
             :depth -1
             :agent nil
             :context (:context options)
             :limits limits
             :usage usage*
             :events events*
             :memory (or (:memory options) (atom {}))
             :observe (:observe options)
             :approval (:approval options)
             :approvals (atom #{})
             :cancel (:cancel options)
             :metadata (:metadata options)
             :model-transports (:model-transports options)
             :executor executor
             :semaphore (Semaphore. (int (:parallelism limits)) true)
             :deadline-ns deadline-ns
             :evaluation-namespace
             (or (:evaluation-namespace options)
                 (:definition-ns agent)
                 (symbol (str "karcarthy.generated.run_"
                              (str/replace run-id "-" "_"))))}]
     (emit! rt {:type :run/started :agent (:name agent) :input input})
     (try
       (let [output (invoke! rt agent input)
             result {:karcarthy/type :run
                     :id run-id
                     :status :completed
                     :agent (:name agent)
                     :input input
                     :output output
                     :usage (assoc @usage*
                                   :duration-ms
                                   (/ (double (- (System/nanoTime) started)) 1000000.0))
                     :trace-id run-id
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
                       :trace-id run-id
                       :error (throwable->failure t)}]
           (emit! rt {:type (if cancelled? :run/cancelled :run/failed)
                      :agent (:name agent) :error (:error result)})
           (assoc result :events @events*)))
       (finally
         (.close executor))))))
