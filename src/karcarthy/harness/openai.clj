(ns karcarthy.harness.openai
  "Harness adapter for the OpenAI Agents SDK
  (https://github.com/openai/openai-agents-python).

  The Agents SDK is Python, so - like the claude harness uses an existing CLI -
  this drives an existing harness by shelling out to a small Python runner
  (`resources/karcarthy/openai_runner.py`) that builds an `agents.Agent` and
  calls `Runner.run_sync`. karcarthy sends a JSON request on stdin and reads a
  JSON result on stdout, so the orchestration layer is identical whether a flow
  runs over Claude or OpenAI - just swap the harness.

  Requirements: a `python3` with `openai-agents` installed and OPENAI_API_KEY in
  the environment."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn openai-request
  "Pure: build the JSON request map sent to the Python runner. `opts` :model
  overrides the agent's :model."
  [agent prompt opts]
  (cond-> {:name         (:name agent)
           :instructions (:instructions agent)
           :input        prompt}
    (or (:model opts) (:model agent)) (assoc :model (or (:model opts) (:model agent)))))

(defn parse-openai-result
  "Parse the runner's JSON stdout into a karcarthy result map."
  [agent-name stdout]
  (let [m (json/read-str stdout :key-fn keyword)]
    (k/result {:agent agent-name
               :ok?   (boolean (:ok m))
               :text  (:text m)
               :error (:error m)
               :raw   m})))

;; The bundled runner copied to a temp file once, so it works whether karcarthy
;; runs from source or from a jar (where the resource isn't a real file).
(def ^:private runner-file
  (delay
    (let [tmp (java.io.File/createTempFile "karcarthy_openai_runner" ".py")]
      (.deleteOnExit tmp)
      (with-open [in (io/input-stream (io/resource "karcarthy/openai_runner.py"))]
        (io/copy in tmp))
      (.getPath tmp))))

(defn openai-agents-harness
  "A `karcarthy.core/Harness` that drives the OpenAI Agents SDK via the Python
  runner. `default-opts` are merged beneath per-run opts. Options:
    :python-bin  python executable (default \"python3\")
    :runner      path to the runner script (default: the bundled resource)
    :model       default model for agents that don't set one
    :dir / :env  working directory / extra environment for the process
    :timeout-ms  kill the runner if it runs longer than this (milliseconds)"
  ([] (openai-agents-harness {}))
  ([default-opts]
   (reify k/Harness
     (-run [_ agent prompt opts]
       (let [opts   (merge default-opts opts)
             python (get opts :python-bin "python3")
             runner (or (:runner opts) @runner-file)
             req    (json/write-str (openai-request agent prompt opts))
             {:keys [exit out err timed-out?]}
             (proc/run [python runner] {:in         req
                                        :dir        (:dir opts)
                                        :env        (:env opts)
                                        :timeout-ms (:timeout-ms opts)})]
         (cond
           timed-out?
           (k/result {:agent (:name agent) :ok? false :text nil
                      :error "openai runner timed out"
                      :raw   {:timed-out? true :err err}})

           (seq (str/trim (or out "")))
           (try
             (parse-openai-result (:name agent) out)
             (catch Exception e
               (k/result {:agent (:name agent) :ok? false
                          :text  (or (not-empty err) out)
                          :error (str "could not parse runner JSON: " (.getMessage e))
                          :raw   {:exit exit :out out :err err}})))

           :else
           (k/result {:agent (:name agent) :ok? false
                      :text  (or (not-empty err) out)
                      :error (str "runner exited with status " exit)
                      :raw   {:exit exit :out out :err err}})))))))
