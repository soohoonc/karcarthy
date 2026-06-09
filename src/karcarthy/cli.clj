(ns karcarthy.cli
  "Command-line entry point and language-agnostic JSON bridge.

      ./bin/karcarthy agent echo --instructions \"Echo the input.\" hi
      ./bin/karcarthy run workflow.json \"what is a monad?\"
      ./bin/karcarthy json < request.json

  A <workflow> is JSON mirroring the EDN workflow:
    {\"type\":\"agent\" \"name\":_ \"instructions\":_ \"model\":?  \"adapter\":?}
    {\"type\":\"pipe\" \"steps\":[<workflow> ...]}
    {\"type\":\"branch\" \"branches\":[<workflow> ...] \"max-concurrency\":?}
    {\"type\":\"delegate\" \"planner\":<workflow> \"worker\":<workflow> \"max-concurrency\":?}
    {\"type\":\"reduce\" \"source\":<branch-or-delegate-workflow> \"reducer\":<workflow>}
    {\"type\":\"route\" \"source\":<workflow> \"routes\":{\"label\":<workflow>} \"default\":<workflow>?}
    {\"type\":\"continue\" \"source\":<workflow> \"to\":<workflow>}
    {\"type\":\"revise\" \"worker\":<workflow> \"evaluator\":<workflow> \"max-rounds\":?}
    {\"type\":\"dynamic\" \"agent\":<agent> \"max-steps\":?}
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
  (let [g                #(get m %)
        with-concurrency #(cond-> %
                            (g "max-concurrency") (assoc :max-concurrency (g "max-concurrency")))]
    (case (g "type")
      "agent"       (k/agent (g "name") (g "instructions")
                             :model   (g "model")
                             :tools   (g "tools")
                             :adapter (some-> (g "adapter") keyword))
      "pipe"        (apply o/pipe (map json->workflow (g "steps")))
      "branch"      (with-concurrency
                      (o/branch (map json->workflow (g "branches"))))
      "delegate"    (with-concurrency
                      (o/delegate (json->workflow (g "planner"))
                                  (json->workflow (g "worker"))))
      "reduce"      (o/reduce (json->workflow (g "source"))
                               (json->workflow (g "reducer")))
      "route"       (let [source (json->workflow (g "source"))
                          routes (json->routes (g "routes"))]
                      (if (contains? m "default")
                        (o/route source routes :default (json->workflow (g "default")))
                        (o/route source routes)))
      "continue"    (o/continue (json->workflow (g "source"))
                                (json->workflow (g "to")))
      "revise"      (o/revise (json->workflow (g "worker")) (json->workflow (g "evaluator"))
                               :max-rounds (or (g "max-rounds") 3))
      "dynamic"     (o/dynamic (json->workflow (g "agent"))
                                :max-steps (or (g "max-steps") 25))
      (throw (ex-info (str "unknown workflow type: " (pr-str (g "type"))) {:node m})))))

(defn- mock [responses]
  (if (map? responses)
    (k/mock-adapter
     (fn [{:keys [agent prompt]}]
       (if (contains? responses (:name agent))
         (str (get responses (:name agent)))
         (str "[" (:name agent) "] " prompt))))
    (k/mock-adapter)))

(defn- adapter
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
      (mock (get req "mock-responses")))))

(defn- result->json [r]
  (try
    (json/write-str r)
    (catch Throwable _
      (json/write-str (select-keys r [:karcarthy/type :ok? :text :agent :rounds :subtasks])))))

(defn- throwable->result [t]
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

(defn- invoke! [req]
  (let [workflow (json->workflow (get req "workflow"))
        input    (get req "input" "")
        h        (adapter req)]
    (o/run h workflow input)))

(defn- render [result opts]
  (cond
    (:pretty? opts) (result->pretty-json result)
    (:json? opts)   (result->json result)
    :else           (str (result->text result) "\n")))

(defn- json! [s]
  (json/read-str s))

(defn- source! [path]
  (if (= "-" path)
    (slurp *in*)
    (slurp path)))

(defn- value! [flag]
  (throw (ex-info (str flag " requires a value") {:flag flag})))

(defn- mock! [s]
  (let [[k v] (str/split s #"=" 2)]
    (when (or (str/blank? k) (nil? v))
      (throw (ex-info "--mock-response expects AGENT=TEXT" {:value s})))
    [k v]))

(defn- opts [args]
  (loop [args (seq args)
         opts {:positionals [] :tools [] :mock-responses {}}]
    (if-not args
      opts
      (let [[arg & more] args]
        (case arg
          "--adapter"       (if-let [v (first more)]
                              (recur (next more) (assoc opts :adapter v))
                              (value! arg))
          "--instructions"  (if-let [v (first more)]
                              (recur (next more) (assoc opts :instructions v))
                              (value! arg))
          "--input"         (if-let [v (first more)]
                              (recur (next more) (assoc opts :input v))
                              (value! arg))
          "--model"         (if-let [v (first more)]
                              (recur (next more) (assoc opts :model v))
                              (value! arg))
          "--tool"          (if-let [v (first more)]
                              (recur (next more) (update opts :tools conj v))
                              (value! arg))
          "--mock-response" (if-let [v (first more)]
                              (let [[k text] (mock! v)]
                                (recur (next more) (assoc-in opts [:mock-responses k] text)))
                              (value! arg))
          "--json"          (recur more (assoc opts :json? true))
          "--pretty"        (recur more (assoc opts :json? true :pretty? true))
          "--help"          (recur more (assoc opts :help? true))
          "-h"              (recur more (assoc opts :help? true))
          "--"              (update opts :positionals into more)
          (recur more (update opts :positionals conj arg)))))))

(defn- input [opts prompt]
  (or (:input opts)
      (some->> prompt seq (str/join " "))
      ""))

(defn- request-opts [req opts]
  (cond-> req
    (:adapter opts)                         (assoc "adapter" (:adapter opts))
    (seq (:mock-responses opts))            (assoc "mock-responses" (:mock-responses opts))))

(defn- agent->request [opts]
  (let [[name & prompt] (:positionals opts)]
    (when-not name
      (throw (ex-info "agent requires NAME" {})))
    (request-opts
      {"workflow" (cond-> {"type"         "agent"
                           "name"         name
                           "instructions" (or (:instructions opts) "Respond to the input.")}
                    (:model opts)       (assoc "model" (:model opts))
                    (seq (:tools opts)) (assoc "tools" (:tools opts)))
       "input"    (input opts prompt)}
      opts)))

(defn- json->request [m opts input]
  (request-opts
    (if (contains? m "workflow")
      (assoc m "input" (or input (get m "input" "")))
      {"workflow" m
       "input"    (or input "")})
    opts))

(defn- file->request [opts]
  (let [[path & prompt] (:positionals opts)]
    (when-not path
      (throw (ex-info "run requires WORKFLOW.json" {})))
    (json->request (json! (source! path))
                   opts
                   (or (:input opts)
                       (some->> prompt seq (str/join " "))))))

(defn- json-command! [opts]
  (let [json-opts (assoc opts :json? true)]
    (try
      (render (invoke! (json! (slurp *in*))) json-opts)
      (catch Throwable t
        (render (throwable->result t) json-opts)))))

(defn- dispatch! [args]
  (let [[cmd & rest] args
        parsed (opts rest)]
    (cond
      (or (:help? parsed) (= cmd "help") (= cmd "--help") (= cmd "-h")) help-text
      (= cmd "agent")                  (render (invoke! (agent->request parsed)) parsed)
      (= cmd "run")                    (render (invoke! (file->request parsed)) parsed)
      (= cmd "json")                   (json-command! parsed)
      (= cmd "demo")                   (render (invoke! {"workflow" {"type" "agent"
                                                                      "name" "echo"
                                                                      "instructions" "Echo the input."}
                                                         "input"    "hi"})
                                               parsed)
      (nil? cmd)                       help-text
      :else                            (throw (ex-info (str "unknown command: " cmd) {:command cmd})))))

(defn -main [& args]
  (.mkdirs (java.io.File. "/tmp/karc"))
  (let [out (try
              (if (and (empty? args) (System/console))
                help-text
                (dispatch! args))
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
