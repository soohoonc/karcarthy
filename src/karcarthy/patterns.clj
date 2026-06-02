(ns karcarthy.patterns
  "Small adapters that express familiar agent-orchestrator shapes as karcarthy
  workflow data.

  These are not compatibility shims for other frameworks. They are deliberately
  thin pattern helpers: crews, group chats, workflow agents, handoff routers, and
  finite state graphs are compiled back to ordinary karcarthy maps so they can be
  inspected, transformed, serialized, and run through any adapter."
  (:refer-clojure :exclude [agent])
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.otel :as otel]))

(defn- name-key [x]
  (cond
    (keyword? x) (name x)
    (string? x) x
    :else (str x)))

(defn- kind-key [x]
  (keyword (name-key x)))

(defn- require-agent [x label]
  (if (k/agent? x)
    x
    (throw (ex-info (str label " must be a karcarthy agent")
                    {:value x :explain (k/explain-agent x)}))))

(defn- require-workflows [xs label]
  (let [v (vec xs)]
    (when (empty? v)
      (throw (ex-info (str label " must not be empty") {:value xs})))
    v))

(defn- text-section [title value]
  (let [s (some-> value str str/trim)]
    (when (seq s)
      (str title ":\n" s))))

(defn task-agent
  "Return an agent whose role instructions are extended with a task assignment.

  This is the useful core of a CrewAI-style task: the base agent keeps its model,
  tools, adapter choice, and handoffs, while the task description becomes data in the
  instructions.

    (task-agent analyst \"Find risks\" :id :risk :expected-output \"3 bullets\")"
  ([task]
   (let [{:keys [agent id name description expected-output context]} task
         agent       (require-agent agent "task :agent")
         task-id     (or id name "task")
         task-name   (name-key task-id)
         description (or description
                         (throw (ex-info "task requires :description"
                                         {:task task})))
         instructions (str/join
                       "\n\n"
                       (remove nil?
                               [(:instructions agent)
                                (text-section "Task" description)
                                (text-section "Context" context)
                                (text-section "Expected output" expected-output)]))]
     (assoc agent
            :name (str (:name agent) "/" task-name)
            :instructions instructions)))
  ([agent description & opts]
   (task-agent (merge {:agent agent :description description}
                      (apply hash-map opts)))))

(defn crew
  "Compile a CrewAI-style crew into workflow data.

  `tasks` is a sequence of maps accepted by `task-agent`.

  Options:
  - `:process :sequential` threads tasks with `chain` (the default).
  - `:process :parallel` fans tasks out with `parallel`.
  - `:process :hierarchical` uses `orchestrate`; pass `:manager` as the planner
    and optionally `:worker` as the worker workflow. Without `:worker`, the
    task chain becomes the worker.
  - `:gather`, `:synthesize`, and `:max-concurrency` are forwarded to the
    underlying composite node where they apply."
  [tasks & {:keys [process manager worker gather synthesize max-concurrency]
            :or   {process :sequential}}]
  (let [steps   (mapv task-agent tasks)
        process (kind-key process)]
    (when (and (empty? steps) (not= :hierarchical process))
      (throw (ex-info "crew requires at least one task" {:tasks tasks})))
    (case process
      :sequential
      (apply o/chain steps)

      :parallel
      (o/parallel* steps :gather gather :max-concurrency max-concurrency)

      :hierarchical
      (let [manager     (or manager
                            (throw (ex-info "hierarchical crew requires :manager"
                                            {:process process})))
            worker-flow (or worker (apply o/chain steps))]
        (o/orchestrate manager worker-flow
                       :synthesize (or synthesize gather)
                       :max-concurrency max-concurrency))

      (throw (ex-info "unsupported crew process"
                      {:process process
                       :supported [:sequential :parallel :hierarchical]})))))

(defn group-chat
  "Compile an AutoGen-style round-robin group chat into a chain.

  The current transcript is just the text threaded through the chain. An adapter can
  append to that transcript, summarize it, or keep opaque session state. This
  helper intentionally models the common deterministic `round_robin` speaker
  selection mode; use `route` directly when speaker selection should be dynamic."
  [agents & {:keys [rounds speaker-selection]
             :or   {speaker-selection :round-robin}}]
  (let [agents            (mapv #(require-agent % "group-chat agent") agents)
        speaker-selection (kind-key speaker-selection)
        rounds            (or rounds (count agents))]
    (when (empty? agents)
      (throw (ex-info "group-chat requires at least one agent" {:agents agents})))
    (when-not (= :round-robin speaker-selection)
      (throw (ex-info "group-chat currently supports :round-robin speaker selection"
                      {:speaker-selection speaker-selection})))
    (when (neg? rounds)
      (throw (ex-info "group-chat :rounds must be non-negative" {:rounds rounds})))
    (apply o/chain (take rounds (cycle agents)))))

(defn workflow-agent
  "Compile a Google ADK-style deterministic workflow agent into workflow data.

  `kind` may be:
  - `:sequential` -> `chain`
  - `:parallel` -> `parallel`
  - `:loop` -> `refine`; provide `[worker evaluator]` or pass `:evaluator`"
  [kind children & {:keys [evaluator max-rounds gather max-concurrency]}]
  (let [children (require-workflows children "workflow-agent children")]
    (case (kind-key kind)
      :sequential
      (apply o/chain children)

      :parallel
      (o/parallel* children :gather gather :max-concurrency max-concurrency)

      :loop
      (let [worker    (first children)
            evaluator (or evaluator (second children))]
        (when-not evaluator
          (throw (ex-info "loop workflow-agent requires an evaluator"
                          {:children children})))
        (if max-rounds
          (o/refine worker evaluator :max-rounds max-rounds)
          (o/refine worker evaluator)))

      (throw (ex-info "unsupported workflow-agent kind"
                      {:kind kind
                       :supported [:sequential :parallel :loop]})))))

(defn handoff-router
  "Compile an OpenAI Agents SDK-style specialist handoff router.

  This is `route` with an agent router: the router agent decides which specialist
  owns the next reply. For a fixed two-agent session handoff, use
  `karcarthy.orchestrate/handoff` directly."
  [router routes & {:keys [default] :as opts}]
  (if (contains? opts :default)
    (o/route router routes :default default)
    (o/route router routes)))

;; ---------------------------------------------------------------------------
;; LangGraph-style finite state graph
;; ---------------------------------------------------------------------------

(defn state-graph
  "Build a finite LangGraph-style state graph workflow.

  `nodes` maps ids to:
  - a karcarthy workflow;
  - a function of state -> result map, state update map, string, or value; or
  - `{:workflow workflow :prompt f-or-value}` where `:prompt` derives the prompt
    from state before running the workflow.

  `edges` maps node ids to:
  - the next node id;
  - `:end` or nil;
  - a route map keyed by `:route`, `:label`, or `:text` from the latest result;
  - a function of `[state result]` returning the next node id.

  Function nodes return state updates in the LangGraph style. Result fields other
  than `:karcarthy/type`, `:ok?`, `:agent`, and `:raw` are merged into graph
  state after each node."
  ([{:keys [nodes start edges max-steps merge-result]}]
   (state-graph nodes start edges
                :max-steps max-steps
                :merge-result merge-result))
  ([nodes start edges & {:keys [max-steps merge-result]}]
   (cond-> {:karcarthy/type :state-graph
            :nodes          nodes
            :start          start
            :edges          edges
            :max-steps      (or max-steps 32)}
     merge-result (assoc :merge-result merge-result))))

(defn- result? [x]
  (and (map? x) (= :result (:karcarthy/type x))))

(defn- normalize-node-return [x state]
  (cond
    (result? x) x
    (map? x)    (k/result (cond-> x
                            (not (contains? x :text)) (assoc :text (:text state))))
    :else       (k/result {:text (str x)})))

(defn- compact-result [result]
  (select-keys result [:karcarthy/type :ok? :agent :text :error :route :label
                       :value :subtasks :results]))

(defn- state-update [result]
  (apply dissoc result [:karcarthy/type :ok? :agent :raw]))

(defn- default-merge-result [state node-id result]
  (-> (merge state (state-update result))
      (assoc :last-node node-id
             :last-result (compact-result result))
      (update :path conj node-id)
      (update :results conj (compact-result result))
      (assoc-in [:outputs node-id] (:text result))))

(defn- spec-node? [node]
  (and (map? node) (contains? node :workflow)))

(defn- node-prompt [node state]
  (let [p (:prompt node)]
    (cond
      (fn? p)   (p state)
      (keyword? p) (get state p)
      (some? p) p
      :else     (:text state))))

(defn- run-graph-node [runner node state opts]
  (try
    (cond
      (fn? node)
      (otel/with-function-span opts :state-graph/node node
        #(normalize-node-return (node state) state))

      (spec-node? node)
      (o/safe-run runner (:workflow node) (str (node-prompt node state)) opts)

      (o/workflow? node)
      (o/safe-run runner node (str (:text state)) opts)

      :else
      (k/result {:ok?   false
                 :error :invalid-state-graph-node
                 :text  (str "invalid state graph node: " (pr-str node))}))
    (catch Throwable t
      (k/result {:ok?      false
                 :error    (or (ex-message t) (str t))
                 :exception (.getName (class t))}))))

(defn- key-candidates [x]
  (remove nil?
          [x
           (when (keyword? x) (name x))
           (when (string? x) (keyword x))]))

(defn- route-map-next [edge-map state result]
  (let [candidates (mapcat key-candidates
                           [(:route result) (:route state)
                            (:label result) (:label state)
                            (:text result)  (:text state)
                            :default "default"])]
    (some (fn [k]
            (when (contains? edge-map k)
              (get edge-map k)))
          candidates)))

(defn- invoke-edge [edge state result]
  (try
    (edge state result)
    (catch clojure.lang.ArityException _
      (edge state))))

(defn- resolve-edge [edge state result]
  (cond
    (fn? edge)  (invoke-edge edge state result)
    (map? edge) (route-map-next edge state result)
    :else      edge))

(defn- end-node? [node-id]
  (or (nil? node-id)
      (= :end node-id)
      (= "end" (some-> node-id name-key str/lower-case))))

(defmethod o/run-node :state-graph
  [runner {:keys [nodes start edges max-steps merge-result]} input opts]
  (let [merge-result (or merge-result default-merge-result)
        initial      {:input input
                      :text input
                      :path []
                      :outputs {}
                      :results []}]
    (loop [state initial, node-id start, step 0]
      (cond
        (end-node? node-id)
        (k/result {:text    (:text state)
                   :state   state
                   :path    (:path state)
                   :results (:results state)})

        (and max-steps (>= step max-steps))
        (k/result {:ok?   false
                   :error :max-steps
                   :text  (str "state graph exceeded max steps: " max-steps)
                   :state state
                   :path  (:path state)})

        :else
        (let [node (get nodes node-id ::missing)]
          (if (= ::missing node)
            (k/result {:ok?   false
                       :error :unknown-state-graph-node
                       :text  (str "unknown state graph node: " (pr-str node-id))
                       :state state
                       :path  (:path state)})
            (let [node-opts (otel/with-child-path opts [:nodes node-id])
                  result    (run-graph-node runner node state node-opts)]
              (if-not (k/ok? result)
                (k/result (assoc result
                                 :state state
                                 :path (:path state)))
                (let [state' (merge-result state node-id result)
                      next'  (resolve-edge (get edges node-id) state' result)]
                  (recur state' next' (inc step)))))))))))
