(ns karcarthy.runner.openai
  "Runner for the OpenAI Agents SDK
  (https://github.com/openai/openai-agents-python).

  The Agents SDK is Python, so this shells out to a small Python script
  (`resources/karcarthy/openai_runner.py`) that builds an `agents.Agent` and
  calls the SDK runtime. karcarthy sends a JSON request on stdin and reads a
  JSON result on stdout, so the orchestration layer is identical whether a
  workflow runs over Claude or OpenAI - just swap the runner.

  Requirements: a `python3` with `openai-agents` installed and OPENAI_API_KEY in
  the environment."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn subagent-request
  "Lower a karcarthy subagent to the JSON shape consumed by the Python bridge."
  [subagent]
  (when-not (k/subagent? subagent)
    (throw (ex-info "invalid OpenAI subagent" {:subagent subagent})))
  (cond-> {:name                (:name subagent)
           :instructions        (:instructions subagent)
           :handoff_description (:description subagent)}
    (:model subagent) (assoc :model (:model subagent))))

(defn request
  "Pure: build the JSON request map sent to the Python bridge. `opts` :model
  overrides the agent's :model."
  [agent prompt opts]
  (cond-> {:name         (:name agent)
           :instructions (:instructions agent)
           :input        prompt}
    (or (:model opts) (:model agent)) (assoc :model (or (:model opts) (:model agent)))
    (seq (:subagents opts)) (assoc :subagents (mapv subagent-request (:subagents opts)))))

(defn stdout->result
  "Parse the Python script's JSON stdout into a karcarthy result map."
  [agent-name stdout]
  (let [m (json/read-str stdout :key-fn keyword)]
    (k/result (cond-> {:agent agent-name
                       :ok?   (true? (:ok m))
                       :text  (:text m)
                       :error (:error m)
                       :raw   m}
                (not (boolean? (:ok m)))
                (assoc :error "OpenAI runner result is missing Boolean ok")))))

;; The bundled Python script is copied to a temp file once, so it works whether karcarthy
;; runs from source or from a jar (where the resource isn't a real file).
(def ^:private bridge-file
  (delay
    (let [tmp (java.io.File/createTempFile "karcarthy_openai_runner" ".py")]
      (.deleteOnExit tmp)
      (with-open [in (io/input-stream (io/resource "karcarthy/openai_runner.py"))]
        (io/copy in tmp))
      (.getPath tmp))))

(defn openai-runner
  "Runner for OpenAI. `default-options` are merged beneath per-run options.
  This implementation drives the OpenAI Agents SDK via the Python bridge.
  Options:
    :python-bin  python executable (default \"python3\")
    :script      path to the Python script (default: the bundled resource)
    :model       default model for agents that don't set one
    :dir / :env  working directory / extra environment for the process
    :timeout-ms  kill the subprocess if it runs longer than this (milliseconds)"
  ([] (openai-runner {}))
  ([default-options]
   (reify k/Runner
     (-run [_ agent prompt opts]
       (k/reject-tools! :openai agent)
       (let [opts   (merge default-options opts)
             python (get opts :python-bin "python3")
             script (or (:script opts) @bridge-file)
             req    (json/write-str (request agent prompt opts))
             {:keys [exit out err timed-out?]}
             (proc/run [python script] {:in         req
                                        :dir        (:dir opts)
                                        :env        (:env opts)
                                        :timeout-ms (:timeout-ms opts)})
             raw    {:runner :openai :exit exit :out out :err err
                     :timed-out? timed-out? :argv [python script]}]
         (cond
           timed-out?
           (k/result {:agent (:name agent) :ok? false :text nil
                      :error "openai runner timed out"
                      :raw   raw})

           (seq (str/trim (or out "")))
           (try
             (let [parsed (stdout->result (:name agent) out)]
               (if (zero? exit)
                 parsed
                 (k/result (assoc parsed
                                  :ok? false
                                  :error (str "runner exited with status " exit)
                                  :raw {:payload (:raw parsed) :process raw}))))
             (catch Exception e
               (k/result {:agent (:name agent) :ok? false
                          :text  (or (not-empty err) out)
                          :error (str "could not parse runner JSON: " (.getMessage e))
                          :raw   raw})))

           :else
           (k/result {:agent (:name agent) :ok? false
                      :text  (or (not-empty err) out)
                      :error (str "runner exited with status " exit)
                      :raw   raw})))))))
