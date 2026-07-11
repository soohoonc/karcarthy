(ns karcarthy.monitor
  "Live, event-derived views of running Agents."
  (:require [clojure.string :as str])
  (:import [java.io Writer]))

(def ^:private rendered-event-types
  #{:run/started :run/completed :run/failed :run/cancelled
    :agent/started :agent/completed :agent/failed
    :model/requested :model/completed :model/failed
    :tool/started :tool/completed :tool/failed
    :program/read :program/expanded :program/checked
    :program/evaluated :program/failed})

(defn- empty-run [event]
  {:id (:run-id event)
   :agent (:agent event)
   :status :running
   :started-at-ms (:time-ms event)
   :updated-at-ms (:time-ms event)
   :agents {}
   :agent-order []
   :agent-forms 0
   :created-agents []
   :usage nil
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
    (some #(= "agent" (:name %)) (vals tools)) :waiting-agents
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
                (assoc :activity (if (= "agent" (:tool event))
                                   :creating-agents
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

(defn- reduce-run-event [run event]
  (let [run (assoc run :updated-at-ms (:time-ms event))]
    (case (:type event)
      :run/started
      (assoc run :agent (:agent event) :status :running)

      :run/completed
      (assoc run :status :completed :usage (:usage event))

      :run/failed
      (assoc run :status :failed :error (:error event))

      :run/cancelled
      (assoc run :status :cancelled :error (:error event))

      :agent/started
      (start-agent run event)

      :agent/completed
      (update-agent run event finish-agent :completed event)

      :agent/failed
      (update-agent run event finish-agent :failed event)

      :model/requested
      (update-agent run event set-activity :model event)

      :model/completed
      (update-agent run event set-activity :running event)

      :model/failed
      (update-agent run event set-activity :model-failed event)

      :tool/started
      (update-agent run event start-tool event)

      :tool/completed
      (update-agent run event finish-tool event)

      :tool/failed
      (update-agent run event finish-tool event)

      :program/read
      (-> run
          (update :agent-forms inc)
          (update-agent event set-activity :program-read event))

      :program/expanded
      (update-agent run event set-activity :program-expanded event)

      :program/checked
      (update-agent run event set-activity :program-checked event)

      :program/evaluated
      (-> run
          (update :created-agents conj (:agent event))
          (update-agent event set-activity :program-evaluated event))

      :program/failed
      (update-agent run event set-activity :program-failed event)

      run)))

(defn- reduce-event [snapshot event]
  (let [snapshot (ensure-run snapshot event)
        run-id (:run-id event)]
    (if (and run-id (get-in snapshot [:runs run-id]))
      (update-in snapshot [:runs run-id] reduce-run-event event)
      snapshot)))

(defn- short-id [id]
  (if (> (count (str id)) 12)
    (str (subs (str id) 0 12) "…")
    (str id)))

(defn- active-tools [agent]
  (keep (:tools agent) (:tool-order agent)))

(defn- plural [n singular]
  (str n " " singular (when (not= n 1) "s")))

(defn- activity-label [agent]
  (case (:status agent)
    :completed "done"
    :failed "failed"
    (case (:activity agent)
      :model "calling model"
      :model-failed "model failed"
      :creating-agents "creating Agents"
      :waiting-agents
      (let [n (count (filter #(= "agent" (:name %)) (active-tools agent)))]
        (str "waiting for " (plural (max 1 n) "Agent")))
      :tools
      (let [tools (vec (active-tools agent))]
        (if (= 1 (count tools))
          (str "Tool: " (:name (first tools)))
          (plural (count tools) "Tool")))
      :program-read "reading Agent form"
      :program-expanded "expanding Agent form"
      :program-checked "checking Agent form"
      :program-evaluated "created Agent"
      :program-failed "Agent form failed"
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
  (let [forms (:agent-forms run)
        header (str "Run " (short-id (:id run)) " · " (name (:status run))
                    (when (pos? forms)
                      (str " · " (plural forms "Agent form"))))
        roots (vec (children run nil))]
    (into [header]
          (mapcat (fn [index agent]
                    (agent-lines run agent "" (= index (dec (count roots)))))
                  (range (count roots))
                  roots))))

(defn- snapshot-value [monitor-or-snapshot]
  (if (instance? clojure.lang.IDeref monitor-or-snapshot)
    @monitor-or-snapshot
    monitor-or-snapshot))

(defn monitor-view
  "Return the current Run and Agent tree as text."
  [monitor-or-snapshot]
  (let [snapshot (snapshot-value monitor-or-snapshot)
        runs (keep (:runs snapshot) (:run-order snapshot))]
    (str/join "\n\n" (map #(str/join "\n" (run-lines %)) runs))))

(defn print-monitor
  "Print one snapshot of a Run monitor."
  ([monitor] (print-monitor monitor *out*))
  ([monitor ^Writer out]
   (.write out (str (monitor-view monitor) "\n"))
   (.flush out)
   monitor))

(declare draw-tree!)

(deftype Monitor [state display out rendered-lines lock]
  clojure.lang.IDeref
  (deref [_] @state)

  clojure.lang.IFn
  (invoke [this event]
    (locking lock
      (swap! state reduce-event event)
      (when (and (= :tree display)
                 (contains? rendered-event-types (:type event)))
        (draw-tree! this)))
    nil))

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

(defn monitor
  "Create an event observer that tracks live Runs and Agents.

  Pass the result as `run!`'s `:observe` function. Dereference it for ordinary
  Clojure data. Use `{:display :tree}` to redraw a live tree on `:out`, which
  defaults to the current `*out*`."
  ([] (monitor {}))
  ([{:keys [display out]
     :or {out *out*}}]
   (when-not (contains? #{nil :tree} display)
     (throw (ex-info "Run monitor :display must be nil or :tree"
                     {:display display})))
   (when-not (instance? Writer out)
     (throw (ex-info "Run monitor :out must be a java.io.Writer"
                     {:out out})))
   (Monitor. (atom {:karcarthy/type :monitor-snapshot
                    :runs {}
                    :run-order []})
             display out (atom 0) (Object.))))
