;; Deep Research-shaped workflow as karcarthy data.
;;
;; Run offline:
;;   clojure -M -e '(load-file "examples/clojure/deep_research.clj")'
;;
;; Run live through Codex CLI, if installed and authenticated:
;;   KARCARTHY_CODEX_LIVE=1 clojure -M -e '(load-file "examples/clojure/deep_research.clj")'
;;
;; The point is not to clone OpenAI's Deep Research model. It is to express the
;; public shape of that kind of system as EDN:
;;
;;   plan research tracks -> investigate tracks in parallel -> synthesize with
;;   citations -> audit/verdict -> revise if needed
;;
;; Public references behind the shape:
;; - OpenAI describes deep research as multi-step internet research that plans,
;;   searches, evaluates sources, refines, synthesizes, and cites sources.
;; - The Deep Research API exposes web search, code interpreter, and MCP calls
;;   in response output metadata.
;; - Codex CLI supports non-interactive `codex exec` and stdin, making it a
;;   natural command-adapter target. Codex can also run as an MCP server for
;;   OpenAI Agents SDK workflows; this example uses `exec` because it is the
;;   thinnest leaf-agent adapter shape.
;; - Anthropic describes Dynamic Workflows as Claude planning work, running many
;;   parallel subagents, verifying work, and combining results. In karcarthy,
;;   the analogous orchestration artifact is EDN workflow data.
;;
;; This file stays offline by default. Set KARCARTHY_CODEX_LIVE=1 only when you
;; intentionally want paid/live Codex CLI calls.

(require '[clojure.edn :as edn]
         '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[karcarthy :as k])

(defn bullets [xs]
  (str/join "\n" (map #(str "- " %) xs)))

(defn section [title body]
  (when (seq body)
    (str title ":\n" body)))

(defn research-instructions
  [{:keys [role mission tools responsibilities output boundaries]}]
  (->> [(str "Role: " role)
        (str "Mission: " mission)
        (if (seq tools)
          (str "Adapter tool allowlist:\n" (bullets tools)
               "\nThe adapter must already provide these tools. karcarthy only passes names.")
          "Adapter tool allowlist: none.")
        (section "Responsibilities" (bullets responsibilities))
        (str "Output contract:\n" output)
        (section "Boundaries" (bullets boundaries))]
       (remove str/blank?)
       (str/join "\n\n")))

(defn research-agent [{:keys [name tools adapter] :as profile}]
  (cond-> (k/agent name (research-instructions profile))
    (seq tools) (assoc :tools (vec tools))
    adapter (assoc :adapter adapter)))

(def research-planner
  (research-agent
   {:name "research-planner"
    :role "Research architect"
    :mission "Break the question into independent research tracks."
    :responsibilities ["Choose tracks that can be investigated independently."
                       "Prefer source diversity: official docs, primary announcements, examples, and skeptical/limitation sources."
                       "Keep the plan small enough to run in parallel."]
    :output "Reply with EDN only: {:subtasks [\"track one\" \"track two\" ...]}. Use 3 to 5 tracks."
    :boundaries ["Do not answer the user's question." "Do not cite sources you have not seen."]}))

(def source-scout
  (research-agent
   {:name "source-scout"
    :role "Source scout"
    :mission "Find source candidates for one research track."
    :tools ["WebSearch" "WebFetch"]
    :responsibilities ["Search for primary or high-quality sources for the track."
                       "Prefer official documentation, system cards, API docs, papers, and reputable analysis."
                       "Return source metadata before interpretation."]
    :output "EDN only: {:track \"...\" :sources [{:title \"...\" :url \"...\" :why \"...\"} ...]}."
    :boundaries ["Do not synthesize the whole answer." "Do not include uncited factual claims."]}))

(def evidence-reader
  (research-agent
   {:name "evidence-reader"
    :role "Evidence extractor"
    :mission "Read the source packet and extract supportable claims."
    :responsibilities ["Extract claims that answer the track."
                       "Attach each claim to a source URL."
                       "Preserve uncertainty and conflicts."]
    :output "EDN only: {:track \"...\" :claims [{:claim \"...\" :url \"...\" :confidence :high|:medium|:low} ...] :gaps [\"...\"]}."
    :boundaries ["No source URL, no claim." "Do not invent publication dates, metrics, or capabilities."]}))

(def source-judge
  (research-agent
   {:name "source-judge"
    :role "Source quality judge"
    :mission "Reject weak or unsupported evidence before synthesis."
    :responsibilities ["Score source quality."
                       "Flag unsupported claims."
                       "Keep enough detail for a report writer to cite correctly."]
    :output "EDN only: {:track \"...\" :accepted [{:claim \"...\" :url \"...\"}] :rejected [{:claim \"...\" :reason \"...\"}] :gaps [\"...\"]}."
    :boundaries ["Do not smooth over disagreement." "Do not write the final report."]}))

(def report-writer
  (research-agent
   {:name "report-writer"
    :role "Citation-backed report writer"
    :mission "Synthesize accepted evidence into a useful deep-research report."
    :responsibilities ["Answer the user's question directly."
                       "Cite every material claim inline with its URL."
                       "Separate findings, caveats, and open questions."
                       "Explain how the workflow used planning, parallel investigation, and synthesis."]
    :output "Markdown sections: Executive answer, Evidence map, Caveats, Open questions, Source list."
    :boundaries ["Do not cite rejected evidence." "Do not hide gaps." "Do not include private chain-of-thought."]}))

(def report-critic
  (research-agent
   {:name "report-critic"
    :role "Research report acceptance critic"
    :mission "Decide whether the report is ready to hand to a human."
    :responsibilities ["Check that important claims are cited."
                       "Check that caveats and source gaps are visible."
                       "Reject if the report makes claims beyond the accepted evidence."]
    :output "EDN only: {:accept? true} or {:accept? false :feedback \"specific revision instructions\"}."
    :boundaries ["Do not rewrite the report yourself."]}))

(def investigation-pipeline
  (k/pipe source-scout evidence-reader source-judge))

(def deep-research-workflow
  (k/iterate
   (k/reduce
    (k/map research-planner investigation-pipeline :max-concurrency 4)
    report-writer)
   report-critic
   :max-rounds 2))

(defn codex-prompt [agent]
  (str "You are running as one karcarthy leaf agent.\n\n"
       "Agent name: " (:name agent) "\n\n"
       "Instructions:\n" (:instructions agent) "\n\n"
       "Return only the output requested by the instructions. "
       "The current input will be appended on stdin."))

(defn codex-adapter
  "Live/paid Codex CLI adapter.

  Codex CLI docs describe `codex exec` as the non-interactive mode and allow the
  prompt to be passed on stdin. Exact flags vary by installed version; verify
  with `codex exec --help` before using this in production."
  ([] (codex-adapter {}))
  ([{:keys [dir timeout-ms] :or {dir "/tmp/karc-deep-research"
                                 timeout-ms (* 20 60 1000)}}]
   (.mkdirs (java.io.File. dir))
   (k/command-adapter
    (fn [agent]
      (cond-> ["codex" "exec"
               "--ephemeral"
               "--color" "never"
               "--sandbox" "read-only"
               "--skip-git-repo-check"
               "--cd" dir]
        (:model agent) (into ["--model" (:model agent)])
        true           (conj (codex-prompt agent))))
    {:dir dir
     :timeout-ms timeout-ms})))

(def offline-responses
  {"research-planner"
   (pr-str
    {:subtasks ["How OpenAI describes Deep Research"
                "What the Deep Research API exposes"
                "How Codex CLI can act as a headless adapter"
                "How karcarthy differs from full agent runtimes"]})

   "source-scout"
   (fn [prompt]
     (pr-str
      {:track prompt
       :sources
       [{:title "Introducing deep research"
         :url "https://openai.com/index/introducing-deep-research/"
         :why "Primary product announcement describing planning, browsing, synthesis, and citations."}
        {:title "Deep research in the OpenAI API"
         :url "https://platform.openai.com/docs/guides/deep-research"
         :why "API documentation describing tool-call metadata for deep research models."}
        {:title "Codex exec mode"
         :url "https://developers.openai.com/codex/noninteractive"
         :why "Codex CLI non-interactive mode for command-adapter execution."}
        {:title "Introducing dynamic workflows in Claude Code"
         :url "https://claude.com/blog/introducing-dynamic-workflows-in-claude-code"
         :why "Primary announcement for the plan, parallel subagent, verification, and synthesis pattern."}]}))

   "evidence-reader"
   (fn [prompt]
     (let [packet (edn/read-string prompt)
           track  (:track packet)]
       (pr-str
        {:track track
         :claims
         [{:claim "Deep Research is described publicly as agentic, multi-step research over web sources with citation-backed outputs."
           :url "https://openai.com/index/introducing-deep-research/"
           :confidence :high}
          {:claim "The Deep Research API exposes web search, code interpreter, and remote MCP calls in response output metadata."
           :url "https://platform.openai.com/docs/guides/deep-research"
           :confidence :high}
          {:claim "Codex CLI has a non-interactive exec mode that can read prompts from stdin."
           :url "https://developers.openai.com/codex/noninteractive"
           :confidence :medium}
          {:claim "Anthropic describes Dynamic Workflows as planning from a prompt, breaking work into subtasks, running parallel subagents, checking results, and returning one coordinated answer."
           :url "https://claude.com/blog/introducing-dynamic-workflows-in-claude-code"
           :confidence :medium}]
         :gaps ["This offline example does not browse; it uses canned source metadata."]})))

   "source-judge"
   (fn [prompt]
     (let [evidence (edn/read-string prompt)]
       (pr-str
        {:track (:track evidence)
         :accepted (mapv #(select-keys % [:claim :url]) (:claims evidence))
         :rejected []
         :gaps (:gaps evidence)})))

   "report-writer"
   (fn [prompt]
     (let [summary (edn/read-string prompt)
           accepted (->> (get-in summary [:results])
                         (mapcat #(-> % :text edn/read-string :accepted))
                         distinct
                         vec)]
       (str "## Executive answer\n"
            "A Deep Research-shaped karcarthy workflow is a data IR: plan tracks, run evidence workers in parallel, filter unsupported claims, synthesize a cited report, then critique and revise. "
            "OpenAI describes Deep Research as multi-step agentic research with documented citations; the API exposes the tool calls used to reach the answer.\n\n"
            "## Evidence map\n"
            (str/join "\n" (map (fn [{:keys [claim url]}] (str "- " claim " (" url ")")) accepted))
            "\n\n## Caveats\n"
            "- This example is offline and deterministic; live browsing belongs inside the selected adapter.\n"
            "- karcarthy coordinates the workflow data, but Codex/OpenAI/Claude/Pydantic-style systems still own tool execution.\n\n"
            "## Open questions\n"
            "- Add real adapter conformance tests for Codex exec, Claude CLI, and OpenAI Agents SDK.\n"
            "- Add stricter schema validation for the EDN control messages.\n\n"
            "## Source list\n"
            "- https://openai.com/index/introducing-deep-research/\n"
            "- https://platform.openai.com/docs/guides/deep-research\n"
            "- https://developers.openai.com/codex/noninteractive\n"
            "- https://claude.com/blog/introducing-dynamic-workflows-in-claude-code\n")))

   "report-critic" "{:accept? true}"})

(def offline-adapter
  (k/mock-adapter
   (fn [{:keys [agent prompt]}]
     (let [response (get offline-responses (:name agent))]
       (cond
         (fn? response) (response prompt)
         response response
         :else (str "[" (:name agent) "] " prompt))))))

(defn event-summary [events]
  (->> events
       (filter #(= :start (:event %)))
       (mapv #(select-keys % [:kind :name :path]))))

(defn run-offline []
  (println "=== Deep Research workflow data ===")
  (pp/pprint deep-research-workflow)
  (println "\n=== Offline run ===")
  (let [events (atom [])
        result (k/run offline-adapter
                      deep-research-workflow
                      "Can karcarthy express an OpenAI Deep Research-style workflow?"
                      {:observe #(swap! events conj %)})]
    (println (:text result))
    (println "\naccepted?" (:accepted? result) "rounds" (:rounds result))
    (println "\n=== Observable execution shape ===")
    (pp/pprint (event-summary @events))))

(defn run-with-codex []
  (println "=== Live Codex Deep Research-shaped run ===")
  (println "This invokes Codex once per leaf agent call and may take several minutes.")
  (let [events (atom [])
        result (k/run (codex-adapter)
                      deep-research-workflow
                      "Can karcarthy express an OpenAI Deep Research-style workflow?"
                      {:observe #(swap! events conj %)})]
    (println (:text result))
    (println "\naccepted?" (:accepted? result) "rounds" (:rounds result))
    (println "\n=== Observable execution shape ===")
    (pp/pprint (event-summary @events))))

(defn -main [& _]
  (if (System/getenv "KARCARTHY_CODEX_LIVE")
    (run-with-codex)
    (run-offline)))

(-main)

(shutdown-agents)
