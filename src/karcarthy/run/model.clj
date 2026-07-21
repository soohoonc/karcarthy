(ns karcarthy.run.model
  "Model transport calls for an active Run."
  (:require [karcarthy.agent :refer [normalize-model]]
            [karcarthy.run.context :as context]
            [karcarthy.schema :refer [fail! throwable->failure]])
  (:import [java.util.concurrent Callable]))

(defn mock-model
  "Deterministic in-process model transport for tests."
  [respond]
  (when-not (fn? respond)
    (throw (IllegalArgumentException. "mock-model requires a function")))
  {:karcarthy/type :model-transport
   :complete respond})

(defn- resolve-value [value run-context]
  (if (fn? value)
    (value (context/run-context run-context))
    value))

(defn- resolve-transport [run-context model]
  (let [configured (:transport model)
        transport
        (if (keyword? configured)
          (or (get (:model-transports run-context) configured)
              (when (= :responses configured)
                (deref (requiring-resolve
                        'karcarthy.model.responses/transport))))
          configured)]
    (or transport
        (fail! :model :configuration
               "No model transport is configured"
               {:transport configured :model model}))))

(defn- call-transport [transport request emit-delta!]
  (cond
    (and (map? transport)
         (not= false (get-in request [:model :stream]))
         (fn? (:stream transport)))
    ((:stream transport) request emit-delta!)

    (and (map? transport) (fn? (:complete transport)))
    ((:complete transport) request)

    (ifn? transport) (transport request)

    :else
    (fail! :model :configuration "Invalid model transport"
           {:transport transport})))

(defn- record-delta! [run-context delta]
  (context/check-run! run-context)
  (case (:type delta)
    :text-delta
    (context/emit! run-context
                   {:type :model/text-delta :delta (:delta delta)})

    :tool-call-delta
    (context/emit! run-context
                   (assoc (dissoc delta :type)
                          :type :model/tool-call-delta))

    (context/emit! run-context
                   {:type :model/stream-event :event delta})))

(defn- response [value]
  (cond
    (string? value) {:type :final :output value}
    (not (map? value)) {:type :final :output value}
    (= :final (:type value)) value
    (= :tool-calls (:type value)) value
    (seq (:tool-calls value))
    (assoc value :type :tool-calls :calls (:tool-calls value))
    (contains? value :output) (assoc value :type :final)
    :else (fail! :model :response
                 "Model transport returned an invalid response"
                 {:response value})))

(defn model!
  "Call the active model transport with one normalized request."
  ([request]
   (model! (context/current-run-context) request))
  ([run-context request]
   (context/check-run! run-context)
   (context/consume! run-context :model-calls 1)
   (let [model (normalize-model
                (resolve-value (:model request) run-context))
         transport (resolve-transport run-context model)
         request (assoc request :model model)
         started (System/nanoTime)]
     (context/emit! run-context
                    {:type :model/requested
                     :model (select-keys model [:provider :transport :id])})
     (try
       (let [child (.submit (:executor run-context)
                            ^Callable
                            (reify Callable
                              (call [_]
                                (binding [context/*run* run-context]
                                  (call-transport
                                   transport request
                                   #(record-delta! run-context %))))))
             response (response (context/await-future! run-context child))
             usage (:usage response)]
         (when-let [amount (or (:input-tokens usage)
                               (:input_tokens usage))]
           (context/consume! run-context :input-tokens amount))
         (when-let [amount (or (:output-tokens usage)
                               (:output_tokens usage))]
           (context/consume! run-context :output-tokens amount))
         (context/emit!
          run-context
          {:type :model/completed
           :duration-ms (/ (double (- (System/nanoTime) started))
                           1000000.0)
           :response-type (:type response)
           :usage usage})
         response)
       (catch Throwable error
         (context/emit!
          run-context
          {:type :model/failed
           :duration-ms (/ (double (- (System/nanoTime) started))
                           1000000.0)
           :error (throwable->failure error)})
         (if (= :failure (:karcarthy/type (ex-data error)))
           (throw error)
           (fail! :model :request (or (ex-message error) (str error))
                  nil error)))))))
