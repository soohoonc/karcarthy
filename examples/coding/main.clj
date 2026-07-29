(ns example.coding
  "A repository-scoped, interactive coding harness."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [karcarthy :as k]))

(def base-instructions
  (str
   "You are a coding agent working in a local repository. Be precise, safe, "
   "and helpful. Keep your communication concise, direct, and friendly.\n\n"
   "Keep working until the user's request is completely resolved. Use the "
   "available tools to resolve it to the best of your ability. Do not guess or "
   "make up an answer. Follow repository instructions, including applicable "
   "AGENTS.md files.\n\n"
   "Fix problems at the root cause when possible. Avoid unneeded complexity. "
   "Do not fix unrelated bugs or overwrite changes you did not make. Keep "
   "changes minimal, focused, and consistent with the existing codebase.\n\n"
   "Run relevant tests or checks when the repository supports them. Start with "
   "focused validation, then run broader checks as confidence grows. If a check "
   "cannot run, say why.\n\n"
   "In the final answer, concisely state what changed, what you verified, and "
   "any remaining issue."))

(def run-limits
  {:model-calls 32
   :evals 4
   :depth 3
   :concurrency 8
   :deadline-ms 600000})

(defn model-id [selected]
  (or selected
      (System/getenv "KARCARTHY_OPENAI_MODEL")
      "gpt-5.6"))

(defn- approval-policies [approval]
  (case approval
    :ask {:read :never
          :search :never
          :write :always
          :edit :always
          :bash :always}
    :never :never
    (throw (ex-info "approval must be ask or never"
                    {:approval approval}))))

(defn coding-agent [{:keys [cwd dynamic? model] :as options}]
  (k/agent
   {:name "coding-agent"
    :description "Inspect, modify, and verify a local repository."
    :model (or model
               {:transport :responses
                :provider :openai
                :id (model-id (:model-id options))
                :reasoning {:effort :medium
                            :summary :auto}
                :timeout-ms 300000})
    :instructions base-instructions
    :tools (cond-> (k/local-tools
                    {:cwd cwd
                     :needs-approval
                     (approval-policies (or (:approval options) :ask))})
             dynamic? (conj k/eval))
    :max-turns 24}))

(defn coding-session
  "Create a process-local, multi-turn coding session.

  `:cwd` defaults to the current directory. `:approval` is `:ask` or `:never`.
  Set `:dynamic? true` to add the full-trust `k/eval` capability. `:model` may
  supply a model configuration for embedding or tests."
  [options]
  (let [options (merge {:cwd (System/getProperty "user.dir")
                        :dynamic? false
                        :approval :ask}
                       options)
        cwd (.getCanonicalPath (io/file (:cwd options)))
        options (assoc options :cwd cwd)
        agent (coding-agent options)]
    {:karcarthy/type :coding-session
     :cwd cwd
     :dynamic? (boolean (:dynamic? options))
     :approval (:approval options)
     :model-id (or (:model-id options)
                   (get-in agent [:model :id])
                   "custom")
     :agent agent
     :session (or (:session options) (k/session))
     :last-run (atom nil)}))

(defn ask!
  "Run one conversational turn in a coding session.

  Optional Run configuration accepts `:on-event` and `:approval`."
  ([coding input]
   (ask! coding input {}))
  ([coding input options]
   (let [run (k/run! (:agent coding) input
                     (merge {:session (:session coding)
                             :limits run-limits}
                            (select-keys options [:on-event :approval])))]
     (reset! (:last-run coding) run)
     run)))

(defn clear-session! [coding]
  (k/clear-session! (:session coding))
  (reset! (:last-run coding) nil)
  coding)

(def usage
  (str
   "Usage: clojure -M:examples coding "
   "[cwd=<directory>] [dynamic=true|false] "
   "[approval=ask|never] [model=<model-id>]"))

(defn- boolean-option [name value]
  (case value
    "true" true
    "false" false
    (throw
     (ex-info (str name " must be true or false")
              {:option name :value value}))))

(defn- approval-option [value]
  (case value
    "ask" :ask
    "never" :never
    (throw
     (ex-info "approval must be ask or never"
              {:option "approval" :value value}))))

(defn parse-options [args]
  (reduce
   (fn [options raw]
     (let [[name value] (str/split raw #"=" 2)]
       (when (or (str/blank? name) (str/blank? value))
         (throw (ex-info "Coding options must use name=value"
                         {:option raw})))
       (case name
         "cwd" (assoc options :cwd value)
         "dynamic" (assoc options :dynamic? (boolean-option name value))
         "approval" (assoc options :approval (approval-option value))
         "model" (assoc options :model-id value)
         (throw (ex-info (str "Unknown coding option: " name)
                         {:option name})))))
   {:cwd (System/getProperty "user.dir")
    :dynamic? false
    :approval :ask}
   args))

(defn- input-value [input key]
  (or (get input key) (get input (name key))))

(defn- tool-summary [tool input]
  (case (:name tool)
    "bash" (input-value input :command)
    "search" (str (pr-str (input-value input :pattern))
                  " in " (or (input-value input :path) "."))
    "eval" "dynamic Clojure program"
    (or (input-value input :path) (pr-str input))))

(defn terminal-approval
  "Create an approval handler that serializes prompts on the supplied streams."
  ([]
   (terminal-approval *in* *out*))
  ([reader writer]
   (let [prompt-lock (Object.)]
     (fn [{:keys [tool input]}]
       (locking prompt-lock
         (binding [*in* reader
                   *out* writer]
           (println)
           (println (str "approval> " (:name tool) " "
                         (tool-summary tool input)))
           (print "Allow this tool call? [y/N] ")
           (flush)
           (contains? #{"y" "yes"}
                      (some-> (read-line) str/trim str/lower-case))))))))

(defn- finish-section! [state]
  (when (or (:reasoning? @state) (:answer? @state))
    (println))
  (swap! state assoc :reasoning? false :answer? false))

(defn terminal-events
  "Create a compact event renderer for streamed summaries, answers, and tools."
  ([]
   (terminal-events *out*))
  ([writer]
   (let [state (atom {:reasoning? false :answer? false :saw-text? false})]
     {:state state
      :on-event
      (fn [event]
        (binding [*out* writer]
          (locking state
            (case (:type event)
              :model/reasoning-summary-delta
              (when (zero? (long (or (:depth event) 0)))
                (when-not (:reasoning? @state)
                  (finish-section! state)
                  (print "thinking> ")
                  (swap! state assoc :reasoning? true))
                (print (:delta event))
                (flush))

              :model/text-delta
              (when (zero? (long (or (:depth event) 0)))
                (when-not (:answer? @state)
                  (finish-section! state)
                  (print "agent> ")
                  (swap! state assoc :answer? true :saw-text? true))
                (print (:delta event))
                (flush))

              :model/completed
              (finish-section! state)

              :tool/approval-requested
              (finish-section! state)

              :tool/started
              (do
                (finish-section! state)
                (println (str "tool> " (:tool event) " "
                              (tool-summary {:name (:tool event)}
                                            (:input event)))))

              :agent/started
              (when (pos? (long (or (:depth event) 0)))
                (finish-section! state)
                (println (str "agent> started " (:agent event))))

              :eval/started
              (do
                (finish-section! state)
                (println "eval> running Clojure program"))

              nil))))
      :finish #(binding [*out* writer]
                 (locking state
                   (finish-section! state)))})))

(defn- print-status! [coding]
  (let [items (k/get-items (:session coding))
        run @(:last-run coding)]
    (println "repository:" (:cwd coding))
    (println "model:" (:model-id coding))
    (println "dynamic eval:" (if (:dynamic? coding) "on" "off"))
    (println "local tool approvals:" (name (:approval coding)))
    (println "conversation items:" (count items))
    (when run
      (println "last run:" (name (:status run))
               (str "· " (get-in run [:usage :model-calls] 0)
                    " model calls")))))

(defn- print-diff! [cwd]
  (let [{:keys [exit out err]}
        (shell/sh "git" "-C" cwd "diff" "--no-ext-diff" "HEAD" "--")]
    (cond
      (not (zero? exit)) (println "diff error>" (str/trim err))
      (str/blank? out) (println "No tracked changes.")
      :else (print out))))

(def help
  (str
   "/help    Show session commands\n"
   "/status  Show repository, model, capabilities, and last-run usage\n"
   "/diff    Show tracked changes against HEAD\n"
   "/clear   Clear this process-local conversation\n"
   "/quit    Exit"))

(defn coding! [options]
  (let [coding (coding-session options)
        approval (terminal-approval)]
    (println "karcarthy coding")
    (print-status! coding)
    (when (:dynamic? coding)
      (println
       "warning: eval is full-trust; local tool approvals do not cover its code"))
    (println "Type /help for session commands.")
    (loop []
      (print "\nyou> ")
      (flush)
      (when-let [input (read-line)]
        (case input
          "/quit" :done
          "/help" (do (println help) (recur))
          "/status" (do (print-status! coding) (recur))
          "/diff" (do (print-diff! (:cwd coding)) (recur))
          "/clear" (do
                     (clear-session! coding)
                     (println "Conversation cleared.")
                     (recur))
          (if (str/blank? input)
            (recur)
            (let [{:keys [state on-event finish]} (terminal-events)
                  run (ask! coding input
                            {:on-event on-event
                             :approval approval})]
              (finish)
              (if (= :completed (:status run))
                (when-not (:saw-text? @state)
                  (println "agent>" (:output run)))
                (println "error>" (get-in run [:error :message])))
              (recur))))))))

(defn -main [& args]
  (try
    (coding! (parse-options args))
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (println (ex-message error))
        (println usage))
      (System/exit 2))))
