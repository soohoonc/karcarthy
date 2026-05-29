(ns karcarthy.orchestrate
  "Orchestration as data.

  A *flow* is a plain Clojure value describing how to coordinate agents. It is
  either an agent (a leaf — see `karcarthy.core/agent`) or a composite node
  tagged with `:karcarthy/type`:

    :chain     run flows in sequence, threading each result's :text into the
               next; short-circuits on the first failure.
    :parallel  run flows concurrently on the same input; gather the results.
    :route     pick one downstream flow by a label from a router (a fn of the
               input, or an agent whose reply is the label).

  Because a flow is just data, you can build it with the constructors here,
  assemble it by hand, generate it programmatically, walk it with
  `clojure.walk`, serialize it to EDN, and diff two versions of it. `run-flow`
  is the interpreter that executes a flow against a `Harness`.

  Every flow run returns a `karcarthy.core` result map, so flows compose: the
  output of one is a valid input position for another."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]))

;; ---------------------------------------------------------------------------
;; Constructors — build flow data
;; ---------------------------------------------------------------------------

(defn chain
  "A flow that runs `steps` (agents or flows) in sequence, threading the :text
  of each result into the next step's input. Returns the last result, or the
  first failing result (short-circuiting)."
  [& steps]
  {:karcarthy/type :chain :steps (vec steps)})

(defn parallel
  "A flow that runs each of `branches` concurrently on the same input and
  returns a result whose :results is the vector of branch results (in order).
  Its :text is the branch texts joined by blank lines, and :ok? is true only if
  every branch succeeded.

  Pass a `:gather` fn (of the results vector -> a result/map) via `parallel*`
  to post-process; this arity uses the default join."
  [& branches]
  {:karcarthy/type :parallel :branches (vec branches)})

(defn parallel*
  "Like `parallel` but with options. `:gather` is a fn of the results vector
  returning a result/map; when present its :text becomes the node's :text."
  [branches & {:keys [gather]}]
  (cond-> {:karcarthy/type :parallel :branches (vec branches)}
    gather (assoc :gather gather)))

(defn route
  "A flow that dispatches to one downstream flow chosen by `router`:
    - if `router` is a fn, it is called with the input and must return a label;
    - otherwise `router` is run as a flow and its result's :text (trimmed) is
      the label.
  `routes` maps labels to flows. `:default` (optional kwarg) is used when no
  route matches."
  [router routes & {:keys [default]}]
  (cond-> {:karcarthy/type :route :router router :routes routes}
    default (assoc :default default)))

;; ---------------------------------------------------------------------------
;; Interpreter
;; ---------------------------------------------------------------------------

(declare run-flow)

(defmulti ^:private run-node
  "Execute one flow node. Dispatches on (:karcarthy/type node)."
  (fn [_harness node _input _opts] (:karcarthy/type node)))

(defmethod run-node :agent
  [harness agent input opts]
  (k/run-agent harness agent input opts))

(defmethod run-node :chain
  [harness {:keys [steps]} input opts]
  (loop [input input, steps steps, last-result nil]
    (if (empty? steps)
      (or last-result (k/result {:ok? true :text input :empty-chain? true}))
      (let [r (run-flow harness (first steps) input opts)]
        (if (k/ok? r)
          (recur (:text r) (rest steps) r)
          r)))))                                    ; short-circuit on failure

(defmethod run-node :parallel
  [harness {:keys [branches gather]} input opts]
  (let [results  (->> branches
                      (mapv (fn [b] (future (run-flow harness b input opts))))
                      (mapv deref))
        gathered (when gather (gather results))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :gathered gathered
               :text     (or (:text gathered)
                             (str/join "\n\n" (keep :text results)))})))

(defmethod run-node :route
  [harness {:keys [router routes default]} input opts]
  (let [label (if (fn? router)
                (router input)
                (str/trim (str (:text (run-flow harness router input opts)))))
        flow  (get routes label default)]
    (if flow
      (run-flow harness flow input opts)
      (k/result {:ok? false :error :no-route :label label
                 :text (str "no route for label: " (pr-str label))}))))

(defmethod run-node :default
  [_ node _ _]
  (throw (ex-info "Not a runnable flow node (missing or unknown :karcarthy/type)"
                  {:node node})))

(defn run-flow
  "Interpret `flow` against `harness`, starting from `input` (a string). Returns
  a `karcarthy.core` result map. `flow` may be an agent or any composite node."
  ([harness flow input] (run-flow harness flow input {}))
  ([harness flow input opts] (run-node harness flow input opts)))
