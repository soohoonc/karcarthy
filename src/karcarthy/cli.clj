(ns karcarthy.cli
  "Command-line entry point and language-agnostic JSON bridge.

      ./bin/karcarthy agent echo --instructions \"Echo the input.\" hi
      ./bin/karcarthy run workflow.json \"what is a monad?\"
      ./bin/karcarthy json < request.json

  A <workflow> is JSON mirroring the EDN workflow:
    {\"type\":\"agent\" \"name\":_ \"instructions\":_ \"model\":?  \"adapter\":?}
    {\"type\":\"pipe\" \"steps\":[<workflow> ...]}
    {\"type\":\"map\" \"branches\":[<workflow> ...]}
    {\"type\":\"map\" \"planner\":<workflow> \"worker\":<workflow>}
    {\"type\":\"reduce\" \"mapped\":<map-workflow> \"reducer\":<workflow>}
    {\"type\":\"bind\" \"source\":<workflow> \"routes\":{\"label\":<workflow>} \"default\":<workflow>?}
    {\"type\":\"bind\" \"source\":<workflow> \"to\":<workflow>}
    {\"type\":\"iterate\" \"worker\":<workflow> \"evaluator\":<workflow> \"max-rounds\":?}
  Response is the karcarthy result map as JSON. \"adapter\" is \"mock\" (default,
  offline) or \"claude\". For deterministic offline demos, add
  \"mock-responses\": {\"agent-name\":\"text\"}."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.adapter.claude :as cc]))

(declare json->workflow)

(def ^:dynamic *exit-on-error* true)

(def ^:private help-text
  (str
   "karcarthy\n"
   "\n"
   "Usage:\n"
   "  karcarthy agent NAME [PROMPT...] --instructions TEXT [options]\n"
   "  karcarthy run WORKFLOW.json [PROMPT...] [options]\n"
   "  karcarthy json < request.json\n"
   "  karcarthy demo\n"
   "\n"
   "Options:\n"
   "  --adapter NAME          Adapter to use: mock (default) or claude\n"
   "  --instructions TEXT     Agent instructions for `agent`\n"
   "  --input TEXT            Input text instead of positional PROMPT\n"
   "  --model NAME            Model hint on the agent node\n"
   "  --tool NAME             Add an adapter tool allowlist entry; repeatable\n"
   "  --mock-response A=TEXT  Deterministic mock response for agent A; repeatable\n"
   "  --json                  Print the full result JSON\n"
   "  --pretty                Pretty-print JSON\n"
   "  -h, --help              Show this help\n"
   "\n"
   "Examples:\n"
   "  karcarthy agent echo --instructions \"Echo the input.\" hi\n"
   "  karcarthy run launch.json \"Prepare a launch review.\"\n"
   "  karcarthy run launch.json --input \"Prepare a launch review.\" --json\n"
   "  karcarthy json < request.json\n"))

(defn- json->routes [routes]
  (reduce-kv (fn [acc label f] (assoc acc label (json->workflow f))) {} routes))

(defn json->workflow
  "Translate a JSON-parsed workflow map (string keys) into karcarthy workflow data.
  Route labels stay strings; `type`/`adapter` become keywords."
  [m]
  (let [g #(get m %)]
    (case (g "type")
      "agent"       (k/agent (g "name") (g "instructions")
                             :model   (g "model")
                             :tools   (g "tools")
                             :adapter (some-> (g "adapter") keyword))
      "pipe"        (apply o/pipe (map json->workflow (g "steps")))
      "map"         (if (contains? m "branches")
                      (o/map (map json->workflow (g "branches")))
                      (o/map (json->workflow (g "planner"))
                             (json->workflow (g "worker"))))
      "reduce"      (o/reduce (json->workflow (g "mapped"))
                               (json->workflow (g "reducer")))
      "bind"        (if (contains? m "routes")
                      (let [source (json->workflow (g "source"))
                            routes (json->routes (g "routes"))]
                        (if (contains? m "default")
                          (o/bind source routes :default (json->workflow (g "default")))
                          (o/bind source routes)))
                      (o/bind (json->workflow (g "source"))
                              (json->workflow (g "to"))))
      "iterate"     (o/iterate (json->workflow (g "worker")) (json->workflow (g "evaluator"))
                               :max-rounds (or (g "max-rounds") 3))
      (throw (ex-info (str "unknown workflow type: " (pr-str (g "type"))) {:node m})))))

(defn- mock-response-adapter [responses]
  (if (map? responses)
    (k/mock-adapter
     (fn [{:keys [agent prompt]}]
       (if (contains? responses (:name agent))
         (str (get responses (:name agent)))
         (str "[" (:name agent) "] " prompt))))
    (k/mock-adapter)))

(defn- adapter-for
  "Build the adapter named in the request. \"claude\" uses lean sub-agent
  defaults (replace mode, tools off) so cross-language agents answer directly."
  [req]
  (let [name (get req "adapter")]
    (if (= name "claude")
      (cc/claude-cli {:system-prompt-mode :replace
                      :max-turns          4
                      :model              "haiku"
                      :dir                "/tmp/karc"
                      :extra-args         ["--disallowedTools"
                                           "Bash,Edit,Write,Read,Glob,Grep,WebSearch,WebFetch,Task,TodoWrite"]})
      (mock-response-adapter (get req "mock-responses")))))

(defn- result->json [r]
  (try
    (json/write-str r)
    (catch Throwable _
      (json/write-str (select-keys r [:karcarthy/type :ok? :text :agent :rounds :subtasks])))))

(defn- error-result [t]
  {:ok false
   :error (.getMessage t)
   :exception (.getName (class t))})

(defn- result->pretty-json [r]
  (try
    (with-out-str (json/pprint r))
    (catch Throwable _
      (with-out-str (json/pprint (select-keys r [:karcarthy/type :ok? :text :agent :rounds :subtasks]))))))

(defn- result->text [r]
  (or (:text r)
      (get r "text")
      (result->json r)))

(defn- run-request [req]
  (let [workflow (json->workflow (get req "workflow"))
        input    (get req "input" "")
        adapter  (adapter-for req)]
    (o/run adapter workflow input)))

(defn- render-result [result opts]
  (cond
    (:pretty? opts) (result->pretty-json result)
    (:json? opts)   (result->json result)
    :else           (str (result->text result) "\n")))

(defn- read-json [s]
  (json/read-str s))

(defn- read-file-or-stdin [path]
  (if (= "-" path)
    (slurp *in*)
    (slurp path)))

(defn- missing-value [flag]
  (throw (ex-info (str flag " requires a value") {:flag flag})))

(defn- split-pair [s]
  (let [[k v] (str/split s #"=" 2)]
    (when (or (str/blank? k) (nil? v))
      (throw (ex-info "--mock-response expects AGENT=TEXT" {:value s})))
    [k v]))

(defn- parse-opts [args]
  (loop [args (seq args)
         opts {:positionals [] :tools [] :mock-responses {}}]
    (if-not args
      opts
      (let [[arg & more] args]
        (case arg
          "--adapter"       (if-let [v (first more)]
                              (recur (next more) (assoc opts :adapter v))
                              (missing-value arg))
          "--instructions"  (if-let [v (first more)]
                              (recur (next more) (assoc opts :instructions v))
                              (missing-value arg))
          "--input"         (if-let [v (first more)]
                              (recur (next more) (assoc opts :input v))
                              (missing-value arg))
          "--model"         (if-let [v (first more)]
                              (recur (next more) (assoc opts :model v))
                              (missing-value arg))
          "--tool"          (if-let [v (first more)]
                              (recur (next more) (update opts :tools conj v))
                              (missing-value arg))
          "--mock-response" (if-let [v (first more)]
                              (let [[k text] (split-pair v)]
                                (recur (next more) (assoc-in opts [:mock-responses k] text)))
                              (missing-value arg))
          "--json"          (recur more (assoc opts :json? true))
          "--pretty"        (recur more (assoc opts :json? true :pretty? true))
          "--help"          (recur more (assoc opts :help? true))
          "-h"              (recur more (assoc opts :help? true))
          "--"              (update opts :positionals into more)
          (recur more (update opts :positionals conj arg)))))))

(defn- prompt-input [opts prompt]
  (or (:input opts)
      (some->> prompt seq (str/join " "))
      ""))

(defn- with-common-request-opts [req opts]
  (cond-> req
    (:adapter opts)                         (assoc "adapter" (:adapter opts))
    (seq (:mock-responses opts))            (assoc "mock-responses" (:mock-responses opts))))

(defn- agent-request [opts]
  (let [[name & prompt] (:positionals opts)]
    (when-not name
      (throw (ex-info "agent requires NAME" {})))
    (with-common-request-opts
      {"workflow" (cond-> {"type"         "agent"
                           "name"         name
                           "instructions" (or (:instructions opts) "Respond to the input.")}
                    (:model opts)       (assoc "model" (:model opts))
                    (seq (:tools opts)) (assoc "tools" (:tools opts)))
       "input"    (prompt-input opts prompt)}
      opts)))

(defn- request-from-json [m opts input]
  (with-common-request-opts
    (if (contains? m "workflow")
      (assoc m "input" (or input (get m "input" "")))
      {"workflow" m
       "input"    (or input "")})
    opts))

(defn- run-file-request [opts]
  (let [[path & prompt] (:positionals opts)]
    (when-not path
      (throw (ex-info "run requires WORKFLOW.json" {})))
    (request-from-json (read-json (read-file-or-stdin path))
                       opts
                       (or (:input opts)
                           (some->> prompt seq (str/join " "))))))

(defn- json-stdin-output [opts]
  (let [json-opts (assoc opts :json? true)]
    (try
      (render-result (run-request (read-json (slurp *in*))) json-opts)
      (catch Throwable t
        (render-result (error-result t) json-opts)))))

(defn- command-output [args]
  (let [[cmd & rest] args
        opts (parse-opts rest)]
    (cond
      (or (:help? opts) (= cmd "help") (= cmd "--help") (= cmd "-h")) help-text
      (= cmd "agent")                  (render-result (run-request (agent-request opts)) opts)
      (= cmd "run")                    (render-result (run-request (run-file-request opts)) opts)
      (= cmd "json")                   (json-stdin-output opts)
      (= cmd "demo")                   (render-result (run-request {"workflow" {"type" "agent"
                                                                                 "name" "echo"
                                                                                 "instructions" "Echo the input."}
                                                                    "input"    "hi"})
                                                     opts)
      (nil? cmd)                       help-text
      :else                            (throw (ex-info (str "unknown command: " cmd) {:command cmd})))))

(defn -main [& args]
  (.mkdirs (java.io.File. "/tmp/karc"))
  (let [out (try
              (if (and (empty? args) (System/console))
                help-text
                (command-output args))
              (catch Throwable t
                (binding [*out* *err*]
                  (println (str "karcarthy: " (.getMessage t)))
                  (println)
                  (print help-text))
                (when *exit-on-error*
                  (System/exit 1))
                ""))]
    (print out)
    (flush)))
