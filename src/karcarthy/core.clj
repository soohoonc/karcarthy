(ns karcarthy.core
  "karcarthy: homoiconic agent orchestration for Clojure.

  Agents, tools, and workflows are plain Clojure maps (EDN). They are
  values, so you build, transform, inspect and serialize them with ordinary
  Clojure.

  karcarthy does not implement the inner agent loop (model calls and tool
  execution). It delegates that to an Agent SDK, coding-agent CLI, Clojure
  function, subprocess, shell command, or test runner, and concentrates on
  coordinating many agents over it."
  ;; `agent` deliberately shadows clojure.core/agent (the STM primitive); this
  ;; is an agent library. Use `clojure.core/agent` if you need the original.
  (:refer-clojure :exclude [agent])
  (:require [clojure.spec.alpha :as s])
  (:import [java.util UUID]))

;; ===========================================================================
;; Data model
;;
;; Every karcarthy entity is a map tagged with `:karcarthy/type`. We use plain,
;; unqualified keys (`:name`, `:instructions`, ...) so the maps are pleasant to
;; write and read by hand; specs below validate them with `:req-un`/`:opt-un`.
;; ===========================================================================

(s/def ::name        (s/and string? seq))
(s/def ::instructions string?)
(s/def ::description string?)
(s/def ::model       (s/nilable string?))
(s/def ::tools       (s/coll-of string? :kind vector?))
(s/def ::disallowed-tools (s/coll-of string? :kind vector?))
(s/def ::runner      keyword?)   ; id into a runner registry (optional)
(s/def ::permission-mode any?)
(s/def ::sandbox-mode any?)
(s/def ::mcp-servers any?)
(s/def ::max-turns pos-int?)
(s/def ::skills (s/coll-of string? :kind vector?))
(s/def ::initial-prompt string?)
(s/def ::memory any?)
(s/def ::effort any?)
(s/def ::reasoning-effort any?)
(s/def ::background? boolean?)
(s/def ::isolation any?)
(s/def ::color any?)
(s/def ::nicknames (s/coll-of string? :kind vector?))
(s/def ::hooks any?)
(s/def ::config map?)

(s/def ::agent
  (s/keys :req-un [::name ::instructions]
          :opt-un [::model ::tools ::runner]))

(s/def ::subagent
  (s/keys :req-un [::name ::description ::instructions]
          :opt-un [::model ::tools ::disallowed-tools
                   ::permission-mode ::sandbox-mode ::mcp-servers
                   ::max-turns ::skills ::initial-prompt ::memory
                   ::effort ::reasoning-effort ::background? ::isolation
                   ::color ::nicknames ::hooks ::config]))

(defn agent
  "Build an agent value. `name` and `instructions` are required; the rest are
  optional kwargs merged in when present.

    (agent \"researcher\" \"Research questions thoroughly.\"
           :model \"sonnet\"
           :tools [\"WebSearch\" \"WebFetch\"])
    ;=> {:karcarthy/type :agent
    ;    :name \"researcher\"
    ;    :instructions \"Research questions thoroughly.\"
    ;    :model \"sonnet\"
    ;    :tools [\"WebSearch\" \"WebFetch\"]}"
  [name instructions & {:keys [model tools runner]}]
  (cond-> {:karcarthy/type :agent
           :name           name
           :instructions   instructions}
    model    (assoc :model model)
    tools    (assoc :tools (vec tools))
    runner  (assoc :runner runner)))

(defn agent?
  "True if `x` is a karcarthy agent value (well-formed)."
  [x]
  (and (map? x) (= :agent (:karcarthy/type x)) (s/valid? ::agent x)))

(defn subagent
  "Build a runner-native subagent definition.

  A subagent is not a workflow node. It is configuration passed to runners that
  support native delegation, such as Claude Code subagents or OpenAI Agents SDK
  handoffs.

    (subagent \"security-reviewer\"
              \"Use for auth, secrets, and permission review.\"
              \"Review like a security owner and return concrete findings.\"
              :tools [\"Read\" \"Grep\" \"Glob\"]
              :model \"sonnet\")"
  [name description instructions & {:keys [model tools disallowed-tools
                                           permission-mode sandbox-mode
                                           mcp-servers max-turns skills
                                           initial-prompt memory effort
                                           reasoning-effort background?
                                           isolation color nicknames hooks
                                           config]
                                    :as opts}]
  (when-let [unknown (seq (remove #{:model :tools :disallowed-tools
                                    :permission-mode :sandbox-mode
                                    :mcp-servers :max-turns :skills
                                    :initial-prompt :memory :effort
                                    :reasoning-effort :background?
                                    :isolation :color :nicknames :hooks
                                    :config}
                                  (keys opts)))]
    (throw (ex-info "subagent contains unknown options"
                    {:unknown (vec unknown)})))
  (cond-> {:karcarthy/type :subagent
           :name name
           :description description
           :instructions instructions}
    model (assoc :model model)
    tools (assoc :tools (vec tools))
    disallowed-tools (assoc :disallowed-tools (vec disallowed-tools))
    permission-mode (assoc :permission-mode permission-mode)
    sandbox-mode (assoc :sandbox-mode sandbox-mode)
    mcp-servers (assoc :mcp-servers mcp-servers)
    max-turns (assoc :max-turns max-turns)
    skills (assoc :skills (vec skills))
    initial-prompt (assoc :initial-prompt initial-prompt)
    memory (assoc :memory memory)
    effort (assoc :effort effort)
    reasoning-effort (assoc :reasoning-effort reasoning-effort)
    (contains? opts :background?) (assoc :background? (boolean background?))
    isolation (assoc :isolation isolation)
    color (assoc :color color)
    nicknames (assoc :nicknames (vec nicknames))
    hooks (assoc :hooks hooks)
    config (assoc :config config)))

(defn subagent?
  "True if `x` is a karcarthy subagent value (well-formed)."
  [x]
  (and (map? x) (= :subagent (:karcarthy/type x)) (s/valid? ::subagent x)))

(defn explain-agent
  "Return a human-readable validation message for `x`, or nil if it is valid."
  [x]
  (when-not (s/valid? ::agent x)
    (s/explain-str ::agent x)))

(defn explain-subagent
  "Return a human-readable validation message for `x`, or nil if it is valid."
  [x]
  (when-not (s/valid? ::subagent x)
    (s/explain-str ::subagent x)))

(defmacro defagent
  "Define a var holding an agent map. The symbol's name becomes the agent's
  :name, and `instructions` (a string literal) becomes both the agent's
  instructions and the var's docstring. Remaining args are the optional kwargs
  of `agent`.

    (defagent researcher
      \"Research questions thoroughly and cite sources.\"
      :model \"sonnet\"
      :tools [\"WebSearch\" \"WebFetch\"])

    researcher        ;=> {:karcarthy/type :agent :name \"researcher\" ...}
    (:doc (meta #'researcher))  ;=> \"Research questions thoroughly...\""
  [sym instructions & opts]
  (when-not (string? instructions)
    (throw (ex-info "defagent: instructions must be a string literal"
                    {:sym sym :instructions instructions})))
  `(def ~(vary-meta sym assoc :doc instructions)
     (agent ~(name sym) ~instructions ~@opts)))

(defmacro defsubagent
  "Define a var holding a subagent map. The symbol's name becomes the
  subagent's :name, `description` guides delegation, and `instructions` becomes
  the runner-native prompt/instructions."
  [sym description instructions & opts]
  (when-not (string? description)
    (throw (ex-info "defsubagent: description must be a string literal"
                    {:sym sym :description description})))
  (when-not (string? instructions)
    (throw (ex-info "defsubagent: instructions must be a string literal"
                    {:sym sym :instructions instructions})))
  `(def ~(vary-meta sym assoc :doc instructions)
     (subagent ~(name sym) ~description ~instructions ~@opts)))

;; ===========================================================================
;; Results
;;
;; Running an agent yields a *result map*. Keeping a single, well-known shape
;; lets orchestration combinators treat every runner uniformly.
;; ===========================================================================

(defn result
  "Construct a result map, defaulting `:ok?` to true.

    {:karcarthy/type :result
     :ok?   true
     :agent \"researcher\"      ; name of the agent that produced it
     :text  \"...final reply...\"
     :raw   <runner-specific payload>}"
  [m]
  (merge {:karcarthy/type :result :ok? true} m))

(defn ok?
  "True if `result` represents a successful run."
  [result]
  (boolean (:ok? result)))

;; ===========================================================================
;; Runner implementation protocol
;;
;; A runner runs a *single* agent's model<->tool loop to completion. The
;; concrete implementations can be Agent SDKs, coding-agent CLIs, Clojure
;; functions, subprocesses, shell commands, or mocks.
;; ===========================================================================

(defprotocol Runner
  "Implementation protocol for runners that run one agent to completion.

  `-run` receives a validated agent map, a prompt string, and an options map,
  and returns a result map (see `result`). Prefer calling `run-agent`, which
  validates first."
  (-run [runner agent prompt opts]))

(defn- runner-key [agent]
  (or (:runner agent) :default))

(defn resolve-runner
  "Pick the runner to run `agent` with. `runner` is either a single runner or
  a registry map {id -> runner}; the agent's `:runner` id selects from the
  registry."
  [runner agent]
  (cond
    (satisfies? Runner runner) runner
    (map? runner) (let [id (runner-key agent)]
                    (or (get runner id)
                        (get runner :default)
                        (throw (ex-info (str "no runner registered for " (pr-str id))
                                        {:runner-key id
                                         :registered (vec (keys runner))}))))
    :else (throw (ex-info "runner must be a single runner or a registry map {id -> runner}"
                          {:got runner}))))

(defn- span-id [] (str (UUID/randomUUID)))

(defn- now-ms [] (System/currentTimeMillis))

(defn- duration-ms [started-ns]
  (/ (double (- (System/nanoTime) started-ns)) 1000000.0))

(defn- observe!
  [opts event]
  (when-let [observe (:observe opts)]
    (try
      (observe event)
      (catch Throwable _ nil))))

(defn- agent-event
  [phase span-id parent-span-id agent attrs]
  (cond-> {:karcarthy/type :event
           :event          phase
           :kind           :agent
           :name           "karcarthy.agent"
           :span/kind      :internal
           :span/id        span-id
           :time-ms        (now-ms)
           :path           (:karcarthy/path attrs)
           :attributes     (cond-> {"karcarthy.kind"       "agent"
                                    "karcarthy.agent.name" (:name agent)
                                    "karcarthy.runner.id" (name (runner-key agent))}
                             (:karcarthy/path attrs)
                             (assoc "karcarthy.path" (pr-str (:karcarthy/path attrs)))
                             (:model agent) (assoc "karcarthy.agent.model" (:model agent))
                             (:tools agent) (assoc "karcarthy.agent.tools.count" (count (:tools agent))))}
    parent-span-id (assoc :parent/span-id parent-span-id)
    (:duration-ms attrs) (assoc :duration-ms (:duration-ms attrs))
    (contains? attrs :ok?) (assoc :ok? (:ok? attrs))
    (:error attrs) (assoc :error (:error attrs))))

(defn run-agent
  "Validate `agent`, then run it on `prompt`. `runner` is either a single
  runner or a registry map {id -> runner} (see `resolve-runner`). Returns a result map;
  throws `ExceptionInfo` if the agent is malformed."
  ([runner agent prompt] (run-agent runner agent prompt {}))
  ([runner agent prompt opts]
   (when-let [msg (explain-agent agent)]
     (throw (ex-info (str "Invalid agent: " msg)
                     {:agent agent :problems (s/explain-data ::agent agent)})))
   (if-not (:observe opts)
     (-run (resolve-runner runner agent) agent prompt opts)
     (let [span-id        (span-id)
           parent-span-id (:karcarthy/parent-span-id opts)
           started-ns     (System/nanoTime)]
       (observe! opts (agent-event :start span-id parent-span-id agent opts))
       (try
         (let [result (-run (resolve-runner runner agent) agent prompt opts)]
           (observe! opts (agent-event :finish span-id parent-span-id agent
                                       (assoc opts
                                              :duration-ms (duration-ms started-ns)
                                              :ok? (ok? result))))
           result)
         (catch Throwable t
           (observe! opts (agent-event :error span-id parent-span-id agent
                                       (assoc opts
                                              :duration-ms (duration-ms started-ns)
                                              :ok? false
                                              :error (or (.getMessage t) (str t)))))
           (throw t)))))))

;; ===========================================================================
;; Mock runner
;;
;; A fully offline runner for tests, demos, and developing orchestration logic
;; without burning tokens or needing network access.
;; ===========================================================================

(defn mock-runner
  "An offline runner. `respond` is a fn of {:agent :prompt :opts} -> String
  (the agent's final reply). With no args it echoes the prompt, tagged with the
  agent name, which is handy for asserting how orchestration routes work.

    (run-agent (mock-runner) (agent \"echo\" \"e\") \"hi\")
    ;=> {:karcarthy/type :result :ok? true :agent \"echo\" :text \"[echo] hi\" ...}"
  ([] (mock-runner (fn [{:keys [agent prompt]}]
                      (str "[" (:name agent) "] " prompt))))
  ([respond]
   (reify Runner
     (-run [_ agent prompt opts]
       (result {:agent (:name agent)
                :text  (respond {:agent agent :prompt prompt :opts opts})
                :raw   {:runner :mock}})))))

(defn fn-runner
  "Build a runner from a Clojure function.

  By default, `f` receives only the flowing input string. With `{:context? true}`,
  `f` receives `{:agent agent :input input :opts opts}`. It may return a plain
  string, a partial result map, or a full result map."
  ([f] (fn-runner f {}))
  ([f {:keys [context? context] :as config}]
   (when-let [unknown (seq (remove #{:context? :context} (keys config)))]
     (throw (ex-info "fn-runner contains unknown options"
                     {:unknown (vec unknown)
                      :supported [:context?]})))
   (let [context? (boolean (or context? context))]
     (reify Runner
       (-run [_ agent input opts]
         (let [reply (if context?
                       (f {:agent agent :input input :opts opts})
                       (f input))]
           (cond
             (and (map? reply) (= :result (:karcarthy/type reply)))
             reply

             (map? reply)
             (result (cond-> reply
                       (not (contains? reply :agent)) (assoc :agent (:name agent))
                       (not (contains? reply :raw)) (assoc :raw {:runner :fn})))

             :else
             (result {:agent (:name agent)
                      :text  (str reply)
                      :raw   {:runner :fn}}))))))))
