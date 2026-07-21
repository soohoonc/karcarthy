(ns karcarthy.run.loop
  "The native Tool and model loop."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [karcarthy.agent :refer [agent? normalize-model]]
            [karcarthy.run.context :as context]
            [karcarthy.run.model :as model]
            [karcarthy.schema :as schema
             :refer [fail! throwable->failure]]
            [karcarthy.tool :refer [hosted-tool? make-tool tool?
                                    validate-approval!]]))

(defn- eval-tool [model tools agents]
  ((requiring-resolve 'karcarthy.eval/eval-tool) model tools agents))

(defn- resolve-value [value run-context]
  (if (fn? value)
    (value (context/run-context run-context))
    value))

(def ^:private agent-call-schema
  {:type "object"
   :properties
   {"input" {:description "The complete input value passed to this Agent."}}
   :required ["input"]
   :additionalProperties false})

(defn- object-schema? [schema]
  (contains? #{"object" :object}
             (or (:type schema) (get schema "type"))))

(defn- agent-as-tool
  [run-agent! agent]
  (when-not (agent? agent)
    (fail! :schema :configuration
           "Agent :agents must contain Agent values"
           {:value agent}))
  (let [schema (schema/json-schema (:input-schema agent))
        structured-input? (object-schema? schema)
        input-schema (if structured-input?
                       (:input-schema agent)
                       agent-call-schema)]
    (make-tool
     {:name (:name agent)
      :description
      (str (or (:description agent)
               (str "Ask " (:name agent) " to complete a task."))
           " This Agent starts without the parent conversation and receives "
           (if structured-input?
             "the Tool input object directly."
             "only the value in the `input` field.")
           " Its final output returns as the Tool result.")
      :input-schema input-schema
      :output-schema (:output-schema agent)
      :needs-approval :never}
     nil
     nil
     (fn [ctx input]
       (run-agent! ctx agent
                   (if structured-input? input (:input input))
                   {})))))

;; ---------------------------------------------------------------------------
;; Tool execution and the native model loop
;; ---------------------------------------------------------------------------

(defn- tool-descriptor [tool]
  (cond
    (tool? tool)
    (let [schema (schema/json-schema (:input-schema tool))]
      (when-not schema
        (fail! :tool :configuration
               "Tool :input-schema must be expressible as JSON Schema"
               {:tool (:name tool)
                :input-schema (:input-schema tool)}))
      {:kind :function
       :name (:name tool)
       :description (:description tool)
       :parameters schema})

    (hosted-tool? tool)
    {:kind :hosted
     :transport (:transport tool)
     :spec (:spec tool)}

    :else
    (fail! :tool :configuration "Agent contains an invalid Tool"
           {:value tool})))

(defn- approval-policy
  [policy ctx input]
  (if (fn? policy) (policy (context/run-context ctx) input) policy))

(defn- request-approval!
  [rt tool input policy]
  (context/emit! rt {:type :tool/approval-requested
             :tool (:name tool) :input input :policy policy})
  (let [allowed?
        (if-let [handler (:approval rt)]
          (boolean (handler (assoc (context/run-context rt)
                                   :tool tool
                                   :input input)))
          false)]
    (context/emit! rt {:type :tool/approval-resolved
               :tool (:name tool) :approved? allowed? :policy policy})
    allowed?))

(defn- approved?
  [rt tool input]
  (let [policy (validate-approval!
                (approval-policy (:needs-approval tool :never) rt input))]
    (cond
      (not (contains? #{true :always :once} policy)) true
      (not= :once policy) (request-approval! rt tool input policy)
      :else
      (let [approvals (:approvals rt)
            approval-key [:tool (:name tool)]]
        (locking approvals
          (if (contains? @approvals approval-key)
            (do
              (context/emit! rt {:type :tool/approval-reused
                         :tool (:name tool) :policy policy})
              true)
            (let [allowed? (request-approval! rt tool input policy)]
              (when allowed? (swap! approvals conj approval-key))
              allowed?)))))))

(defn- execute-tool-body! [rt tool input]
  (binding [context/*run* rt]
    ((:execute tool) rt input)))

(defn- run-tool!
  [rt tool call]
  (when-not (tool? tool)
    (fail! :tool :configuration "Agent contains an invalid Tool"
           {:value tool}))
  (let [input (:input call)
        call-id (or (:id call) (context/id "tool_"))]
    (schema/check! :tool-input (:input-schema tool) input)
    (context/run-guardrails! rt :tool-input (:input-guardrails tool) input)
    (when-not (approved? rt tool input)
      (fail! :approval :tool "Tool approval was denied"
             {:tool (:name tool) :input input}))
    (context/emit! rt {:type :tool/started :tool (:name tool)
               :tool-call-id call-id :input input})
    (let [started (System/nanoTime)]
      (try
        (let [output (execute-tool-body! rt tool input)
              _ (schema/check! :tool-output (:output-schema tool) output)
              _ (context/run-guardrails! rt :tool-output
                                         (:output-guardrails tool) output)
              model-output (if-let [project (:to-model-output tool)]
                             (project output)
                             output)
              result {:id call-id
                      :name (:name tool)
                      :output output
                      :model-output model-output}]
          (context/emit! rt {:type :tool/completed :tool (:name tool)
                     :tool-call-id call-id
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :output output})
          result)
        (catch Throwable t
          (let [failure (throwable->failure t)
                recoverable? (contains? #{:execution :mcp :tool}
                                        (:kind failure))
                error (cond-> failure
                        recoverable? (assoc :recoverable? true))]
            (context/emit! rt {:type :tool/failed :tool (:name tool)
                       :tool-call-id call-id
                       :duration-ms (/ (double (- (System/nanoTime) started))
                                      1000000.0)
                       :error error})
            (if recoverable?
              {:id call-id
               :name (:name tool)
               :output nil
               :model-output {:error (:message error)}
               :is-error true
               :error error}
              (throw t))))))))

(defn- structured-schema? [schema]
  (let [schema (schema/json-schema schema)]
    (contains? #{"array" "object" :array :object}
               (or (:type schema) (get schema "type")))))

(defn ^:no-doc output [output schema]
  (if (and (structured-schema? schema) (string? output)
           (re-find #"^\s*[\[{]" output))
    (try (json/read-str output :key-fn keyword)
         (catch Throwable _ output))
    output))

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

(defn- unique-tools! [tools]
  (let [duplicates (->> tools
                        (filter tool?)
                        (map :name)
                        frequencies
                        (keep (fn [[name n]] (when (> n 1) name)))
                        sort
                        vec)]
    (when (seq duplicates)
      (fail! :schema :configuration
             "Agent contains duplicate Tool or Agent names"
             {:names duplicates}))
    tools))

(defn ^:no-doc run!
  [rt agent input run-agent!]
  (let [max-turns (or (:max-turns agent) 20)
        model (normalize-model (resolve-value (:model agent) rt))
        tools-value (:tools agent)
        direct-tools (vec (or (resolve-value tools-value rt) []))
        agents-value (:agents agent)
        available-agents (vec (or (resolve-value agents-value rt) []))
        _ (doseq [tool direct-tools]
            (when-not (or (tool? tool) (hosted-tool? tool))
              (fail! :tool :configuration
                     "Agent :tools must contain Tool values"
                     {:value tool})))
        agent-tools (mapv #(agent-as-tool run-agent! %) available-agents)
        tools (unique-tools!
               (conj (into direct-tools agent-tools)
                     (eval-tool model direct-tools available-agents)))
        tool-map (into {} (comp (filter tool?) (map (juxt :name identity))) tools)
        instructions (instructions! rt (:instructions agent))
        prior (context/session-items! (:session rt))
        user-message {:role :user :content input}
        messages (conj prior user-message)]
    (loop [turn 1
           messages messages
           pending messages
           provider-state nil
           unpersisted [user-message]]
      (context/check-run! rt)
      (when (> turn max-turns)
        (fail! :budget :model-loop "Agent exceeded :max-turns"
               {:agent (:name agent) :max-turns max-turns}))
      (let [request {:agent agent
                     :model model
                     :instructions instructions
                     :messages (if provider-state pending messages)
                     :provider-state provider-state
                     :tools (mapv tool-descriptor tools)
                     :output-schema (schema/json-schema
                                     (:output-schema agent))
                     :turn turn}
            response (model/model! rt request)]
        (case (:type response)
          :final
          (let [output (output (:output response) (:output-schema agent))]
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
                               (context/submit-limited! rt
                                                        #(run-tool! rt tool call))))
                           calls)
                results (context/await-futures! rt jobs)
                assistant-message {:role :assistant :tool-calls calls}
                result-messages
                (mapv (fn [{:keys [id name model-output is-error]}]
                        (cond-> {:role :tool
                                 :tool-call-id id
                                 :name name
                                 :content model-output}
                          is-error (assoc :is-error true)))
                      results)
                new-items (into [assistant-message] result-messages)]
            (context/append-items! rt (into (vec unpersisted) new-items))
            (recur (inc turn)
                   (into messages new-items)
                   result-messages
                   (:provider-state response)
                   []))

          (fail! :model :response "Unsupported model response type"
                 {:response response}))))))
