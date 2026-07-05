(ns karcarthy.core
  "karcarthy: agent orchestration as data for Clojure, inspired by Lisp's
  homoiconicity.

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
  (:require [clojure.spec.alpha :as s]
            [karcarthy.observe :as obs]))

;; ---------------------------------------------------------------------------
;; Data model
;;
;; Every karcarthy entity is a map tagged with `:karcarthy/type`. We use plain,
;; unqualified keys (`:name`, `:instructions`, ...) so the maps are pleasant to
;; write and read by hand; specs below validate them with `:req-un`/`:opt-un`.
;; ---------------------------------------------------------------------------

(s/def ::name        (s/and string? seq))
(s/def ::instructions string?)
(s/def ::description string?)
(s/def ::model       (s/nilable string?))
(s/def ::tools       (s/coll-of string? :kind vector?))
(s/def ::disallowed-tools (s/coll-of string? :kind vector?))
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
          :opt-un [::description ::model ::tools ::config]))

(s/def ::subagent
  (s/keys :req-un [::name ::description ::instructions]
          :opt-un [::model ::tools ::disallowed-tools
                   ::permission-mode ::sandbox-mode ::mcp-servers
                   ::max-turns ::skills ::initial-prompt ::memory
                   ::effort ::reasoning-effort ::background? ::isolation
                   ::color ::nicknames ::hooks ::config]))

(declare agent?)

(def ^:private agent-keys
  #{:name :description :instructions :model :tools :config})

(defn reject-unknown!
  "Throw when `m` contains keys outside `supported`; otherwise return `m`.
  The shared guard behind every option-taking constructor."
  [label supported m]
  (when-let [unknown (seq (remove (set supported) (keys m)))]
    (throw (ex-info (str label " contains unknown options")
                    {:unknown (vec unknown)
                     :supported (vec supported)})))
  m)

(defn- normalize-agent
  [spec]
  (when-not (map? spec)
    (throw (ex-info "agent expects a map"
                    {:value spec
                     :supported '(agent {:name ... :instructions ...})})))
  (let [base (:from spec)]
    (when (and base (not (agent? base)))
      (throw (ex-info "agent :from expects an agent value"
                      {:from base})))
    (reject-unknown! "agent" (conj agent-keys :from) spec)
    (let [spec'  (cond-> spec
                   (some? (:tools spec))
                   (update :tools vec)

                   (and (contains? spec :tools) (nil? (:tools spec)))
                   (dissoc :tools))
          agent' (-> (merge base (dissoc spec' :from))
                     (assoc :karcarthy/type :agent))]
      (when-not (agent? agent')
        (throw (ex-info "agent spec is invalid"
                        {:agent agent'
                         :problems (s/explain-str ::agent agent')})))
      agent')))

(defn agent
  "Build an agent spec as plain EDN data.

    (agent {:name \"researcher\"
            :instructions \"Research questions thoroughly.\"
            :model \"sonnet\"
            :tools [\"WebSearch\" \"WebFetch\"]})

  `:model`, `:tools`, and `:config` are interpreted by the runner passed to
  `run` or `run-agent`.

  To derive a variant from an existing agent:

    (agent {:from reviewer :model \"gpt-5.2\"})"
  [spec]
  (normalize-agent spec))

(defn agent?
  "True if `x` is a karcarthy agent value (well-formed)."
  [x]
  (and (map? x)
       (= :agent (:karcarthy/type x))
       (every? (conj agent-keys :karcarthy/type) (keys x))
       (s/valid? ::agent x)))

(def subagent-option-keys
  "Option keys accepted by `subagent`, in the order they appear in the built
  map. The single source of truth for the option surface; `::subagent` above
  mirrors it for validation."
  [:model :tools :disallowed-tools :permission-mode :sandbox-mode
   :mcp-servers :max-turns :skills :initial-prompt :memory :effort
   :reasoning-effort :background? :isolation :color :nicknames :hooks
   :config])

(defn- normalize-subagent-opts
  [opts]
  (cond-> opts
    (contains? opts :tools)            (update :tools vec)
    (contains? opts :disallowed-tools) (update :disallowed-tools vec)
    (contains? opts :skills)           (update :skills vec)
    (contains? opts :nicknames)        (update :nicknames vec)
    (contains? opts :background?)      (update :background? boolean)))

(defn subagent
  "Build a runner-native subagent definition.

  A subagent is not a workflow node. It is configuration passed to runners that
  support native delegation, such as Claude Code subagents or OpenAI Agents SDK
  handoffs.

    (subagent \"security-reviewer\"
              \"Use for auth, secrets, and permission review.\"
              \"Review like a security owner and return concrete findings.\"
              :tools [\"Read\" \"Grep\" \"Glob\"]
              :model \"sonnet\")

  See `subagent-option-keys` in this namespace (and the `::subagent` spec) for
  the full option list."
  [name description instructions & {:as opts}]
  (reject-unknown! "subagent" subagent-option-keys (or opts {}))
  (let [opts      (normalize-subagent-opts (or opts {}))
        subagent' (into {:karcarthy/type :subagent
                         :name name
                         :description description
                         :instructions instructions}
                        (keep (fn [k]
                                (when (some? (get opts k))
                                  [k (get opts k)])))
                        subagent-option-keys)]
    (when-not (s/valid? ::subagent subagent')
      (throw (ex-info "subagent spec is invalid"
                      {:subagent subagent'
                       :problems (s/explain-str ::subagent subagent')})))
    subagent'))

(defn subagent?
  "True if `x` is a karcarthy subagent value (well-formed)."
  [x]
  (and (map? x) (= :subagent (:karcarthy/type x)) (s/valid? ::subagent x)))

(defn explain-agent
  "Return a human-readable validation message for `x`, or nil if it is valid."
  [x]
  (when-not (agent? x)
    (cond
      (not (map? x)) "agent must be a map"
      (not= :agent (:karcarthy/type x)) "agent must have :karcarthy/type :agent"
      :else (or (when-let [unknown (seq (remove (conj agent-keys :karcarthy/type)
                                                     (keys x)))]
                  (str "agent contains unknown keys: " (pr-str (vec unknown))))
                (s/explain-str ::agent x)))))

(defn explain-subagent
  "Return a human-readable validation message for `x`, or nil if it is valid."
  [x]
  (when-not (s/valid? ::subagent x)
    (s/explain-str ::subagent x)))

(defmacro defagent
  "Define a var holding an agent spec. The symbol's name becomes `:name` unless
  the spec explicitly provides one.

    (defagent researcher
      {:instructions \"Research questions thoroughly and cite sources.\"
       :model \"sonnet\"
       :tools [\"WebSearch\" \"WebFetch\"]})

    (defagent claude-researcher
      {:from researcher
       :model \"sonnet\"})"
  [sym spec]
  `(do
     (def ~sym
       (let [spec# ~spec]
         (agent (cond-> spec#
                  (and (map? spec#) (not (contains? spec# :name)))
                  (assoc :name ~(name sym))))))
     (alter-meta! (var ~sym) assoc :doc (:instructions ~sym))
     (var ~sym)))

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

;; ---------------------------------------------------------------------------
;; Results
;;
;; Running an agent yields a *result map*. Keeping a single, well-known shape
;; lets orchestration combinators treat every runner uniformly.
;; ---------------------------------------------------------------------------

(defn result
  "Construct a result map, defaulting `:ok?` to true.

    {:karcarthy/type :result
     :ok?   true
     :agent \"researcher\"      ; name of the agent that produced it
     :text  \"...final reply...\"
     :raw   <runner-specific payload>}"
  [m]
  (when-not (map? m)
    (throw (ex-info "result expects a map" {:value m})))
  (let [r (merge {:karcarthy/type :result :ok? true} m)]
    (when-not (= :result (:karcarthy/type r))
      (throw (ex-info "result :karcarthy/type must be :result" {:result r})))
    (when-not (boolean? (:ok? r))
      (throw (ex-info "result :ok? must be true or false" {:result r})))
    r))

(defn result?
  "True when `x` is a tagged result with a Boolean status."
  [x]
  (and (map? x)
       (= :result (:karcarthy/type x))
       (boolean? (:ok? x))))

(defn ok?
  "True if `result` represents a successful run."
  [result]
  (true? (:ok? result)))

(defn coerce-result
  "Coerce `reply` - a string, a partial result map, or a full result map -
  into a result map, filling absent keys from `defaults`. Internal glue shared
  by `fn-runner` and `karcarthy.orchestrate`'s `step` node."
  [reply defaults]
  (cond
    (and (map? reply) (= :result (:karcarthy/type reply)))
    (result reply)

    (map? reply)
    (result (merge defaults reply))

    :else
    (result (assoc defaults :text (str reply)))))

;; ---------------------------------------------------------------------------
;; Runner implementation protocol
;;
;; A runner runs a *single* agent's model<->tool loop to completion. The
;; concrete implementations can be Agent SDKs, coding-agent CLIs, Clojure
;; functions, subprocesses, shell commands, or mocks.
;; ---------------------------------------------------------------------------

(defprotocol Runner
  "Implementation protocol for runners that run one agent to completion.

  `-run` receives a validated agent map, a prompt string, and an options map,
  and returns a result map (see `result`). Prefer calling `run-agent`, which
  validates first."
  (-run [runner agent prompt options]))

(defn- runner!
  [runner]
  (if (satisfies? Runner runner)
    runner
    (throw (ex-info "runner must implement karcarthy.core/Runner"
                    {:got runner}))))

(defn- agent-event
  [phase span-id parent-span-id agent attrs]
  (obs/event phase span-id parent-span-id
             {:kind :agent
              :name "karcarthy.agent"
              :attributes (cond-> {"karcarthy.kind"       "agent"
                                   "karcarthy.agent.name" (:name agent)}
                            (:model agent) (assoc "karcarthy.agent.model" (:model agent))
                            (:tools agent) (assoc "karcarthy.agent.tools.count" (count (:tools agent))))}
             attrs))

(def ^:private interpreter-options
  [:karcarthy/scope :karcarthy/path :karcarthy/parent-span-id
   :max-concurrency :run-timeout-ms :cancel? :observe :edn-retries :state])

(defn ^:no-doc reject-tools!
  "Fail when a runner cannot enforce an agent's tool allowlist."
  [runner agent]
  (when (seq (:tools agent))
    (throw (ex-info (str (name runner) " runner does not support agent :tools")
                    {:runner runner :tools (:tools agent)}))))

(defn run-agent
  "Validate `agent`, then run it on `prompt` with `runner`. Returns a result map;
  throws `ExceptionInfo` if the agent is malformed."
  ([runner agent prompt] (run-agent runner agent prompt {}))
  ([runner agent prompt opts]
   (when-not (agent? agent)
     (let [msg (explain-agent agent)]
       (throw (ex-info (str "Invalid agent: " msg)
                       {:agent agent :problems (s/explain-data ::agent agent)}))))
   (let [runner (runner! runner)]
     (obs/with-span opts
       (fn [phase span-id parent-span-id attrs]
         (agent-event phase span-id parent-span-id agent attrs))
       (fn [_]
         (let [runner-opts (apply dissoc opts interpreter-options)
               r (-run runner agent prompt runner-opts)]
           (when-not (result? r)
             (throw (ex-info "runner returned an invalid result"
                             {:agent (:name agent) :result r})))
           r))))))

;; ---------------------------------------------------------------------------
;; Mock runner
;;
;; A fully offline runner for tests, demos, and developing orchestration logic
;; without burning tokens or needing network access.
;; ---------------------------------------------------------------------------

(defn mock-runner
  "An offline runner. `respond` is a fn of {:agent :prompt :options} -> String
  (the agent's final reply). With no args it echoes the prompt, tagged with the
  agent name, which is handy for asserting how orchestration routes work.

    (run-agent (mock-runner) (agent {:name \"echo\" :instructions \"e\"}) \"hi\")
    ;=> {:karcarthy/type :result :ok? true :agent \"echo\" :text \"[echo] hi\" ...}"
  ([] (mock-runner (fn [{:keys [agent prompt]}]
                      (str "[" (:name agent) "] " prompt))))
  ([respond]
   (reify Runner
     (-run [_ agent prompt opts]
       (result {:agent (:name agent)
                :text  (respond {:agent agent :prompt prompt :options opts})
                :raw   {:runner :mock}})))))

(defn fn-runner
  "Build a runner from a Clojure function.

  By default, `f` receives only the flowing input string. With `{:call? true}`,
  `f` receives `{:agent agent :input input :options options}`. It may return a plain
  string, a partial result map, or a full result map."
  ([f] (fn-runner f {}))
  ([f {:keys [call?] :as config}]
   (reject-unknown! "fn-runner" [:call?] config)
   (let [call? (boolean call?)]
     (reify Runner
       (-run [_ agent input opts]
         (let [reply (if call?
                       (f {:agent agent :input input :options opts})
                       (f input))]
           (coerce-result reply {:agent (:name agent)
                                 :raw   {:runner :fn}})))))))
