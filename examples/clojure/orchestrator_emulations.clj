;; Offline emulations of popular agent orchestrator patterns.
;;
;; Run:
;;
;;     clojure -M -e '(load-file "examples/clojure/orchestrator_emulations.clj")'
;;
;; This intentionally uses the mock runner: the point is to show that the shapes
;; are ordinary karcarthy data before any paid model call happens.

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[karcarthy :as k])

(defn- contains-ci? [s needle]
  (str/includes? (str/lower-case (str s)) (str/lower-case needle)))

(defn- base-name [agent-name]
  (first (str/split (str agent-name) #"/")))

(defn- append-line [transcript speaker line]
  (str (str/trim (str transcript)) "\n" speaker ": " line))

(def demo-runner
  (k/mock-runner
   (fn [{:keys [agent prompt]}]
     (let [n (base-name (:name agent))]
       (case n
         "triage" (cond
                    (contains-ci? prompt "refund") "billing"
                    (contains-ci? prompt "500")    "technical"
                    :else                          "general")

         "billing-specialist" "Refund path: verify account, explain policy, then offer the fastest supported remedy."
         "technical-specialist" "Technical path: capture the failing endpoint, check deploy logs, and roll back if error rate rises."
         "generalist" "General path: answer directly and keep the next action explicit."

         "researcher" "Market signal: teams want orchestrators, but they also want inspectable plans."
         "analyst" "Risk readout: keep runner boundaries clear, add schemas where outputs cross teams, trace every handoff."
         "writer" (str "Brief: " prompt)

         "pm" (append-line prompt "PM" "Scope the demo around patterns developers already recognize.")
         "engineer" (append-line prompt "Engineer" "Compile those patterns to data so they can be diffed and rewritten.")
         "critic" (append-line prompt "Critic" "Show the generated workflow, not only the final answer.")

         "docs-agent" "Docs path: explain the API by showing the EDN workflow and its runner boundary."
         "code-agent" "Code path: build the thin adapter first, then verify it with the offline runner."
         "synthesizer" (str "Synthesized answer from graph state: " prompt)

         "policy-check" "Policy: no risky side effects in this demo."
         "cost-check" "Cost: mock runner keeps the demo free; real runners can be swapped in."
         "launch-check" "Launch: examples are load-file runnable."

         (str "[" (:name agent) "] " prompt))))))

(k/defagent triage "Classify a support request as billing, technical, or general.")
(k/defagent billing-specialist "Own billing/refund support replies.")
(k/defagent technical-specialist "Own technical incident replies.")
(k/defagent generalist "Own general replies.")

(k/defagent researcher "Research the market and cite the main signal.")
(k/defagent analyst "Analyze risks and trade-offs.")
(k/defagent writer "Write the final brief.")

(k/defagent pm "Represent product management in a group chat.")
(k/defagent engineer "Represent engineering in a group chat.")
(k/defagent critic "Represent critique/review in a group chat.")

(k/defagent docs-agent "Answer with documentation-oriented guidance.")
(k/defagent code-agent "Answer with implementation-oriented guidance.")
(k/defagent synthesizer "Synthesize the latest graph state into the final reply.")

(k/defagent policy-check "Check launch policy.")
(k/defagent cost-check "Check launch cost.")
(k/defagent launch-check "Check launch readiness.")

(def crewai-like
  (k/crew [{:agent researcher
            :id :market
            :description "Find the clearest market signal for karcarthy."
            :expected-output "One crisp finding."}
           {:agent analyst
            :id :risks
            :description "Identify implementation risks in the proposal."
            :expected-output "A short risk readout."}
           {:agent writer
            :id :brief
            :description "Turn the findings into a concise launch brief."
            :expected-output "One paragraph."}]))

(def autogen-like
  (k/group-chat [pm engineer critic] :rounds 5))

(def openai-agents-like
  (k/handoff-router triage
                    {"billing"   billing-specialist
                     "technical" technical-specialist
                     "general"   generalist}
                    :default generalist))

(defn- classify-node [{:keys [input]}]
  {:text  input
   :route (if (contains-ci? input "api") :code :docs)})

(def langgraph-like
  (k/state-graph
   {:start :classify
    :nodes {:classify classify-node
            :docs      {:workflow docs-agent :prompt :input}
            :code      {:workflow code-agent :prompt :input}
            :finish    {:workflow synthesizer
                        :prompt (fn [state]
                                  (str "input=" (:input state)
                                       "\nroute=" (name (:route state))
                                       "\nworker=" (:text state)))}}
    :edges {:classify {:docs :docs :code :code}
            :docs      :finish
            :code      :finish
            :finish    :end}}))

(def adk-like
  (k/workflow-agent :parallel
                    [policy-check cost-check launch-check]
                    :gather (fn [results]
                              (k/result
                               {:text (str/join "\n" (map #(str "- " (:text %))
                                                           results))}))))

(defn- compact-workflow [workflow]
  (walk/postwalk
   (fn [x]
     (cond
       (k/agent? x) (select-keys x [:karcarthy/type :name :model :runner])
       (fn? x)      :fn
       :else        x))
   workflow))

(def scenarios
  [{:name "CrewAI-style crew: agents + tasks + sequential process"
    :workflow crewai-like
    :input "Show why karcarthy matters."}
   {:name "AutoGen-style group chat: round-robin speakers over a transcript"
    :workflow autogen-like
    :input "Kick off a demo plan."}
   {:name "OpenAI Agents SDK-style: triage to specialist ownership"
    :workflow openai-agents-like
    :input "Customer asks for a refund after a failed renewal."}
   {:name "LangGraph-style: state, nodes, and routed edges"
    :workflow langgraph-like
    :input "Should the API demo be docs-first or code-first?"}
   {:name "Google ADK-style workflow agent: deterministic parallel checks"
    :workflow adk-like
    :input "Can we launch the orchestrator emulation example?"}])

(doseq [{:keys [name workflow shape run input]} scenarios]
  (println "\n===" name "===")
  (println "orchestration data:")
  (pp/pprint (compact-workflow (or workflow shape)))
  (let [r (if run
            (run input)
            (k/run demo-runner workflow input))]
    (println "ok? " (k/ok? r))
    (println "text:")
    (println (:text r))))

(shutdown-agents)
