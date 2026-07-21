(ns karcarthy.run
  "Public Run operations."
  (:refer-clojure :exclude [run!])
  (:require [karcarthy.run.agent :as agent]
            [karcarthy.run.context :as context]
            [karcarthy.run.model :as model]))

(defn context
  "Return the local, non-model-visible context for the current Agent call."
  ([]
   (context/context))
  ([run-context]
   (context/context run-context)))

(defn emit!
  "Record one observation event and notify the run observer."
  ([event]
   (context/emit! event))
  ([run-context event]
   (context/emit! run-context event)))

(defn events
  "Return the events recorded by a Run."
  [run]
  (context/events run))

(defn output
  "Return the output of a completed Run, or throw with the Run as data."
  [run]
  (context/output run))

(defn ^:no-doc current-run-context
  "Return the internal context for the Agent or Tool currently running."
  []
  (context/current-run-context))

(defn ^:no-doc check-run!
  [run-context]
  (context/check-run! run-context))

(defn ^:no-doc consume!
  "Atomically consume a shared run resource budget."
  [run-context resource amount]
  (context/consume! run-context resource amount))

(defn mock-model
  "Deterministic in-process model transport for tests."
  [respond]
  (model/mock-model respond))

(defn model!
  "Call the active model transport with one normalized request."
  ([request]
   (model/model! request))
  ([run-context request]
   (model/model! run-context request)))

(defn run!
  "Run an Agent and return a Run map.

  The first call establishes a run. Calls made during its dynamic extent join
  that run and therefore share its id, limits, usage, deadline, cancellation,
  approvals, events, executor, and context. Each Agent starts a fresh model
  conversation unless it is the first call with a Session."
  ([agent input]
   (agent/run! agent input))
  ([agent input options]
   (agent/run! agent input options)))
