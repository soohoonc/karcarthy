(ns karcarthy.cli
  "A language-agnostic bridge. Read a workflow described as JSON on stdin, run it,
  and write the result as JSON on stdout. Any language can drive karcarthy by
  exchanging data, so the homoiconic part (a workflow is data you can build,
  transform, and have an agent generate or edit) survives the boundary.

      echo '{\"workflow\": <workflow>, \"input\": \"...\", \"adapter\": \"mock\"}' \\
        | ./bin/karcarthy

  A <workflow> is JSON mirroring the EDN workflow:
    {\"type\":\"agent\" \"name\":_ \"instructions\":_ \"model\":?  \"adapter\":?}
    {\"type\":\"pipe\" \"steps\":[<workflow> ...]}
    {\"type\":\"map\" \"branches\":[<workflow> ...]}
    {\"type\":\"map\" \"planner\":<workflow> \"worker\":<workflow>}
    {\"type\":\"bind\" \"source\":<workflow> \"routes\":{\"label\":<workflow>} \"default\":<workflow>?}
    {\"type\":\"bind\" \"source\":<workflow> \"to\":<workflow>}
    {\"type\":\"iterate\" \"worker\":<workflow> \"evaluator\":<workflow> \"max-rounds\":?}
    {\"type\":\"evolve\" \"agent\":<workflow> \"max-rounds\":?}
  Response is the karcarthy result map as JSON. \"adapter\" is \"mock\" (default,
  offline) or \"claude\". For deterministic offline demos, add
  \"mock-responses\": {\"agent-name\":\"text\"}."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.self :as self]
            [karcarthy.runner.claude :as cc]))

(declare json->workflow)

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
                             :adapter (some-> (or (g "adapter") (g "runner") (g "harness")) keyword)
                             :runner  (some-> (g "runner") keyword)
                             :harness (some-> (g "harness") keyword))
      "pipe"        (apply o/pipe (map json->workflow (g "steps")))
      "map"         (if (contains? m "branches")
                      (o/map (map json->workflow (g "branches")))
                      (o/map (json->workflow (g "planner"))
                             (json->workflow (g "worker"))))
      "bind"        (if (contains? m "routes")
                      (o/bind (json->workflow (or (g "source") (g "router")))
                              (json->routes (g "routes"))
                              :default (some-> (g "default") json->workflow))
                      (o/bind (json->workflow (or (g "source") (g "from")))
                              (json->workflow (g "to"))))
      "iterate"     (o/iterate (json->workflow (g "worker")) (json->workflow (g "evaluator"))
                               :max-rounds (or (g "max-rounds") 3))
      "chain"       (apply o/chain (map json->workflow (g "steps")))
      "parallel"    (apply o/parallel (map json->workflow (g "branches")))
      "route"       (o/route (json->workflow (g "router"))
                             (json->routes (g "routes"))
                             :default (some-> (g "default") json->workflow))
      "refine"      (o/refine (json->workflow (g "worker")) (json->workflow (g "evaluator"))
                              :max-rounds (or (g "max-rounds") 3))
      "orchestrate" (o/orchestrate (json->workflow (g "planner")) (json->workflow (g "worker")))
      "handoff"     (o/handoff (json->workflow (g "from")) (json->workflow (g "to")))
      "evolve"      (self/evolve (json->workflow (g "agent")) :max-rounds (or (g "max-rounds") 5))
      (throw (ex-info (str "unknown workflow type: " (pr-str (g "type"))) {:node m})))))

(defn json->flow
  "Deprecated alias for `json->workflow`."
  [m]
  (json->workflow m))

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
  (let [name (or (get req "adapter") (get req "runner") (get req "harness"))]
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

(defn -main [& _]
  (.mkdirs (java.io.File. "/tmp/karc"))
  (let [out (try
              (let [req     (json/read-str (slurp *in*))
                    workflow (json->workflow (or (get req "workflow") (get req "flow")))
                    input   (get req "input" "")
                    adapter (adapter-for req)]
                (result->json (o/run adapter workflow input)))
              (catch Throwable t
                (json/write-str {:ok false
                                 :error (.getMessage t)
                                 :exception (.getName (class t))})))]
    (print out)
    (flush)))
