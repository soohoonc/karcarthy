(ns karcarthy.responses-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [karcarthy.model.responses :as responses])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(defn- sse-body [& events]
  (apply str (map #(str "data: " (json/write-str %) "\n\n") events)))

(deftest lowers-normalized-request
  (let [body (responses/request
              {:model {:id "gpt-5.6"
                       :reasoning {:effort :high
                                   :context :all-turns}
                       :verbosity :low
                       :parallel-tool-calls false
                       :temperature 0.2}
               :context {:system "help"
                         :messages [{:role :user :content {:question "hi"}}]}
               :tools [{:kind :function
                        :name "lookup"
                        :description "Look up a value."
                        :parameters {:type "object"
                                     :properties {"id" {:type "string"}}}}]
               :output-schema {:type "object"
                               :properties {"answer" {:type "string"}}}})]
    (is (= "gpt-5.6" (:model body)))
    (is (= {:effort "high" :context "all_turns"} (:reasoning body)))
    (is (= "low" (get-in body [:text :verbosity])))
    (is (false? (:parallel_tool_calls body)))
    (is (= 0.2 (:temperature body)))
    (is (= "function" (get-in body [:tools 0 :type])))
    (is (= "user" (get-in body [:input 0 :role])))
    (is (= "json_schema" (get-in body [:text :format :type])))))

(deftest lowers-function-results-with-response-state
  (let [body (responses/request
              {:model {:id "gpt-5.6"}
               :state {:previous-response-id "resp_1"}
               :context {:system "help"
                         :messages [{:role :tool
                                    :tool-call-id "call_1"
                                    :content {:value 3}}]}
               :tools []})]
    (is (= "resp_1" (:previous_response_id body)))
    (is (= "function_call_output" (get-in body [:input 0 :type])))
    (is (= "call_1" (get-in body [:input 0 :call_id])))
    (is (= "{\"value\":3}" (get-in body [:input 0 :output])))))

(deftest lowers-hosted-tools-and-replayed-tool-call-history
  (let [body (responses/request
              {:model {:id "gpt-5.6"}
               :context
               {:system "help"
                :messages [{:role :assistant
                            :tool-calls [{:id "call_1"
                                          :name "lookup"
                                          :input {:id "a"}}]}
                        {:role :tool
                         :tool-call-id "call_1"
                         :content {:value 3}}]}
               :tools [{:kind :hosted
                        :transport :responses
                        :spec {:type "web_search"
                               :search_context_size "low"}}]})]
    (is (= {:type "web_search" :search_context_size "low"}
           (first (:tools body))))
    (is (= "function_call" (get-in body [:input 0 :type])))
    (is (= "lookup" (get-in body [:input 0 :name])))
    (is (= "function_call_output" (get-in body [:input 1 :type])))))

(deftest rejects-hosted-tools-owned-by-another-transport
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"different transport"
       (responses/request
        {:model {:id "model"}
         :context {:system "help"
                   :messages [{:role :user :content "hello"}]}
         :tools [{:kind :hosted
                  :transport :another-transport
                  :spec {:type "special"}}]}))))

(deftest primitive-output-contracts-are-validated-locally
  (let [body (responses/request
              {:model {:id "gpt-5.6"}
               :context {:system "Return text."
                         :messages [{:role :user :content "hello"}]}
               :tools []
               :output-schema {:type "string"}})]
    (is (nil? (:text body)))))

(deftest normalizes-function-calls
  (let [response (responses/response
                  {:id "resp_1"
                   :output [{:type "function_call"
                             :call_id "call_1"
                             :name "lookup"
                             :arguments "{\"id\":\"a\"}"}]
                   :usage {:input_tokens 5
                           :output_tokens 2
                           :total_tokens 7}})]
    (is (= :tool-calls (:type response)))
    (is (= [{:id "call_1" :name "lookup" :input {:id "a"}}]
           (:calls response)))
    (is (= {:previous-response-id "resp_1"} (:state response)))
    (is (= 5 (get-in response [:usage :input-tokens])))))

(deftest normalizes-final-text
  (let [response (responses/response
                  {:id "resp_2"
                   :output [{:type "message"
                             :content [{:type "output_text" :text "hello"}
                                       {:type "output_text" :text " world"}]}]
                   :usage {}})]
    (is (= :final (:type response)))
    (is (= "hello world" (:output response)))))

(deftest response-errors-fail
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad request"
                        (responses/response
                         {:error {:message "bad request"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid function-call"
                        (responses/response
                         {:id "r"
                          :output [{:type "function_call"
                                    :call_id "c"
                                    :name "f"
                                    :arguments "{"}]}))))

(deftest incomplete-responses-fail-closed
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"status incomplete"
       (responses/response
        {:id "resp_incomplete"
         :status "incomplete"
         :incomplete_details {:reason "max_output_tokens"}
         :output []}))))

(deftest calls-a-configured-responses-compatible-endpoint
  (let [seen (promise)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        response-bytes
        (.getBytes
         (json/write-str
          {:id "resp_gateway"
           :status "completed"
           :output [{:type "message"
                     :content [{:type "output_text" :text "gateway ok"}]}]
           :usage {:input_tokens 3 :output_tokens 2 :total_tokens 5}})
         StandardCharsets/UTF_8)]
    (.createContext
     server "/v1/responses"
     (reify HttpHandler
       (handle [_ exchange]
         (with-open [input (.getRequestBody exchange)]
           (deliver seen
                    {:method (.getRequestMethod exchange)
                     :path (str (.getPath (.getRequestURI exchange)))
                     :authorization
                     (.getFirst (.getRequestHeaders exchange) "Authorization")
                     :gateway-header
                     (.getFirst (.getRequestHeaders exchange) "X-Gateway")
                     :body (json/read-str (slurp input) :key-fn keyword)}))
         (.add (.getResponseHeaders exchange)
               "Content-Type" "application/json")
         (.sendResponseHeaders exchange 200 (alength response-bytes))
         (with-open [output (.getResponseBody exchange)]
           (.write output response-bytes)))))
    (.start server)
    (try
      (let [port (.getPort (.getAddress server))
            result
            (responses/complete!
             {:model {:transport :responses
                      :provider :anthropic
                      :id "anthropic/claude-test"
                      :base-url (str "http://127.0.0.1:" port "/v1")
                      :api-key "test-key"
                      :headers {"X-Gateway" "karcarthy-test"}}
              :context {:system "Answer briefly."
                        :messages [{:role :user :content "hello"}]}
              :tools []})
            request (deref seen 5000 ::timeout)]
        (is (= :final (:type result)))
        (is (= "gateway ok" (:output result)))
        (is (= "POST" (:method request)))
        (is (= "/v1/responses" (:path request)))
        (is (= "Bearer test-key" (:authorization request)))
        (is (= "karcarthy-test" (:gateway-header request)))
        (is (= "anthropic/claude-test" (get-in request [:body :model]))))
      (finally
        (.stop server 0)))))

(deftest streams-a-responses-compatible-endpoint
  (let [seen (promise)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        response-payload
        {:id "resp_stream"
         :status "completed"
         :output [{:type "message"
                   :content [{:type "output_text" :text "hello"}]}]
         :usage {:input_tokens 2 :output_tokens 1 :total_tokens 3}}
        response-bytes
        (.getBytes
         (sse-body
          {:type "response.created" :response {:id "resp_stream"}}
          {:type "response.output_text.delta" :delta "hel"}
          {:type "response.output_text.delta" :delta "lo"}
          {:type "response.completed" :response response-payload})
         StandardCharsets/UTF_8)]
    (.createContext
     server "/v1/responses"
     (reify HttpHandler
       (handle [_ exchange]
         (with-open [input (.getRequestBody exchange)]
           (deliver seen
                    {:accept (.getFirst (.getRequestHeaders exchange) "Accept")
                     :body (json/read-str (slurp input) :key-fn keyword)}))
         (.add (.getResponseHeaders exchange)
               "Content-Type" "text/event-stream")
         (.sendResponseHeaders exchange 200 (alength response-bytes))
         (with-open [output (.getResponseBody exchange)]
           (.write output response-bytes)))))
    (.start server)
    (try
      (let [port (.getPort (.getAddress server))
            deltas (atom [])
            result
            (responses/stream!
             {:model {:transport :responses
                      :id "stream-model"
                      :base-url (str "http://127.0.0.1:" port "/v1")
                      :auth? false}
              :context {:system "Answer."
                        :messages [{:role :user :content "hello"}]}
              :tools []}
             #(swap! deltas conj %))
            request (deref seen 5000 ::timeout)]
        (is (= :final (:type result)))
        (is (= "hello" (:output result)))
        (is (= [{:type :text-delta :delta "hel"}
                {:type :text-delta :delta "lo"}]
               @deltas))
        (is (= "text/event-stream" (:accept request)))
        (is (true? (get-in request [:body :stream]))))
      (finally
        (.stop server 0)))))
