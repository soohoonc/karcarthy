(ns karcarthy.core
  "karcarthy: homoiconic agent orchestration for Clojure.

  Agents, tools, handoffs and workflows are plain Clojure maps (EDN). They are
  values, so you build, transform, inspect and serialize them with ordinary
  Clojure.

  karcarthy does not implement the inner agent loop (model calls and tool
  execution). It delegates that to a *runner*, such as the `claude -p` CLI, and
  concentrates on coordinating many agents over it. Runner adapters live in
  `karcarthy.runner.*`."
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
(s/def ::runner      keyword?)   ; id into a runner registry (optional)
(s/def ::harness     keyword?)   ; deprecated alias for ::runner

(s/def ::agent
  (s/keys :req-un [::name ::instructions]
          :opt-un [::model ::tools ::handoffs ::runner ::harness]))

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
  [name instructions & {:keys [model tools handoffs runner harness]}]
  (cond-> {:karcarthy/type :agent
           :name           name
           :instructions   instructions}
    model    (assoc :model model)
    tools    (assoc :tools (vec tools))
    handoffs (assoc :handoffs (vec handoffs))
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
;; Runner protocol
;;
;; A runner runs a *single* agent's model<->tool loop to completion. This is
;; the boundary between karcarthy (orchestration) and the underlying agent runner
;; (Claude Agent SDK, OpenAI Agents SDK, a mock, ...).
;; ===========================================================================

(defprotocol Runner
  "Runs one agent to completion.

  `-run` receives a validated agent map, a prompt string, and an options map,
  and returns a result map (see `result`). Implementations live in
  `karcarthy.runner.*`. Prefer calling `run-agent`, which validates first."
  (-run [runner agent prompt opts]))

(def Harness
  "Deprecated alias for `Runner`. Use `Runner` in new code."
  Runner)

(defn- runner-id [agent]
  (or (:runner agent) (:harness agent) :default))

(defn resolve-runner
  "Pick the runner to run `agent` with. `runner` is either a Runner directly,
  or a registry map {id -> Runner} - in which case the agent's `:runner` id
  selects one, falling back to `:harness` for compatibility, then `:default`.

    (resolve-runner {:claude cc :default mk} (agent \"a\" \"i\" :runner :claude))
    ;=> cc"
  [runner agent]
  (cond
    (satisfies? Runner runner) runner
    (map? runner) (let [id (runner-id agent)]
                    (or (get runner id)
                        (get runner :default)
                        (throw (ex-info (str "no runner registered for " (pr-str id))
                                        {:runner-id id
                                         :harness-id id
                                         :registered (vec (keys runner))}))))
    :else (throw (ex-info "runner must be a Runner or a registry map {id -> Runner}"
                          {:got runner}))))

(defn resolve-harness
  "Deprecated alias for `resolve-runner`; accepts the same inputs."
  [harness agent]
  (resolve-runner harness agent))

(defn run-agent
  "Validate `agent`, then run it on `prompt`. `runner` is either a Runner or a
  registry map {id -> Runner} (see `resolve-runner`). Returns a result map;
  throws `ExceptionInfo` if the agent is malformed."
  ([runner agent prompt] (run-agent runner agent prompt {}))
  ([runner agent prompt opts]
   (when-let [msg (explain-agent agent)]
     (throw (ex-info (str "Invalid agent: " msg)
                     {:agent agent :problems (s/explain-data ::agent agent)})))
   (-run (resolve-runner runner agent) agent prompt opts)))

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
                :raw   {:runner :mock :harness :mock}})))))

(defn mock-harness
  "Deprecated alias for `mock-runner`."
  ([] (mock-runner))
  ([respond] (mock-runner respond)))
