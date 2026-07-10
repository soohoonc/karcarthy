(ns karcarthy.openai-test
  (:require [clojure.test :refer [deftest is]]
            [karcarthy.model.openai :as openai]))

(deftest lowers-normalized-request
  (let [body (openai/request
              {:model {:id "gpt-5.6"
                       :reasoning {:effort :high
                                   :context :all-turns}
                       :verbosity :low
                       :parallel-tool-calls false
                       :temperature 0.2}
               :instructions "help"
               :input [{:role :user :content {:question "hi"}}]
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
  (let [body (openai/request
              {:model {:id "gpt-5.6"}
               :instructions "help"
               :state {:previous-response-id "resp_1"}
               :input [{:role :tool
                        :tool-call-id "call_1"
                        :content {:value 3}}]
               :tools []})]
    (is (= "resp_1" (:previous_response_id body)))
    (is (= "function_call_output" (get-in body [:input 0 :type])))
    (is (= "call_1" (get-in body [:input 0 :call_id])))
    (is (= "{\"value\":3}" (get-in body [:input 0 :output])))))

(deftest lowers-hosted-tools-and-replayed-tool-call-history
  (let [body (openai/request
              {:model {:id "gpt-5.6"}
               :instructions "help"
               :input [{:role :assistant
                        :tool-calls [{:id "call_1"
                                      :name "lookup"
                                      :input {:id "a"}}]}
                       {:role :tool
                        :tool-call-id "call_1"
                        :content {:value 3}}]
               :tools [{:kind :hosted
                        :provider :openai
                        :spec {:type "web_search"
                               :search_context_size "low"}}]})]
    (is (= {:type "web_search" :search_context_size "low"}
           (first (:tools body))))
    (is (= "function_call" (get-in body [:input 0 :type])))
    (is (= "lookup" (get-in body [:input 0 :name])))
    (is (= "function_call_output" (get-in body [:input 1 :type])))))

(deftest primitive-output-contracts-are-validated-locally
  (let [body (openai/request
              {:model {:id "gpt-5.6"}
               :instructions "Return text."
               :input [{:role :user :content "hello"}]
               :tools []
               :output-schema {:type "string"}})]
    (is (nil? (:text body)))))

(deftest normalizes-function-calls
  (let [response (openai/response
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
  (let [response (openai/response
                  {:id "resp_2"
                   :output [{:type "message"
                             :content [{:type "output_text" :text "hello"}
                                       {:type "output_text" :text " world"}]}]
                   :usage {}})]
    (is (= :final (:type response)))
    (is (= "hello world" (:output response)))))

(deftest response-errors-fail
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad request"
                        (openai/response
                         {:error {:message "bad request"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid function-call"
                        (openai/response
                         {:id "r"
                          :output [{:type "function_call"
                                    :call_id "c"
                                    :name "f"
                                    :arguments "{"}]}))))
