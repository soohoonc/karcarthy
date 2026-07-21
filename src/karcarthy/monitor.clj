(ns karcarthy.monitor
  "Live, event-derived views of running Agents."
  (:require [clojure.string :as str])
  (:import [java.io Closeable Writer]
           [java.util.concurrent Executors ThreadFactory TimeUnit]))

(def ^:private rendered-event-types
  #{:run/started :run/completed :run/failed :run/cancelled
    :agent/started :agent/completed :agent/failed
    :model/requested :model/completed :model/failed
    :tool/started :tool/completed :tool/failed
    :eval/started :eval/expanded :eval/completed :eval/failed})

(defn- empty-run [event]
  {:id (:run-id event)
   :agent (:agent event)
   :status :running
   :started-at-ms (:time-ms event)
   :updated-at-ms (:time-ms event)
   :elapsed-ms 0
   :agents {}
   :agent-order []
   :evals 0
   :usage {:model-calls 0
           :input-tokens 0
           :output-tokens 0}
   :error nil})

(defn- ensure-run [snapshot event]
  (let [run-id (:run-id event)]
    (if (or (nil? run-id) (get-in snapshot [:runs run-id]))
      snapshot
      (-> snapshot
          (assoc-in [:runs run-id] (empty-run event))
          (update :run-order conj run-id)))))

(defn- agent-activity-after-tools [tools]
  (cond
    (some #(= "eval" (:name %)) (vals tools)) :evaluating
    (seq tools) :tools
    :else :running))

(defn- start-agent [run event]
  (let [agent-id (:agent-id event)
        parent-id (:parent-id event)
        new? (not (contains? (:agents run) agent-id))
        agent {:id agent-id
               :parent-id parent-id
               :depth (:depth event)
               :name (:agent event)
               :status :running
               :activity :running
               :tools {}
               :tool-order []
               :started-at-ms (:time-ms event)
               :updated-at-ms (:time-ms event)}
        run (cond-> (assoc-in run [:agents agent-id] agent)
              new? (update :agent-order conj agent-id))]
    (if (and parent-id (get-in run [:agents parent-id]))
      (assoc-in run [:agents parent-id :activity] :waiting-agents)
      run)))

(defn- update-agent [run event f & args]
  (let [agent-id (:agent-id event)]
    (if (and agent-id (get-in run [:agents agent-id]))
      (apply update-in run [:agents agent-id] f args)
      run)))

(defn- start-tool [agent event]
  (let [call-id (:tool-call-id event)
        new? (not (contains? (:tools agent) call-id))
        tool {:id call-id
              :name (:tool event)
              :started-at-ms (:time-ms event)}]
    (cond-> (-> agent
                (assoc :activity (if (= "eval" (:tool event))
                                   :evaluating
                                   :tools)
                       :updated-at-ms (:time-ms event))
                (assoc-in [:tools call-id] tool))
      new? (update :tool-order conj call-id))))

(defn- finish-tool [agent event]
  (let [tools (dissoc (:tools agent) (:tool-call-id event))]
    (assoc agent
           :tools tools
           :activity (agent-activity-after-tools tools)
           :updated-at-ms (:time-ms event))))

(defn- set-activity [agent activity event]
  (assoc agent :activity activity :updated-at-ms (:time-ms event)))

(defn- finish-agent [agent status event]
  (assoc agent
         :status status
         :activity nil
         :updated-at-ms (:time-ms event)
         :ended-at-ms (:time-ms event)
         :error (:error event)))

(defn- usage-value [usage kebab snake]
  (long (or (get usage kebab) (get usage snake) 0)))

(defn- add-model-usage [run event]
  (let [usage (:usage event)]
    (-> run
        (update-in [:usage :input-tokens] +
                   (usage-value usage :input-tokens :input_tokens))
        (update-in [:usage :output-tokens] +
                   (usage-value usage :output-tokens :output_tokens)))))

(defn- reduce-run-event [run event]
  (let [run (assoc run :updated-at-ms (:time-ms event))]
    (case (:type event)
      :run/started
      (assoc run :agent (:agent event) :status :running)

      :run/completed
      (assoc run
             :status :completed
             :ended-at-ms (:time-ms event)
             :usage (merge (:usage run) (:usage event)))

      :run/failed
      (assoc run :status :failed :ended-at-ms (:time-ms event)
             :error (:error event))

      :run/cancelled
      (assoc run :status :cancelled :ended-at-ms (:time-ms event)
             :error (:error event))

      :agent/started
      (start-agent run event)

      :agent/completed
      (update-agent run event finish-agent :completed event)

      :agent/failed
      (update-agent run event finish-agent :failed event)

      :model/requested
      (-> run
          (update-in [:usage :model-calls] inc)
          (update-agent event set-activity :model event))

      :model/completed
      (-> run
          (add-model-usage event)
          (update-agent event set-activity :running event))

      :model/failed
      (update-agent run event set-activity :model-failed event)

      :tool/started
      (update-agent run event start-tool event)

      :tool/completed
      (update-agent run event finish-tool event)

      :tool/failed
      (update-agent run event finish-tool event)

      :eval/started
      (-> run
          (update :evals inc)
          (update-agent event set-activity :evaluating event))

      :eval/expanded
      (update-agent run event set-activity :evaluating event)

      :eval/completed
      (update-agent run event set-activity :running event)

      :eval/failed
      (update-agent run event set-activity :eval-failed event)

      run)))

(defn- refresh-elapsed [snapshot now-ms]
  (-> snapshot
      (assoc :now-ms now-ms)
      (update :runs
              (fn [runs]
                (reduce-kv
                 (fn [result run-id run]
                   (let [end-ms (or (:ended-at-ms run) now-ms
                                    (:updated-at-ms run))]
                     (assoc result run-id
                            (assoc run :elapsed-ms
                                   (max 0 (- end-ms
                                             (:started-at-ms run)))))))
                 {}
                 runs)))))

(defn- reduce-event [snapshot event]
  (let [snapshot (ensure-run snapshot event)
        run-id (:run-id event)]
    (if (and run-id (get-in snapshot [:runs run-id]))
      (-> snapshot
          (update-in [:runs run-id] reduce-run-event event)
          (refresh-elapsed (:time-ms event)))
      snapshot)))

(defn- short-id [id]
  (if (> (count (str id)) 12)
    (str (subs (str id) 0 12) "…")
    (str id)))

(defn- active-tools [agent]
  (keep (:tools agent) (:tool-order agent)))

(defn- plural [n singular]
  (str (format "%,d" (long n)) " " singular (when (not= n 1) "s")))

(defn- elapsed-label [run]
  (let [elapsed-seconds (quot (:elapsed-ms run 0) 1000)]
    (if (< elapsed-seconds 60)
      (str elapsed-seconds "s")
      (format "%dm %02ds" (quot elapsed-seconds 60)
              (mod elapsed-seconds 60)))))

(defn- token-count [run]
  (+ (get-in run [:usage :input-tokens] 0)
     (get-in run [:usage :output-tokens] 0)))

(defn- activity-label [agent]
  (case (:status agent)
    :completed "done"
    :failed "failed"
    (case (:activity agent)
      :model "calling model"
      :model-failed "model failed"
      :evaluating "evaluating Clojure"
      :waiting-agents "waiting for Agent"
      :tools
      (let [tools (vec (active-tools agent))]
        (if (= 1 (count tools))
          (str "Tool: " (:name (first tools)))
          (plural (count tools) "Tool")))
      :eval-failed "eval failed"
      "running")))

(defn- children [run parent-id]
  (->> (:agent-order run)
       (keep (:agents run))
       (filter #(= parent-id (:parent-id %)))))

(defn- agent-lines [run agent prefix last?]
  (let [connector (if last? "└─ " "├─ ")
        line (str prefix connector (:name agent) " · " (activity-label agent))
        child-prefix (str prefix (if last? "   " "│  "))
        children (vec (children run (:id agent)))]
    (into [line]
          (mapcat (fn [index child]
                    (agent-lines run child child-prefix
                                 (= index (dec (count children)))))
                  (range (count children))
                  children))))

(defn- run-lines [run]
  (let [evals (:evals run)
        header (str "Run " (short-id (:id run)) " · " (name (:status run))
                    " · " (elapsed-label run)
                    " · " (plural (get-in run [:usage :model-calls] 0)
                                    "model call")
                    " · " (plural (token-count run) "token")
                    (when (pos? evals)
                      (str " · " (plural evals "eval"))))
        roots (vec (children run nil))]
    (into [header]
          (mapcat (fn [index agent]
                    (agent-lines run agent "" (= index (dec (count roots)))))
                  (range (count roots))
                  roots))))

(defn monitor-state
  "Return the current monitor state as Clojure data."
  [monitor-or-snapshot]
  (if (instance? clojure.lang.IDeref monitor-or-snapshot)
    @monitor-or-snapshot
    monitor-or-snapshot))

(defn monitor-view
  "Return the current Run and Agent tree as text."
  [monitor-or-snapshot]
  (let [snapshot (monitor-state monitor-or-snapshot)
        runs (keep (:runs snapshot) (:run-order snapshot))]
    (str/join "\n\n" (map #(str/join "\n" (run-lines %)) runs))))

(defn print-monitor
  "Print one snapshot of a Run monitor."
  ([monitor] (print-monitor monitor *out*))
  ([monitor ^Writer out]
   (.write out (str (monitor-view monitor) "\n"))
   (.flush out)
   monitor))

(declare draw-tree! update-ticker! stop-ticker!)

(deftype Monitor [state display out rendered-lines ticker lock]
  clojure.lang.IDeref
  (deref [_] @state)

  clojure.lang.IFn
  (invoke [this event]
    (locking lock
      (swap! state reduce-event event)
      (when (and (= :tree display)
                 (contains? rendered-event-types (:type event)))
        (draw-tree! this))
      (update-ticker! this))
    nil)

  Closeable
  (close [this]
    (locking lock
      (stop-ticker! this))))

(defmethod print-method Monitor [monitor ^Writer out]
  (.write out (monitor-view monitor)))

(defn- draw-tree! [^Monitor monitor]
  (let [^Writer out (.-out monitor)
        rendered-lines (.-rendered-lines monitor)
        previous @rendered-lines
        view (monitor-view monitor)
        lines (if (str/blank? view) 0 (count (str/split-lines view)))]
    (when (pos? previous)
      (.write out (str "\u001b[" previous "A")))
    (.write out "\u001b[J")
    (when (pos? lines)
      (.write out (str view "\n")))
    (.flush out)
    (reset! rendered-lines lines)))

(defn- active-runs? [snapshot]
  (some #(= :running (:status %)) (vals (:runs snapshot))))

(def ^:private daemon-thread-factory
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "karcarthy-monitor")
        (.setDaemon true)))))

(defn- start-ticker! [^Monitor monitor]
  (let [ticker (.-ticker monitor)
        lock (.-lock monitor)]
    (when (nil? @ticker)
      (let [executor (Executors/newSingleThreadScheduledExecutor
                      daemon-thread-factory)]
        (if (compare-and-set! ticker nil executor)
          (.scheduleAtFixedRate
           executor
           ^Runnable
           (reify Runnable
             (run [_]
               (try
                 (locking lock
                   (when (active-runs? @(.-state monitor))
                     (swap! (.-state monitor)
                            refresh-elapsed (System/currentTimeMillis))
                     (when (= :tree (.-display monitor))
                       (draw-tree! monitor))))
                 (catch Throwable _ nil))))
           1 1 TimeUnit/SECONDS)
          (.shutdownNow executor))))))

(defn- stop-ticker! [^Monitor monitor]
  (let [ticker (.-ticker monitor)]
    (when-let [executor @ticker]
      (when (compare-and-set! ticker executor nil)
        (.shutdownNow executor)))))

(defn- update-ticker! [^Monitor monitor]
  (if (active-runs? @(.-state monitor))
    (start-ticker! monitor)
    (stop-ticker! monitor)))

(defn monitor
  "Create or inspect a live Run monitor.

  With no argument or an options map, create an event observer to pass as
  `run!`'s `:observe` function. With an existing monitor, return it so the REPL
  prints its current tree. Use `monitor-state` for ordinary Clojure data.

  `{:display :tree}` redraws a live tree on `:out`, which defaults to the
  current `*out*`."
  ([] (monitor {}))
  ([value]
   (if (instance? Monitor value)
     value
     (let [{:keys [display out]
            :or {out *out*}} value]
       (when-not (map? value)
         (throw (ex-info "monitor expects an options map or an existing monitor"
                         {:value value})))
       (when-not (contains? #{nil :tree} display)
         (throw (ex-info "Run monitor :display must be nil or :tree"
                         {:display display})))
       (when-not (instance? Writer out)
         (throw (ex-info "Run monitor :out must be a java.io.Writer"
                         {:out out})))
       (Monitor. (atom {:karcarthy/type :monitor-snapshot
                        :runs {}
                        :run-order []
                        :now-ms nil})
                 display out (atom 0) (atom nil) (Object.))))))
