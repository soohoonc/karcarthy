;; Agents editing their own behavior - LIVE.
;;
;; Makes real, paid `claude -p` calls; run it intentionally. Needs the `claude`
;; CLI with valid auth on PATH.
;;
;;     clojure -M -e '(load-file "examples/clojure/evolve.clj")'
;;
;; Demonstrates `evolve`: an agent edits its own definition at runtime via EDN
;; patches, then acts with the new behavior.

(require '[karcarthy :as k]
         '[karcarthy.self :as self]
         '[karcarthy.orchestrate :as o])

(.mkdirs (java.io.File. "/tmp/karc"))

;; Lean sub-agents: system prompt = just the agent's instructions, tools off, so
;; the model answers directly rather than inheriting Claude Code's persona.
(def no-tools
  ["--disallowedTools"
   "Bash,Edit,Write,Read,Glob,Grep,WebSearch,WebFetch,Task,TodoWrite"])

(def runner
  (k/claude-runner {:system-prompt-mode :replace
                 :max-turns          3
                 :model              "haiku"
                 :dir                "/tmp/karc"
                 :timeout-ms         90000
                 :extra-args         no-tools}))

(println "=== evolve: an agent edits its own instructions at runtime ===")
(let [poet (k/agent {:name "poet"
                     :instructions "You are a mediocre poet who writes one bland line."})
      r    (k/run {:runner runner
                   :workflow (self/evolve poet :max-rounds 3)
                   :input "Patch yourself into an expert minimalist poet, then write ONE line about Lisp."})]
  (println "ROUNDS:" (:rounds r) "| PATCHES:" (count (:patches r)))
  (println "EVOLVED INSTRUCTIONS:" (:instructions (:evolved r)))
  (println "ANSWER:" (:text r)))

(shutdown-agents)
