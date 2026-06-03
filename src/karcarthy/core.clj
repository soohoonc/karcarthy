(ns karcarthy.core
  "karcarthy: homoiconic agent orchestration for Clojure.

  Agents, tools, and workflows are plain Clojure maps (EDN). They are
  values, so you build, transform, inspect and serialize them with ordinary
  Clojure.

  karcarthy does not implement the inner agent loop (model calls and tool
  execution). It delegates that to an Agent SDK, coding-agent CLI, command
  process, or test adapter, and concentrates on coordinating many agents over
  it."
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
(s/def ::model       (s/nilable string?))
(s/def ::tools       (s/coll-of string? :kind vector?))
(s/def ::adapter     keyword?)   ; id into an adapter registry (optional)

(s/def ::agent
  (s/keys :req-un [::name ::instructions]
          :opt-un [::model ::tools ::adapter]))

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
  [name instructions & {:keys [model tools adapter]}]
  (cond-> {:karcarthy/type :agent
           :name           name
           :instructions   instructions}
    model    (assoc :model model)
    tools    (assoc :tools (vec tools))
    adapter  (assoc :adapter adapter)))

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
;; concrete implementations can be Agent SDKs, coding-agent CLIs, command
;; processes, or mocks.
;; ===========================================================================

(defprotocol Adapter
  "Implementation protocol for adapters that run one agent to completion.

  `-run` receives a validated agent map, a prompt string, and an options map,
  and returns a result map (see `result`). Prefer calling `run-agent`, which
  validates first."
  (-run [adapter agent prompt opts]))

(defn- adapter-id [agent]
  (or (:adapter agent) :default))

(defn resolve-adapter
  "Pick the adapter to run `agent` with. `adapter` is either a single adapter or
  a registry map {id -> adapter}; the agent's `:adapter` id selects from the
  registry."
  [adapter agent]
  (cond
    (satisfies? Adapter adapter) adapter
    (map? adapter) (let [id (adapter-id agent)]
                    (or (get adapter id)
                        (get adapter :default)
                        (throw (ex-info (str "no adapter registered for " (pr-str id))
                                        {:adapter-id id
                                         :registered (vec (keys adapter))}))))
    :else (throw (ex-info "adapter must be a single adapter or a registry map {id -> adapter}"
                          {:got adapter}))))

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
                                    "karcarthy.adapter.id" (name (adapter-id agent))}
                             (:karcarthy/path attrs)
                             (assoc "karcarthy.path" (pr-str (:karcarthy/path attrs)))
                             (:model agent) (assoc "karcarthy.agent.model" (:model agent))
                             (:tools agent) (assoc "karcarthy.agent.tools.count" (count (:tools agent))))}
    parent-span-id (assoc :parent/span-id parent-span-id)
    (:duration-ms attrs) (assoc :duration-ms (:duration-ms attrs))
    (contains? attrs :ok?) (assoc :ok? (:ok? attrs))
    (:error attrs) (assoc :error (:error attrs))))

(defn run-agent
  "Validate `agent`, then run it on `prompt`. `adapter` is either a single
  adapter or a registry map {id -> adapter} (see `resolve-adapter`). Returns a result map;
  throws `ExceptionInfo` if the agent is malformed."
  ([adapter agent prompt] (run-agent adapter agent prompt {}))
  ([adapter agent prompt opts]
   (when-let [msg (explain-agent agent)]
     (throw (ex-info (str "Invalid agent: " msg)
                     {:agent agent :problems (s/explain-data ::agent agent)})))
   (if-not (:observe opts)
     (-run (resolve-adapter adapter agent) agent prompt opts)
     (let [span-id        (span-id)
           parent-span-id (:karcarthy/parent-span-id opts)
           started-ns     (System/nanoTime)]
       (observe! opts (agent-event :start span-id parent-span-id agent opts))
       (try
         (let [result (-run (resolve-adapter adapter agent) agent prompt opts)]
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
   (reify Adapter
     (-run [_ agent prompt opts]
       (result {:agent (:name agent)
                :text  (respond {:agent agent :prompt prompt :opts opts})
                :raw   {:adapter :mock}})))))
