(ns karcarthy.core
  "karcarthy: homoiconic agent orchestration for Clojure.

  Agents, tools, handoffs and workflows are plain Clojure maps (EDN). They are
  values, so you build, transform, inspect and serialize them with ordinary
  Clojure.

  karcarthy does not implement the inner agent loop (model calls and tool
  execution). It delegates that to an Agent SDK, coding-agent CLI, command
  process, or test adapter, and concentrates on coordinating many agents over
  it."
  ;; `agent` deliberately shadows clojure.core/agent (the STM primitive); this
  ;; is an agent library. Use `clojure.core/agent` if you need the original.
  (:refer-clojure :exclude [agent])
  (:require [clojure.spec.alpha :as s]))

;; ===========================================================================
;; Data model
;;
;; Every karcarthy entity is a map tagged with `:karcarthy/type`. We use plain,
;; unqualified keys (`:name`, `:instructions`, ...) so the maps are pleasant to
;; write and read by hand; specs below validate them with `:req-un`/`:opt-un`.
;; ===========================================================================

(s/def ::name        (s/and string? seq))
(s/def ::instructions string?)
(s/def ::model       (s/nilable string?))
(s/def ::tools       (s/coll-of string? :kind vector?))
(s/def ::handoffs    (s/coll-of string? :kind vector?))
(s/def ::adapter     keyword?)   ; id into an adapter registry (optional)
(s/def ::runner      keyword?)   ; legacy alias for ::adapter
(s/def ::harness     keyword?)   ; deprecated alias for ::adapter

(s/def ::agent
  (s/keys :req-un [::name ::instructions]
          :opt-un [::model ::tools ::handoffs ::adapter ::runner ::harness]))

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
  [name instructions & {:keys [model tools handoffs adapter runner harness]}]
  (cond-> {:karcarthy/type :agent
           :name           name
           :instructions   instructions}
    model    (assoc :model model)
    tools    (assoc :tools (vec tools))
    handoffs (assoc :handoffs (vec handoffs))
    adapter  (assoc :adapter adapter)
    runner   (assoc :runner runner)
    harness  (assoc :harness harness)))

(defn agent?
  "True if `x` is a karcarthy agent value (well-formed)."
  [x]
  (and (map? x) (= :agent (:karcarthy/type x)) (s/valid? ::agent x)))

(defn explain-agent
  "Return a human-readable validation message for `x`, or nil if it is valid."
  [x]
  (when-not (s/valid? ::agent x)
    (s/explain-str ::agent x)))

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

;; ===========================================================================
;; Results
;;
;; Running an agent yields a *result map*. Keeping a single, well-known shape
;; lets orchestration combinators treat every adapter uniformly.
;; ===========================================================================

(defn result
  "Construct a result map, defaulting `:ok?` to true.

    {:karcarthy/type :result
     :ok?   true
     :agent \"researcher\"      ; name of the agent that produced it
     :text  \"...final reply...\"
     :raw   <adapter-specific payload>}"
  [m]
  (merge {:karcarthy/type :result :ok? true} m))

(defn ok?
  "True if `result` represents a successful run."
  [result]
  (boolean (:ok? result)))

;; ===========================================================================
;; Adapter implementation protocol
;;
;; An adapter runs a *single* agent's model<->tool loop to completion. The
;; protocol name is still `Runner`, but public docs should describe the concrete
;; systems: Agent SDKs, coding-agent CLIs, command processes, and mocks.
;; ===========================================================================

(defprotocol Runner
  "Implementation protocol for adapters that run one agent to completion.

  `-run` receives a validated agent map, a prompt string, and an options map,
  and returns a result map (see `result`). Prefer calling `run-agent`, which
  validates first."
  (-run [runner agent prompt opts]))

(def Harness
  "Deprecated alias for the implementation protocol."
  Runner)

(def Adapter
  "Alias for the implementation protocol. Prefer adapter terminology in public
  docs; the protocol name remains `Runner` for now because this is still the
  existing implementation boundary."
  Runner)

(defn- adapter-id [agent]
  (or (:adapter agent) (:runner agent) (:harness agent) :default))

(defn resolve-runner
  "Legacy name for `resolve-adapter`. Pick the adapter to run `agent` with.
  The first argument is either a single adapter or a registry map {id -> adapter}.
  The agent's `:adapter` id selects one, falling back to legacy keys, then
  `:default`.

    (resolve-runner {:claude cc :default mk} (agent \"a\" \"i\" :adapter :claude))
    ;=> cc"
  [adapter agent]
  (cond
    (satisfies? Runner adapter) adapter
    (map? adapter) (let [id (adapter-id agent)]
                    (or (get adapter id)
                        (get adapter :default)
                        (throw (ex-info (str "no adapter registered for " (pr-str id))
                                        {:adapter-id id
                                         :runner-id id
                                         :harness-id id
                                         :registered (vec (keys adapter))}))))
    :else (throw (ex-info "adapter must be a single adapter or a registry map {id -> adapter}"
                          {:got adapter}))))

(defn resolve-adapter
  "Pick the adapter to run `agent` with. `adapter` is either a single adapter or
  a registry map {id -> adapter}; the agent's `:adapter` id selects from the
  registry, falling back to legacy `:runner` and `:harness` keys."
  [adapter agent]
  (resolve-runner adapter agent))

(defn resolve-harness
  "Deprecated alias for `resolve-runner`; accepts the same inputs."
  [harness agent]
  (resolve-runner harness agent))

(defn run-agent
  "Validate `agent`, then run it on `prompt`. `adapter` is either a single
  adapter or a registry map {id -> adapter} (see `resolve-adapter`). Returns a result map;
  throws `ExceptionInfo` if the agent is malformed."
  ([adapter agent prompt] (run-agent adapter agent prompt {}))
  ([adapter agent prompt opts]
   (when-let [msg (explain-agent agent)]
     (throw (ex-info (str "Invalid agent: " msg)
                     {:agent agent :problems (s/explain-data ::agent agent)})))
   (-run (resolve-adapter adapter agent) agent prompt opts)))

;; ===========================================================================
;; Mock adapter
;;
;; A fully offline adapter for tests, demos, and developing orchestration logic
;; without burning tokens or needing network access.
;; ===========================================================================

(defn mock-adapter
  "An offline adapter. `respond` is a fn of {:agent :prompt :opts} -> String
  (the agent's final reply). With no args it echoes the prompt, tagged with the
  agent name, which is handy for asserting how orchestration routes work.

    (run-agent (mock-adapter) (agent \"echo\" \"e\") \"hi\")
    ;=> {:karcarthy/type :result :ok? true :agent \"echo\" :text \"[echo] hi\" ...}"
  ([] (mock-adapter (fn [{:keys [agent prompt]}]
                      (str "[" (:name agent) "] " prompt))))
  ([respond]
   (reify Runner
     (-run [_ agent prompt opts]
       (result {:agent (:name agent)
                :text  (respond {:agent agent :prompt prompt :opts opts})
                :raw   {:adapter :mock}})))))

(defn mock-runner
  "Legacy alias for `mock-adapter`."
  ([] (mock-adapter))
  ([respond] (mock-adapter respond)))

(defn mock-harness
  "Deprecated alias for `mock-adapter`."
  ([] (mock-adapter))
  ([respond] (mock-adapter respond)))
