;; Agents using karcarthy to author and edit their own behavior — LIVE.
;;
;; Makes real, paid `claude -p` calls; run it intentionally. Needs the `claude`
;; CLI with valid auth on PATH.
;;
;;     clojure -M -e '(load-file "examples/self_modify.clj")'
;;
;; Two demonstrations of the metacircular layer (karcarthy.self):
;;   1. run-authored — an agent WRITES a karcarthy flow (EDN), which karcarthy
;;      parses (data only, never eval) and runs.
;;   2. evolve       — an agent EDITS ITS OWN definition at runtime via EDN
;;      patches, then acts with the new behavior.

(require '[karcarthy.core :as k]
         '[karcarthy.self :as self]
         '[karcarthy.orchestrate :as o]
         '[karcarthy.harness.claude :as cc])

(.mkdirs (java.io.File. "/tmp/karc"))

;; Lean sub-agents: system prompt = just the agent's instructions, tools off, so
;; the model answers directly rather than inheriting Claude Code's persona.
(def no-tools
  ["--disallowedTools"
   "Bash,Edit,Write,Read,Glob,Grep,WebSearch,WebFetch,Task,TodoWrite"])

(def harness
  (cc/claude-harness {:system-prompt-mode :replace
                      :max-turns          3
                      :model              "haiku"
                      :dir                "/tmp/karc"
                      :timeout-ms         90000
                      :extra-args         no-tools}))

(println "=== run-authored: an agent writes a karcarthy flow as EDN ===")
(let [designer (k/agent "designer" "You design karcarthy orchestration flows.")
      {:keys [flow result]} (self/run-authored harness designer
                                               "Answer concisely: what is a monad?")]
  (println "AUTHORED FLOW:" (pr-str flow))
  (println "RAN ->" (:text result)))

(println "\n=== evolve: an agent edits its own instructions at runtime ===")
(let [poet (k/agent "poet" "You are a mediocre poet who writes one bland line.")
      r    (o/run-flow harness (self/evolve poet :max-rounds 3)
                       "Patch yourself into an expert minimalist poet, then write ONE line about Lisp.")]
  (println "ROUNDS:" (:rounds r) "| PATCHES:" (count (:patches r)))
  (println "EVOLVED INSTRUCTIONS:" (:instructions (:evolved r)))
  (println "ANSWER:" (:text r)))

(shutdown-agents)
