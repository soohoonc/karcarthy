(ns karcarthy.mcp-fixture
  (:require [clojure.data.json :as json]))

(defn- reply! [id result]
  (println (json/write-str {:jsonrpc "2.0" :id id :result result}))
  (flush))

(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader.
                          (java.io.InputStreamReader. System/in)))]
    (let [message (json/read-str line :key-fn keyword)]
      (case (:method message)
        "initialize"
        (reply! (:id message)
                {:protocolVersion "2025-11-25"
                 :capabilities {:tools {}}
                 :serverInfo {:name "fixture" :version "1"}})

        "tools/list"
        (reply! (:id message)
                {:tools
                 [{:name "echo"
                   :description "Echo one text value."
                   :inputSchema
                   {:type "object"
                    :properties {"text" {:type "string"}}
                    :required ["text"]
                    :additionalProperties false}}]})

        "tools/call"
        (let [text (get-in message [:params :arguments :text])]
          (reply! (:id message)
                  {:content [{:type "text" :text text}]
                   :structuredContent {:echo text}
                   :isError false}))
        nil))))
