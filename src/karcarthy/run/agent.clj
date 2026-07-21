(ns karcarthy.run.agent
  "Agent participation and root Run orchestration."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [karcarthy.agent :refer [agent?]]
            [karcarthy.run.context :as context]
            [karcarthy.run.loop :as loop]
            [karcarthy.schema :as schema
             :refer [fail! throwable->failure]]
            [karcarthy.session :as session])
  (:import [java.util.concurrent Executors Semaphore]))

(declare run-agent!)

(defn- options! [label supported options]
  (when-not (map? options)
    (fail! :schema :configuration (str label " must be a map")
           {:value options}))
  (schema/reject-unknown! label supported options))

(defn- validate-root-options! [options]
  (options! "Run options" context/run-option-keys options)
  (doseq [[key predicate message]
          [[:on-event #(or (nil? %) (fn? %))
            "Run :on-event must be a function"]
           [:approval #(or (nil? %) (fn? %))
            "Run :approval must be a function"]
           [:model-transports #(or (nil? %) (map? %))
            "Run :model-transports must be a map"]
           [:cancel #(or (nil? %) (boolean? %) (fn? %)
                         (instance? clojure.lang.IDeref %))
            "Run :cancel must be a boolean, function, or dereferenceable value"]]]
    (when-not (predicate (get options key))
      (fail! :schema :configuration message {:value (get options key)})))
  options)

(defn- run-agent!
  [parent agent input options]
  (when-not (agent? agent)
    (fail! :schema :agent "run! requires an Agent" {:value agent}))
  (let [options (or options {})
        _ (options! "Participating run options"
                    context/agent-call-option-keys options)
        depth (inc (:depth parent))
        limits (context/validate-limits!
                (context/narrower-limits
                 (:limits parent)
                 (merge (:limits agent) (:limits options))))]
    (when (> depth (:depth limits))
      (fail! :budget :depth "Agent depth limit was reached"
             {:depth depth :limit (:depth limits)}))
    (let [local-deadline
          (when-let [ms (:deadline-ms limits)]
            (+ (System/nanoTime) (* 1000000 (long ms))))
          deadline
          (let [parent-deadline (:deadline-ns parent)]
            (cond
              (nil? parent-deadline) local-deadline
              (nil? local-deadline) parent-deadline
              :else (min parent-deadline local-deadline)))
          active-session (when (zero? depth) (:initial-session parent))
          run-context (assoc parent
                             :agent-id (context/id "agent_")
                             :parent-id (:agent-id parent)
                             :depth depth
                             :agent agent
                             :agent-input input
                             :context (if (contains? options :context)
                                        (:context options)
                                        (:context parent))
                             :session active-session
                             :pending-session-items (atom nil)
                             :limits limits
                             :deadline-ns deadline)
          started (System/nanoTime)]
      (schema/check! :context (:context-schema agent) (:context run-context))
      (schema/check! :agent-input (:input-schema agent) input)
      (context/run-guardrails! run-context :agent-input
                               (:input-guardrails agent) input)
      (context/emit! run-context
                     {:type :agent/started
                      :agent (:name agent)
                      :input input})
      (try
        (let [output (binding [context/*run* run-context]
                       (loop/run! run-context agent input run-agent!))
              output (loop/output output (:output-schema agent))]
          (context/check-run! run-context)
          (schema/check! :agent-output (:output-schema agent) output)
          (context/run-guardrails! run-context :agent-output
                                   (:output-guardrails agent) output)
          (when-let [items @(:pending-session-items run-context)]
            (context/append-items! run-context items))
          (context/emit!
           run-context
           {:type :agent/completed
            :agent (:name agent)
            :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
            :output output})
          output)
        (catch Throwable error
          (context/emit!
           run-context
           {:type :agent/failed
            :agent (:name agent)
            :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
            :error (throwable->failure error)})
          (if (= :failure (:karcarthy/type (ex-data error)))
            (throw error)
            (fail! :execution :agent (or (ex-message error) (str error))
                   {:agent (:name agent)} error)))))))

(defn- result
  [run-context agent input started events output error]
  {:karcarthy/type :run
   :id (:run-id run-context)
   :status (cond
             (nil? error) :completed
             (= :cancellation (:kind error)) :cancelled
             :else :failed)
   :agent (:name agent)
   :input input
   :output output
   :usage (assoc @(:usage run-context)
                 :duration-ms
                 (/ (double (- (System/nanoTime) started)) 1000000.0))
   :events events
   :error error})

(defn- run-agent-call!
  [run-context agent input options]
  (let [started (System/nanoTime)
        events (atom [])
        run-context (update run-context :event-collectors
                            (fnil conj []) events)]
    (try
      (let [output (run-agent! run-context agent input options)]
        (result run-context agent input started @events output nil))
      (catch Throwable error
        (result run-context agent input started @events nil
                (throwable->failure error))))))

(defn- root-context [agent options]
  (validate-root-options! options)
  (when (and (some? (:session options))
             (not (session/session? (:session options))))
    (fail! :schema :session
           "Run :session must implement karcarthy.session/Session"
           {:value (:session options)}))
  (when-not (agent? agent)
    (fail! :schema :agent "run! requires an Agent" {:value agent}))
  (let [limits (context/validate-limits!
                (merge context/default-limits (:limits options)))
        run-id (context/id "run_")
        started (System/nanoTime)]
    {:karcarthy/type :run-context
     :run-id run-id
     :agent-id nil
     :parent-id nil
     :depth -1
     :agent nil
     :context (:context options)
     :limits limits
     :usage (atom {:model-calls 0
                   :input-tokens 0
                   :output-tokens 0
                   :evals 0})
     :events (atom [])
     :initial-session (:session options)
     :on-event (:on-event options)
     :approval (:approval options)
     :approvals (atom #{})
     :cancel (:cancel options)
     :model-transports (:model-transports options)
     :executor (Executors/newVirtualThreadPerTaskExecutor)
     :semaphore (Semaphore. (int (:concurrency limits)) true)
     :deadline-ns (when-let [ms (:deadline-ms limits)]
                    (+ started (* 1000000 (long ms))))
     :eval-parent-namespace (:definition-ns agent)
     :eval-namespace (symbol
                      (str 'karcarthy.eval
                           ".run_" (str/replace run-id "-" "_")))
     :eval-counter (atom 0)}))

(defn run!
  "Run an Agent and return a Run map."
  ([agent input]
   (run! agent input {}))
  ([agent input options]
   (if context/*run*
     (let [run-context context/*run*
           started (System/nanoTime)]
       (try
         (context/await-future!
          run-context
          (context/submit-limited!
           run-context #(run-agent-call! run-context agent input
                                         (or options {}))))
         (catch Throwable error
           (result run-context agent input started [] nil
                   (throwable->failure error)))))
     (let [options (or options {})
           run-context (root-context agent options)
           started (System/nanoTime)
           events (:events run-context)
           usage (:usage run-context)]
       (context/emit! run-context
                      {:type :run/started
                       :agent (:name agent)
                       :input input})
       (try
         (let [call #(binding [context/*run* run-context]
                       (run-agent-call! run-context agent input {}))
               result (if-let [active-session (:initial-session run-context)]
                        (locking active-session (call))
                        (call))
               event-type (case (:status result)
                            :completed :run/completed
                            :cancelled :run/cancelled
                            :run/failed)]
           (context/emit!
            run-context
            (cond-> {:type event-type :agent (:name agent)}
              (= :completed (:status result))
              (assoc :output (:output result) :usage @usage)
              (not= :completed (:status result))
              (assoc :error (:error result))))
           (assoc result
                  :usage (assoc @usage
                                :duration-ms
                                (/ (double (- (System/nanoTime) started))
                                   1000000.0))
                  :events @events))
         (finally
           (.shutdownNow (:executor run-context))))))))
