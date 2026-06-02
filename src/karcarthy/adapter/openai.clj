(ns karcarthy.adapter.openai
  "Adapter for the OpenAI Agents SDK
  (https://github.com/openai/openai-agents-python).

  The Agents SDK is Python, so this shells out to a small Python script
  (`resources/karcarthy/openai_runner.py`) that builds an `agents.Agent` and
  calls the SDK runtime. karcarthy sends a JSON request on stdin and reads a
  JSON result on stdout, so the orchestration layer is identical whether a
  workflow runs over Claude or OpenAI - just swap the adapter.

  Requirements: a `python3` with `openai-agents` installed and OPENAI_API_KEY in
  the environment."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn openai-request
  "Pure: build the JSON request map sent to the Python bridge. `opts` :model
  overrides the agent's :model."
  [agent prompt opts]
  (cond-> {:name         (:name agent)
           :instructions (:instructions agent)
           :input        prompt}
    (or (:model opts) (:model agent)) (assoc :model (or (:model opts) (:model agent)))))

(defn parse-openai-result
  "Parse the Python script's JSON stdout into a karcarthy result map."
  [agent-name stdout]
  (let [m (json/read-str stdout :key-fn keyword)]
    (k/result {:agent agent-name
               :ok?   (boolean (:ok m))
               :text  (:text m)
               :error (:error m)
               :raw   m})))

;; The bundled Python script is copied to a temp file once, so it works whether karcarthy
;; runs from source or from a jar (where the resource isn't a real file).
(def ^:private bridge-file
  (delay
    (let [tmp (java.io.File/createTempFile "karcarthy_openai_runner" ".py")]
      (.deleteOnExit tmp)
      (with-open [in (io/input-stream (io/resource "karcarthy/openai_runner.py"))]
        (io/copy in tmp))
      (.getPath tmp))))

(defn openai-agents-sdk
  "Adapter that drives the OpenAI Agents SDK via the Python script.
  `default-opts` are merged beneath per-run opts. Options:
    :python-bin  python executable (default \"python3\")
    :script      path to the Python script (default: the bundled resource)
    :model       default model for agents that don't set one
    :dir / :env  working directory / extra environment for the process
    :timeout-ms  kill the subprocess if it runs longer than this (milliseconds)"
  ([] (openai-agents-sdk {}))
  ([default-opts]
   (reify k/Adapter
     (-run [_ agent prompt opts]
       (let [opts   (merge default-opts opts)
             python (get opts :python-bin "python3")
             script (or (:script opts) @bridge-file)
             req    (json/write-str (openai-request agent prompt opts))
             {:keys [exit out err timed-out?]}
             (proc/run [python script] {:in         req
                                        :dir        (:dir opts)
                                        :env        (:env opts)
                                        :timeout-ms (:timeout-ms opts)})]
         (cond
           timed-out?
           (k/result {:agent (:name agent) :ok? false :text nil
                      :error "openai adapter timed out"
                      :raw   {:timed-out? true :err err}})

           (seq (str/trim (or out "")))
           (try
             (parse-openai-result (:name agent) out)
             (catch Exception e
               (k/result {:agent (:name agent) :ok? false
                          :text  (or (not-empty err) out)
                          :error (str "could not parse adapter JSON: " (.getMessage e))
                          :raw   {:exit exit :out out :err err}})))

           :else
           (k/result {:agent (:name agent) :ok? false
                      :text  (or (not-empty err) out)
                      :error (str "adapter exited with status " exit)
                      :raw   {:exit exit :out out :err err}})))))))
