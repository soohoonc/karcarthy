(ns karcarthy.session
  "Multi-turn conversations over a session-aware adapter.

  `converse` runs a sequence of prompts against one agent, threading the
  adapter's session id so each turn sees the prior context - i.e. memory. It
  works with any adapter that returns `:session-id` and honours a `:resume`
  option; others simply run the turns in sequence without shared context."
  (:require [karcarthy.core :as k]))

(defn converse
  "Run `prompts` as a multi-turn conversation with `agent`. Returns a vector of
  results, one per prompt, threading the session so each turn after the first
  resumes the previous one. `:opts` are passed to every turn."
  [adapter agent prompts & {:keys [opts] :or {opts {}}}]
  (loop [prompts (seq prompts), session nil, acc []]
    (if-not prompts
      acc
      (let [turn-opts (cond-> opts session (assoc :resume session))
            r         (k/run-agent adapter agent (first prompts) turn-opts)]
        (recur (next prompts) (or (:session-id r) session) (conj acc r))))))
