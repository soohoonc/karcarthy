(ns karcarthy.eval
  "Read, expand, check, evaluate, and record model-authored Clojure Agents."
  (:refer-clojure :exclude [eval])
  (:require [clojure.walk :as walk]
            [karcarthy.core :as core])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

(declare evaluation-ns! compile-agent!)

(defn- deepest-message [^Throwable t]
  (loop [current t]
    (if-let [cause (.getCause current)]
      (recur cause)
      (or (ex-message current) (str current)))))

(defn- read-form*
  [source]
  (let [reader (LineNumberingPushbackReader. (StringReader. source))
        eof (Object.)
        form (read {:eof eof} reader)
        extra (read {:eof eof} reader)]
    (when (identical? eof form)
      (core/fail! :read :read "Agent source is empty"))
    (when-not (identical? eof extra)
      (core/fail! :read :read
                  "Agent source must contain exactly one top-level form"
                  {:extra extra}))
    form))

(defn read-agent-form
  "Read exactly one Clojure form with reader evaluation disabled."
  ([source]
   (read-agent-form nil source))
  ([rt source]
   (when-not (string? source)
     (core/fail! :read :read "Agent source must be a string"
                 {:value source}))
   (try
     (binding [*read-eval* false
               *ns* (if rt (evaluation-ns! rt) *ns*)]
       (read-form* source))
     (catch clojure.lang.ExceptionInfo e
       (throw e))
     (catch Throwable t
       (core/fail! :read :read (or (ex-message t) (str t)) nil t)))))

(defn- evaluation-ns!
  [rt]
  (let [ns-sym (:evaluation-namespace rt)
        existing (find-ns ns-sym)
        ns-obj (or existing (create-ns ns-sym))]
    (binding [*ns* ns-obj]
      (when-not existing
        (clojure.core/refer 'clojure.core)
        nil)
      ;; `agent` intentionally replaces clojure.core/agent in generated code.
      (when-let [mapping (ns-resolve ns-obj 'agent)]
        (when (= 'clojure.core (-> mapping meta :ns ns-name))
          (ns-unmap ns-obj 'agent)))
      (clojure.core/refer
       'karcarthy.core
       :only '[agent tool invoke! spawn! await! await-all! handoff!
               as-tool context model! emit! source-form expanded-form])
      (clojure.core/refer
       'karcarthy.eval
       :only '[read-agent-form check-agent-form! eval-agent-form!
               compile-agent!]))
    ns-obj))

(defn- macroexpand-all
  "Recursively macroexpand executable positions while preserving quoted data.
  `clojure.walk/macroexpand-all` expands inside `(quote ...)`, which is wrong for
  homoiconic Agents because their retained source form can itself contain
  macros."
  [form]
  (let [expanded (macroexpand form)]
    (if (and (seq? expanded)
             (contains? #{'quote 'clojure.core/quote} (first expanded)))
      expanded
      (walk/walk macroexpand-all identity expanded))))

(defn check-agent-form!
  "Macroexpand a form in the Runtime evaluation namespace. Returns a checked
  representation. Compiler symbol/arity failures that require evaluation are
  reported by `eval-agent-form!`."
  [rt form]
  (core/check-runtime! rt)
  (let [ns-obj (evaluation-ns! rt)]
    (try
      (let [expanded (binding [*ns* ns-obj]
                       (macroexpand-all form))
            checked {:karcarthy/type :checked-agent-form
                     :form form
                     :expanded-form expanded
                     :namespace (ns-name ns-obj)}]
        (core/emit! rt {:type :program/expanded
                        :form form
                        :expanded-form expanded
                        :namespace (ns-name ns-obj)})
        (core/emit! rt {:type :program/checked
                        :namespace (ns-name ns-obj)})
        checked)
      (catch Throwable t
        (core/emit! rt {:type :program/failed
                        :phase :expand
                        :error (core/throwable->failure t)})
        (core/fail! :expand :expand (or (ex-message t) (str t))
                    {:form form :namespace (ns-name ns-obj)} t)))))

(defn eval-agent-form!
  "Evaluate a checked form and require it to produce an Agent."
  [rt checked]
  (when-not (= :checked-agent-form (:karcarthy/type checked))
    (core/fail! :contract :evaluation
                "eval-agent-form! requires a checked Agent form"
                {:value checked}))
  (core/check-runtime! rt)
  (let [ns-obj (evaluation-ns! rt)]
    (try
      (let [value (binding [*ns* ns-obj]
                    (clojure.core/eval (:form checked)))]
        (when-not (core/agent? value)
          (core/fail! :evaluation :evaluation
                      "Generated form did not evaluate to an Agent"
                      {:form (:form checked) :value value}))
        (let [value (assoc value
                           :source-form (:form checked)
                           :expanded-form (:expanded-form checked)
                           :definition-ns (ns-name ns-obj))]
          (core/emit! rt {:type :program/evaluated
                          :agent (:name value)
                          :namespace (ns-name ns-obj)})
          value))
      (catch clojure.lang.ExceptionInfo e
        (core/emit! rt {:type :program/failed
                        :phase :evaluation
                        :error (core/throwable->failure e)})
        (throw e))
      (catch Throwable t
        (core/emit! rt {:type :program/failed
                        :phase :evaluation
                        :error (core/throwable->failure t)})
        (core/fail! :evaluation :evaluation (deepest-message t)
                    {:form (:form checked) :namespace (ns-name ns-obj)} t)))))

(defn compile-agent!
  "Read, expand, check, evaluate, and return an Agent."
  [rt source]
  (core/consume! rt :generated-forms 1)
  (core/emit! rt {:type :program/read :source source})
  (->> (read-agent-form rt source)
       (check-agent-form! rt)
       (eval-agent-form! rt)))
