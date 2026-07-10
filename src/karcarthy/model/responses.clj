(ns karcarthy.model.responses
  "Responses-compatible HTTP transport. It only translates model I/O; the
  karcarthy core owns the loop and executes function tools locally."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [karcarthy.core :as core])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 30))
             (.build))))

(defn- json-value [value]
  (if (string? value) value (json/write-str value)))

(defn- input-items [message]
  (case (:role message)
    :tool [{:type "function_call_output"
            :call_id (:tool-call-id message)
            :output (json-value (:content message))}]
    :user [{:role "user" :content (json-value (:content message))}]
    :assistant
    (if (seq (:tool-calls message))
      (mapv (fn [{:keys [id name input]}]
              {:type "function_call"
               :call_id id
               :name name
               :arguments (json-value input)})
            (:tool-calls message))
      [{:role "assistant" :content (json-value (:content message))}])
    :system [{:role "system" :content (json-value (:content message))}]
    [{:role (name (:role message)) :content (json-value (:content message))}]))

(defn- function-tool [{:keys [name description parameters]}]
  {:type "function"
   :name name
   :description description
   :parameters parameters
   :strict false})

(defn- request-tool [tool]
  (case (:kind tool)
    :hosted
    (if (= :responses (:transport tool))
      (:spec tool)
      (core/fail! :model :configuration
                  "Hosted Tool belongs to a different transport"
                  {:tool-transport (:transport tool)
                   :model-transport :responses}))
    :function (function-tool tool)
    (core/fail! :model :configuration
                "Responses transport received an unsupported normalized Tool"
                {:tool tool})))

(defn web-search
  "Create a Responses server-executed web-search capability. The configured
  endpoint and selected model must support this hosted Tool."
  ([] (web-search {}))
  ([options]
   (core/hosted-tool :responses (merge {:type "web_search"} options))))

(defn- api-name [x]
  (str/replace (name x) "-" "_"))

(defn- api-value [value]
  (cond
    (keyword? value) (api-name value)
    (map? value) (into {} (map (fn [[k v]] [(keyword (api-name k))
                                             (api-value v)])) value)
    (vector? value) (mapv api-value value)
    :else value))

(defn- reasoning-config [reasoning]
  (cond
    (nil? reasoning) nil
    (map? reasoning) (api-value reasoning)
    :else {:effort (api-value reasoning)}))

(defn- object-schema? [schema]
  (contains? #{"object" :object}
             (or (:type schema) (get schema "type"))))

(defn request
  "Pure: lower a normalized karcarthy model request to a Responses API body."
  [{:keys [model instructions input state tools output-schema]}]
  (let [text-config
        (cond-> {}
          (:verbosity model)
          (assoc :verbosity (api-value (:verbosity model)))

          (object-schema? output-schema)
          (assoc :format {:type "json_schema"
                          :name "agent_output"
                          :schema output-schema
                          :strict true}))
        body {:model (:id model)
              :instructions instructions
              :input (vec (mapcat input-items input))
              :tools (mapv request-tool tools)
              :tool_choice (or (:tool-choice model) "auto")
              :parallel_tool_calls
              (if (contains? model :parallel-tool-calls)
                (boolean (:parallel-tool-calls model))
                true)
              :store (if (contains? model :store) (boolean (:store model)) true)}
        body (cond-> body
               (get state :previous-response-id)
               (assoc :previous_response_id (get state :previous-response-id))

               (:reasoning model)
               (assoc :reasoning (reasoning-config (:reasoning model)))

               (:temperature model)
               (assoc :temperature (:temperature model))

               (:top-p model)
               (assoc :top_p (:top-p model))

               (:max-output-tokens model)
               (assoc :max_output_tokens (:max-output-tokens model))

               (seq text-config)
               (assoc :text text-config))]
    (merge body (:provider-options model))))

(defn- parse-arguments [arguments]
  (try
    (json/read-str (or arguments "{}") :key-fn keyword)
    (catch Throwable t
      (core/fail! :model :response
                  "Responses endpoint returned invalid function-call arguments"
                  {:arguments arguments} t))))

(defn- output-text [output]
  (->> output
       (filter #(= "message" (:type %)))
       (mapcat :content)
       (filter #(= "output_text" (:type %)))
       (map :text)
       (apply str)))

(defn response
  "Pure: normalize a Responses API payload for the karcarthy loop."
  [payload]
  (when-let [error (:error payload)]
    (core/fail! :model :response
                (or (:message error) "Responses request failed")
                {:error error}))
  (when (and (:status payload) (not= "completed" (:status payload)))
    (core/fail! :model :response
                (str "Responses request ended with status " (:status payload))
                {:status (:status payload)
                 :incomplete-details (:incomplete_details payload)}))
  (let [calls (->> (:output payload)
                   (filter #(= "function_call" (:type %)))
                   (mapv (fn [item]
                           {:id (:call_id item)
                            :name (:name item)
                            :input (parse-arguments (:arguments item))})))
        usage {:input-tokens (get-in payload [:usage :input_tokens] 0)
               :output-tokens (get-in payload [:usage :output_tokens] 0)
               :total-tokens (get-in payload [:usage :total_tokens] 0)}
        state {:previous-response-id (:id payload)}]
    (if (seq calls)
      {:type :tool-calls
       :calls calls
       :state state
       :usage usage
       :raw payload}
      {:type :final
       :output (output-text (:output payload))
       :state state
       :usage usage
       :raw payload})))

(defn- request-url [model]
  (if-let [url (:url model)]
    (str url)
    (let [base-url (str/replace
                    (or (:base-url model) "https://api.openai.com/v1")
                    #"/$" "")
          path (or (:path model) "/responses")]
      (str base-url (if (str/starts-with? path "/") path (str "/" path))))))

(defn- configured-headers [model]
  (into {} (map (fn [[name value]] [(str name) (str value)]))
        (:headers model)))

(defn- authorization-header? [headers]
  (some #(= "authorization" (str/lower-case %)) (keys headers)))

(defn- api-key [model]
  (or (:api-key model)
      (when-let [name (:api-key-env model)] (System/getenv (str name)))
      (System/getenv "RESPONSES_API_KEY")
      (System/getenv "OPENAI_API_KEY")))

(defn complete!
  "Execute one Responses-compatible HTTP request.

  `:base-url` defaults to OpenAI. Gateways may set `:base-url`, `:api-key` or
  `:api-key-env`, and `:headers`. Set `:auth? false` for a trusted endpoint
  that requires no Authorization header. `:url` overrides the complete URL."
  [normalized-request]
  (let [model (:model normalized-request)
        headers (configured-headers model)
        key (api-key model)
        default-auth? (and (not= false (:auth? model))
                           (not (authorization-header? headers)))
        timeout-ms (long (or (:timeout-ms model) 120000))]
    (when (and default-auth? (str/blank? key))
      (core/fail! :model :configuration
                  "Responses API key is not configured"
                  {:transport :responses
                   :api-key-env (:api-key-env model)}))
    (when (str/blank? (:id model))
      (core/fail! :model :configuration
                  "Responses model configuration requires :id"
                  {:model model}))
    (let [body (json/write-str (request normalized-request))
          builder (doto (HttpRequest/newBuilder)
                    (.uri (URI/create (request-url model)))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body)))
          _ (when default-auth?
              (.header builder "Authorization" (str "Bearer " key)))
          _ (doseq [[name value] headers]
              (.setHeader builder name value))
          http-request (.build builder)
          http-response (.send ^HttpClient @http-client http-request
                               (HttpResponse$BodyHandlers/ofString))
          status (.statusCode http-response)
          request-id (some-> (.firstValue (.headers http-response)
                                          "x-request-id")
                             (.orElse nil))
          response-body (.body http-response)
          payload (try
                    (json/read-str response-body :key-fn keyword)
                    (catch Throwable t
                      (core/fail! :model :response
                                  "Responses endpoint returned non-JSON content"
                                  {:status status :body response-body} t)))]
      (when-not (<= 200 status 299)
                    (core/fail! :model :request
                    (or (get-in payload [:error :message])
                        (str "Responses request failed with HTTP " status))
                    {:status status
                     :request-id request-id
                     :response payload}))
      (response payload))))
