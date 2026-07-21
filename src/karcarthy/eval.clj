(ns karcarthy.eval
  "Same-process evaluation of one model-authored Clojure expression."
  (:require [clojure.walk :as walk]
            [karcarthy.agent :as agent]
            [karcarthy.contract :as contract]
            [karcarthy.run :as run]
            [karcarthy.tool :as tool])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

(defn- deepest-message [^Throwable t]
  (loop [current t]
    (if-let [cause (.getCause current)]
      (recur cause)
      (or (ex-message current) (str current)))))

(defn read-expression
  "Read exactly one Clojure expression with reader evaluation disabled."
  [source]
  (when-not (string? source)
    (contract/fail! :read :eval "Eval code must be a string" {:value source}))
  (try
    (let [reader (LineNumberingPushbackReader. (StringReader. source))
          eof (Object.)
          expression (binding [*read-eval* false] (read {:eof eof} reader))
          extra (binding [*read-eval* false] (read {:eof eof} reader))]
      (when (identical? eof expression)
        (contract/fail! :read :eval "Eval code is empty"))
      (when-not (identical? eof extra)
        (contract/fail! :read :eval
                        "Eval code must contain exactly one top-level expression"
                        {:extra extra}))
      expression)
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Throwable t
      (contract/fail! :read :eval (or (ex-message t) (str t)) nil t))))

(defn- eval-ns!
  [rt]
  (let [ns-sym (:eval-namespace rt)
        existing (find-ns ns-sym)
        ns-obj (or existing (create-ns ns-sym))]
    (binding [*ns* ns-obj]
      (when-not existing
        (clojure.core/refer 'clojure.core)
        (when-let [parent-sym (:eval-parent-namespace rt)]
          (when-let [parent (find-ns parent-sym)]
            (let [available (->> (keys (ns-publics parent))
                                 (remove #(ns-resolve ns-obj %))
                                 vec)]
              (when (seq available)
                (clojure.core/refer parent-sym :only available)))))
        (doseq [sym '[agent defagent tool deftool run! context model! emit!
                      definition expansion]]
          (when (ns-resolve ns-obj sym)
            (ns-unmap ns-obj sym)))
        (clojure.core/refer
         'karcarthy.agent
         :only '[agent defagent definition expansion])
        (clojure.core/refer 'karcarthy.tool :only '[tool deftool])
        (clojure.core/refer
         'karcarthy.run
         :only '[run! context model! emit!]))
      (doseq [[sym value] (:eval-bindings rt)]
        (when (ns-resolve ns-obj sym)
          (ns-unmap ns-obj sym))
        (intern ns-obj sym value)))
    ns-obj))

(defn- macroexpand-expression [form]
  (let [expanded (macroexpand form)]
    (if (and (seq? expanded)
             (contains? #{'quote 'clojure.core/quote} (first expanded)))
      expanded
      (walk/walk macroexpand-expression identity expanded))))

(declare model-value)

(defn- model-map [value]
  (into {}
        (map (fn [[k v]]
               [(cond
                  (or (string? k) (keyword? k)) k
                  (symbol? k) (str k)
                  :else (contract/fail!
                         :evaluation :output
                         "Eval returned a map with an unsupported key" {:key k}))
                (model-value v)]))
        value))

(defn- model-value
  "Restrict an eval Tool result to values a model transport can encode."
  [value]
  (cond
    (or (nil? value) (string? value) (number? value)
        (instance? Boolean value)) value
    (keyword? value) (name value)
    (symbol? value) (str value)
    (agent/agent? value) {:karcarthy/type "agent" :name (:name value)}
    (tool/tool? value) {:karcarthy/type "tool" :name (:name value)}
    (map? value) (model-map value)
    (sequential? value) (mapv model-value value)
    (set? value) (mapv model-value value)
    :else (contract/fail!
           :evaluation :output
           "Eval returned a value that cannot be sent to the model"
           {:class (.getName (class value))})))

(defn ^:no-doc eval-in-run!
  "Evaluate one Clojure expression in `rt`, with `input` lexically available."
  [rt code input]
  (run/consume! rt :evals 1)
  (let [ordinal (swap! (:eval-counter rt) inc)
        rt (assoc rt :eval-namespace
                  (symbol (str (:eval-namespace rt) ".expr_" ordinal)))
        started (System/nanoTime)]
    (run/emit! rt {:type :eval/started :code code :input input})
    (try
      (let [ns-obj (eval-ns! rt)
            expression (binding [*ns* ns-obj]
                         (read-expression code))
            expansion (binding [*ns* ns-obj]
                        (macroexpand-expression expression))]
        (run/emit! rt {:type :eval/expanded
                       :expression expression
                       :expansion expansion
                       :namespace (ns-name ns-obj)})
        (intern ns-obj 'input input)
        (let [value (binding [*ns* ns-obj run/*run* rt]
                      (clojure.core/eval expression))
              value (model-value value)]
          (run/emit! rt {:type :eval/completed
                         :duration-ms (/ (double (- (System/nanoTime) started))
                                         1000000.0)
                         :value value})
          value))
      (catch Throwable t
        (let [structured? (= :failure (:karcarthy/type (ex-data t)))
              failure (if structured?
                        (contract/throwable->failure t)
                        (contract/failure :evaluation :evaluation
                                          (deepest-message t) {:code code}))]
          (run/emit! rt {:type :eval/failed
                         :phase (:phase failure)
                         :duration-ms (/ (double (- (System/nanoTime) started))
                                         1000000.0)
                         :error failure})
          (if structured?
            (throw t)
            (contract/fail! :evaluation :evaluation (deepest-message t)
                            {:code code} t)))))))
