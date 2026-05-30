(ns karcarthy.dynamic
  "A data-only meta-execution loop.

  `karcarthy.self/evolve` lets one agent patch itself. This namespace lifts that
  idea to the whole execution state: a controller agent emits EDN operations that
  put resources into the runtime, patch them, remove them, call them, and decide
  the next operation from the resulting state.

  The important boundary is the same as the rest of karcarthy: operations are
  parsed as data with `clojure.edn`, never evaluated as code. Dynamic behavior
  comes from changing the data registries that later runs resolve."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.edn :as kedn]
            [karcarthy.orchestrate :as o]))

(defn- name-key [x]
  (cond
    (keyword? x) (name x)
    (string? x) x
    :else (str x)))

(defn- kind-key [x]
  (keyword (name-key x)))

(defn- workflow-kind? [kind]
  (= :workflow (kind-key kind)))

(defn dynamic-agent-ref
  "A portable reference to an agent in a dynamic runtime. It is resolved to the
  current agent value immediately before execution, so patches take effect."
  [name]
  {:karcarthy/type :agent-ref :name (name-key name)})

(defn dynamic-workflow-ref
  "A portable reference to a named workflow in a dynamic runtime."
  [name]
  {:karcarthy/type :workflow-ref :name (name-key name)})

(declare workflow-config?)

(defn- maybe-workflow-config? [x]
  (or (fn? x) (workflow-config? x)))

(defn workflow-config?
  "True if `x` is a portable workflow config accepted by the dynamic runtime.
  This extends normal karcarthy workflows with data refs:
  `{:karcarthy/type :agent-ref :name ...}` and
  `{:karcarthy/type :workflow-ref :name ...}`."
  [x]
  (cond
    (k/agent? x) true
    (not (map? x)) false
    :else
    (case (:karcarthy/type x)
      :agent-ref    (contains? x :name)
      :workflow-ref (contains? x :name)
      :chain        (and (sequential? (:steps x))
                         (every? workflow-config? (:steps x)))
      :parallel     (and (sequential? (:branches x))
                         (every? workflow-config? (:branches x)))
      :route        (and (maybe-workflow-config? (:router x))
                         (map? (:routes x))
                         (every? workflow-config? (vals (:routes x)))
                         (or (not (contains? x :default))
                             (workflow-config? (:default x))))
      :refine       (and (workflow-config? (:worker x))
                         (maybe-workflow-config? (:evaluator x)))
      :orchestrate  (and (maybe-workflow-config? (:planner x))
                         (workflow-config? (:worker x)))
      :handoff      (and (workflow-config? (:from x))
                         (workflow-config? (:to x)))
      :evolve       (workflow-config? (:agent x))
      false)))

(defn- validate-agent [agent]
  (if (k/agent? agent)
    agent
    (throw (ex-info "invalid dynamic agent" {:agent agent
                                             :explain (k/explain-agent agent)}))))

(defn- validate-workflow [workflow]
  (if (workflow-config? workflow)
    workflow
    (throw (ex-info "invalid dynamic workflow" {:workflow workflow}))))

(defn dynamic-runtime
  "Create mutable runtime state for dynamic execution.

  `agents` is a collection of agent maps. `workflows` is a map of name -> workflow
  config. Both are stored as data and can be changed by operations."
  [& {:keys [agents workflows history]}]
  (doseq [agent agents] (validate-agent agent))
  (doseq [[_ workflow] workflows] (validate-workflow workflow))
  (atom {:agents    (into {} (map (fn [a] [(name-key (:name a)) a]) agents))
         :workflows (into {} (map (fn [[n w]] [(name-key n) w]) workflows))
         :history   (vec history)}))

(defn snapshot
  "Return the current dynamic runtime state as plain data."
  [rt]
  @rt)

(defn- lookup-agent [rt name]
  (let [n (name-key name)]
    (or (get-in @rt [:agents n])
        (throw (ex-info (str "unknown dynamic agent: " (pr-str n))
                        {:name n :known (keys (:agents @rt))})))))

(defn- lookup-workflow [rt name]
  (let [n (name-key name)]
    (or (get-in @rt [:workflows n])
        (throw (ex-info (str "unknown dynamic workflow: " (pr-str n))
                        {:name n :known (keys (:workflows @rt))})))))

(defn materialize
  "Resolve dynamic agent/workflow references in `workflow` against runtime state,
  returning an ordinary karcarthy workflow that `karcarthy.orchestrate/run` can
  execute."
  ([rt workflow] (materialize rt workflow #{}))
  ([rt workflow seen]
   (cond
     (or (k/agent? workflow) (fn? workflow)) workflow
     (not (map? workflow)) workflow
     :else
     (case (:karcarthy/type workflow)
       :agent-ref
       (lookup-agent rt (:name workflow))

       :workflow-ref
       (let [n (name-key (:name workflow))
             marker [:workflow n]]
         (when (contains? seen marker)
           (throw (ex-info "cyclic dynamic workflow reference" {:name n :seen seen})))
         (materialize rt (lookup-workflow rt n) (conj seen marker)))

       :chain
       (update workflow :steps #(mapv (fn [w] (materialize rt w seen)) %))

       :parallel
       (update workflow :branches #(mapv (fn [w] (materialize rt w seen)) %))

       :route
       (cond-> workflow
         (not (fn? (:router workflow))) (update :router #(materialize rt % seen))
         true (update :routes (fn [routes]
                                (into {} (map (fn [[label w]]
                                                [label (materialize rt w seen)])
                                              routes))))
         (contains? workflow :default) (update :default #(materialize rt % seen)))

       :refine
       (cond-> workflow
         true (update :worker #(materialize rt % seen))
         (not (fn? (:evaluator workflow))) (update :evaluator #(materialize rt % seen)))

       :orchestrate
       (cond-> workflow
         (not (fn? (:planner workflow))) (update :planner #(materialize rt % seen))
         true (update :worker #(materialize rt % seen)))

       :handoff
       (-> workflow
           (update :from #(materialize rt % seen))
           (update :to #(materialize rt % seen)))

       :evolve
       (update workflow :agent #(materialize rt % seen))

       workflow))))

(def ^:private operation-kinds
  #{:put :patch :remove :call :complete})

(defn- operation-kind [operation]
  (let [op (or (:karcarthy/op operation) (:op operation))]
    (cond
      (keyword? op) op
      (string? op) (keyword op)
      :else op)))

(defn normalize-operation
  "Normalize an operation map, accepting either `:karcarthy/op` or `:op`."
  [operation]
  (let [op (operation-kind operation)]
    (when-not (contains? operation-kinds op)
      (throw (ex-info "unknown dynamic operation" {:operation operation
                                                   :known operation-kinds})))
    (assoc operation :karcarthy/op op)))

(defn read-operation
  "Parse the first EDN map in `text` into a dynamic operation."
  [text]
  (normalize-operation (kedn/extract-map text)))

(defn- compact-result [result]
  (select-keys result [:karcarthy/type :ok? :agent :text :error :label :rounds
                       :subtasks :results :value :kind :id :removed?]))

(defn- remember! [rt operation result]
  (swap! rt update :history conj {:operation operation
                                  :result    (compact-result result)})
  result)

(defn- operation-input [operation]
  (or (:input operation) (:prompt operation) ""))

(defn- resource-kind [resource]
  (or (:kind resource)
      (when-let [t (:karcarthy/type resource)]
        (case t
          :agent :agent
          t))))

(defn- resource-id [resource]
  (or (:id resource) (:name resource)))

(defn- normalize-agent-resource [resource]
  (if (k/agent? resource)
    resource
    (let [n (or (:name resource) (:id resource))]
      (validate-agent
       (cond-> (select-keys resource [:instructions :model :tools :handoffs
                                      :runner :harness])
         true (assoc :karcarthy/type :agent
                     :name (name-key n)))))))

(defn- normalize-workflow-resource [resource]
  (validate-workflow (or (:workflow resource) (:graph resource) (:value resource))))

(defn- put-resource! [rt resource]
  (let [kind (kind-key (resource-kind resource))
        id   (or (resource-id resource)
                 (throw (ex-info "dynamic resource requires :id or :name"
                                 {:resource resource})))]
    (cond
      (= :agent kind)
      (let [agent (normalize-agent-resource resource)
            n     (name-key (:name agent))]
        (swap! rt assoc-in [:agents n] agent)
        [:agent n])

      (workflow-kind? kind)
      (let [n        (name-key id)
            workflow (normalize-workflow-resource resource)]
        (swap! rt assoc-in [:workflows n] workflow)
        [:workflow n])

      :else
      (throw (ex-info "dynamic resource kind must be :agent or :workflow"
                      {:resource resource :kind kind})))))

(defn- patch-resource! [rt kind id patch]
  (let [kind (kind-key kind)
        n    (name-key id)]
    (cond
      (= :agent kind)
      (let [current (lookup-agent rt n)
            updated (validate-agent (merge current patch))]
        (swap! rt assoc-in [:agents n] updated)
        [:agent n])

      (workflow-kind? kind)
      (let [current  (lookup-workflow rt n)
            workflow (validate-workflow (merge current patch))]
        (swap! rt assoc-in [:workflows n] workflow)
        [:workflow n])

      :else
      (throw (ex-info "dynamic resource kind must be :agent or :workflow"
                      {:kind kind :id id})))))

(defn- remove-resource! [rt kind id]
  (let [kind (kind-key kind)
        n    (name-key id)]
    (cond
      (= :agent kind)
      (do (swap! rt update :agents dissoc n) [:agent n])

      (workflow-kind? kind)
      (do (swap! rt update :workflows dissoc n) [:workflow n])

      :else
      (throw (ex-info "dynamic resource kind must be :agent or :workflow"
                      {:kind kind :id id})))))

(defn- target-kind [rt operation]
  (or (:kind operation)
      (let [target (name-key (or (:target operation) (:name operation)))]
        (cond
          (contains? (:agents @rt) target) :agent
          (contains? (:workflows @rt) target) :workflow
          :else :agent))))

(defn- call-once [runner rt operation input opts]
  (if-let [workflow (:workflow operation)]
    (o/run runner (materialize rt workflow) input opts)
    (let [target (or (:target operation) (:name operation))
          kind   (target-kind rt operation)]
      (cond
        (= :agent (kind-key kind))
        (k/run-agent runner (lookup-agent rt target) input opts)

        (workflow-kind? kind)
        (o/run runner (materialize rt (lookup-workflow rt target)) input opts)

        :else
        (throw (ex-info "dynamic call target must be an agent or workflow"
                        {:operation operation :kind kind :target target}))))))

(defn- apply-call [runner rt operation opts]
  (if (contains? operation :for-each)
    (let [items   (:for-each operation)
          results (mapv (fn [item]
                          (call-once runner rt operation (str item) opts))
                        items)]
      (k/result {:agent   "dynamic"
                 :ok?     (every? k/ok? results)
                 :results results
                 :text    (str/join "\n\n" (keep :text results))}))
    (call-once runner rt operation (operation-input operation) opts)))

(defn apply-operation
  "Apply one dynamic operation to `rt`, optionally running agents or workflows.
  Returns a karcarthy result map describing the operation outcome."
  ([runner rt operation] (apply-operation runner rt operation {}))
  ([runner rt operation opts]
   (let [operation (normalize-operation operation)
         op        (:karcarthy/op operation)]
     (case op
       :put
       (let [[kind id] (put-resource! rt (:resource operation))
             r         (k/result {:agent "dynamic"
                                  :text  (str "put " (name kind) " " id)
                                  :kind  kind
                                  :id    id})]
         (remember! rt operation r))

       :patch
       (let [patch     (or (:merge operation) (:patch operation))
             [kind id] (patch-resource! rt (:kind operation) (:id operation) patch)
             r         (k/result {:agent "dynamic"
                                  :text  (str "patched " (name kind) " " id)
                                  :kind  kind
                                  :id    id})]
         (remember! rt operation r))

       :remove
       (let [[kind id] (remove-resource! rt (:kind operation) (:id operation))
             r         (k/result {:agent   "dynamic"
                                  :text    (str "removed " (name kind) " " id)
                                  :kind    kind
                                  :id      id
                                  :removed? true})]
         (remember! rt operation r))

       :call
       (let [r (apply-call runner rt operation opts)]
         (remember! rt operation r))

       :complete
       (let [v (:value operation)
             r (k/result {:agent "dynamic"
                          :value v
                          :text  (str (or (:text operation) (:content operation) v ""))})]
         (remember! rt operation r))))))

(def dynamic-reference
  "Prompt fragment for a controller agent that drives dynamic execution."
  (str/join
   "\n"
   ["You control karcarthy by outputting exactly one EDN operation map."
    "Do not output prose."
    ""
    "Operations:"
    "{:karcarthy/op :put :resource {:kind :agent :id \"agent-name\" :instructions \"...\"}}"
    "{:karcarthy/op :put :resource {:kind :workflow :id \"workflow-name\" :workflow WORKFLOW}}"
    "{:karcarthy/op :patch :kind :agent :id \"agent-name\" :merge {:instructions \"...\"}}"
    "{:karcarthy/op :patch :kind :workflow :id \"workflow-name\" :merge MAP}"
    "{:karcarthy/op :remove :kind :agent :id \"agent-name\"}"
    "{:karcarthy/op :call :target \"agent-or-workflow-name\" :input \"...\"}"
    "{:karcarthy/op :call :target \"agent-or-workflow-name\" :for-each [\"a\" \"b\"]}"
    "{:karcarthy/op :complete :text \"final answer\"}"
    "Portable refs inside workflows:"
    "{:karcarthy/type :agent-ref :name \"agent-name\"}"
    "{:karcarthy/type :workflow-ref :name \"workflow-name\"}"
    ""
    "Known workflow node types: :chain, :parallel, :route, :refine,"
    ":orchestrate, :handoff, :evolve."]))

(defn- state-view [rt]
  (let [{:keys [agents workflows history]} @rt]
    {:agents    (into {} (map (fn [[n a]]
                                [n (select-keys a [:karcarthy/type :name :instructions
                                                    :model :tools :runner])])
                              agents))
     :workflows workflows
     :history   history}))

(defn controller-prompt
  "Build the prompt for the dynamic controller's next operation."
  [task rt last-result step]
  (str dynamic-reference
       "\n\nTASK:\n" task
       "\n\nSTEP: " step
       "\n\nSTATE EDN:\n" (pr-str (state-view rt))
       "\n\nLAST OBSERVATION EDN:\n" (pr-str (compact-result last-result))
       "\n\nOutput exactly one EDN operation now."))

(defn run-dynamic
  "Run a controller agent that evolves agents/workflows as data.

  The controller is called repeatedly. Each reply must be one EDN operation, and
  non-terminal operations feed their result back into the next controller turn.
  `:max-steps nil` allows the controller to run until it emits `:complete` or an
  error occurs; the default is 25 so accidental loops remain finite by default."
  [runner controller task & {:keys [runtime max-steps opts]
                             :or   {max-steps 25 opts {}}}]
  (let [rt (or runtime (dynamic-runtime))]
    (loop [step 1, last-result nil]
      (if (and max-steps (> step max-steps))
        (k/result {:agent   (:name controller)
                   :ok?     false
                   :error   :max-steps
                   :text    (str "dynamic controller exceeded max steps: " max-steps)
                   :runtime (snapshot rt)})
        (let [prompt (controller-prompt task rt last-result step)
              cr     (k/run-agent runner controller prompt opts)]
          (if-not (k/ok? cr)
            (k/result (assoc cr :runtime (snapshot rt)))
            (let [outcome (try
                            (let [operation (read-operation (:text cr))
                                  result    (apply-operation runner rt operation opts)]
                              {:operation operation
                               :result    (assoc result :runtime (snapshot rt) :steps step)})
                            (catch Throwable t
                              {:error t}))]
              (if-let [t (:error outcome)]
                (k/result {:agent   (:name controller)
                           :ok?     false
                           :error   (or (ex-message t) (str t))
                           :text    (:text cr)
                           :runtime (snapshot rt)
                           :raw     {:controller cr}})
                (let [{:keys [operation result]} outcome]
                  (if (= :complete (:karcarthy/op operation))
                    result
                    (recur (inc step) result)))))))))))
