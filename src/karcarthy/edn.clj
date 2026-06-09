(ns karcarthy.edn
  "Internal helpers for reading EDN fragments from model output."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn extract-map!
  "Pull the first EDN map out of `s`, which may contain prose or a fenced block.
  Uses `clojure.edn/read-string` - data only, never evaluates code."
  [s]
  (let [s      (str s)
        fenced (re-find #"(?s)```(?:edn|clojure|clj)?\s*(.*?)```" s)
        body   (if fenced (second fenced) s)
        idx    (str/index-of body "{")]
    (when (nil? idx)
      (throw (ex-info "no EDN map found in output" {:input s})))
    (try
      (edn/read-string (subs body idx))
      (catch Exception e
        (throw (ex-info (str "could not parse EDN: " (.getMessage e))
                        {:input s} e))))))
