;; A LIVE map/reduce run against the Claude CLI adapter (`claude -p`).
;;
;; Unlike the offline `karcarthy.demo`, this makes real, paid API calls, so run
;; it intentionally. It needs a working `claude` CLI with valid auth on PATH.
;;
;;     clojure -M -e '(load-file "examples/clojure/live.clj")'
;;
;; It demonstrates the CLI tuning that makes agents behave as constrained
;; sub-agents rather than as an interactive Claude Code session:
;;   * :system-prompt-mode :replace  - the system prompt is *only* the agent's
;;     instructions, with none of Claude Code's interactive persona;
;;   * --disallowedTools ...          - tools off, so agents answer directly
;;     instead of wandering into tool use (which burns turns);
;;   * a neutral :dir                 - avoids picking up the project's CLAUDE.md.

(require '[karcarthy :as k]
         '[karcarthy.orchestrate :as o]
         '[clojure.string :as str])

(.mkdirs (java.io.File. "/tmp/karc"))

(def no-tools
  ["--disallowedTools"
   "Bash,Edit,Write,Read,Glob,Grep,WebSearch,WebFetch,Task,TodoWrite,NotebookEdit,MultiEdit"])

(def adapter
  (k/claude-cli {:system-prompt-mode :replace
                 :max-turns          4
                 :model              "haiku"
                 :dir                "/tmp/karc"
                 :extra-args         no-tools}))

(def planner
  (k/agent "planner"
           "Output ONLY two lines, each a short subtopic (a noun phrase) for the question. No numbering, no preamble, no blank lines."))

(def writer
  (k/agent "writer"
           "Write ONE concise sentence about the given subtopic. Output only the sentence."))

(def research
  (o/map planner writer
         :reduce (fn [results _input]
                   (k/result {:text (str/join "\n" (map #(str "- " (:text %)) results))}))))

(let [r (o/run adapter research
               "Why is homoiconicity useful for agent orchestration?")]
  (println "SUBTASKS:" (pr-str (:subtasks r)))
  (println "OK?      " (k/ok? r))
  (println "RESULT:")
  (println (:text r))
  (doseq [w (:results r)]
    (println (format "  [worker] ok=%s turns=%s cost=$%.4f"
                     (k/ok? w) (:num-turns w) (or (:cost-usd w) 0.0)))))

(shutdown-agents)
