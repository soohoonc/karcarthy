(ns karcarthy.cli
  "A language-agnostic bridge. Read a flow described as JSON on stdin, run it, and
  write the result as JSON on stdout. Any language can drive karcarthy by
  exchanging data, so the homoiconic part (a workflow is data you can build,
  transform, and have an agent author or edit) survives the boundary.

      echo '{\"flow\": <flow>, \"input\": \"...\", \"harness\": \"mock\"}' \\
        | clojure -M -m karcarthy.cli

  A <flow> is JSON mirroring the EDN flow:
    {\"type\":\"agent\" \"name\":_ \"instructions\":_ \"model\":?  \"harness\":?}
    {\"type\":\"chain\" \"steps\":[<flow> ...]}
    {\"type\":\"parallel\" \"branches\":[<flow> ...]}
    {\"type\":\"route\" \"router\":<flow> \"routes\":{\"label\":<flow>} \"default\":<flow>?}
    {\"type\":\"refine\" \"worker\":<flow> \"evaluator\":<flow> \"max-rounds\":?}
    {\"type\":\"orchestrate\" \"planner\":<flow> \"worker\":<flow>}
    {\"type\":\"handoff\" \"from\":<flow> \"to\":<flow>}
    {\"type\":\"evolve\" \"agent\":<flow> \"max-rounds\":?}
  Response is the karcarthy result map as JSON. \"harness\" is \"mock\" (default,
  offline) or \"claude\"."
  (:require [clojure.data.json :as json]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.self :as self]
            [karcarthy.harness.claude :as cc]))

(defn json->flow
  "Translate a JSON-parsed flow map (string keys) into karcarthy flow data.
  Route labels stay strings; `type`/`harness` become keywords."
  [m]
  (let [g #(get m %)]
    (case (g "type")
      "agent"       (k/agent (g "name") (g "instructions")
                             :model   (g "model")
                             :tools   (g "tools")
                             :harness (some-> (g "harness") keyword))
      "chain"       (apply o/chain (map json->flow (g "steps")))
      "parallel"    (apply o/parallel (map json->flow (g "branches")))
      "route"       (o/route (json->flow (g "router"))
                             (reduce-kv (fn [acc label f] (assoc acc label (json->flow f)))
                                        {} (g "routes"))
                             :default (some-> (g "default") json->flow))
      "refine"      (o/refine (json->flow (g "worker")) (json->flow (g "evaluator"))
                              :max-rounds (or (g "max-rounds") 3))
      "orchestrate" (o/orchestrate (json->flow (g "planner")) (json->flow (g "worker")))
      "handoff"     (o/handoff (json->flow (g "from")) (json->flow (g "to")))
      "evolve"      (self/evolve (json->flow (g "agent")) :max-rounds (or (g "max-rounds") 5))
      (throw (ex-info (str "unknown flow type: " (pr-str (g "type"))) {:node m})))))

(defn- harness-for
  "Build the harness named in the request. \"claude\" uses lean sub-agent
  defaults (replace mode, tools off) so cross-language agents answer directly."
  [name]
  (if (= name "claude")
    (cc/claude-harness {:system-prompt-mode :replace
                        :max-turns          4
                        :model              "haiku"
                        :dir                "/tmp/karc"
                        :extra-args         ["--disallowedTools"
                                             "Bash,Edit,Write,Read,Glob,Grep,WebSearch,WebFetch,Task,TodoWrite"]})
    (k/mock-harness)))

(defn- result->json [r]
  (try
    (json/write-str r)
    (catch Throwable _
      (json/write-str (select-keys r [:karcarthy/type :ok? :text :agent :rounds :subtasks])))))

(defn -main [& _]
  (.mkdirs (java.io.File. "/tmp/karc"))
  (let [out (try
              (let [req     (json/read-str (slurp *in*))
                    flow    (json->flow (get req "flow"))
                    input   (get req "input" "")
                    harness (harness-for (get req "harness"))]
                (result->json (o/run-flow harness flow input)))
              (catch Throwable t
                (json/write-str {:ok false
                                 :error (.getMessage t)
                                 :exception (.getName (class t))})))]
    (print out)
    (flush)))
