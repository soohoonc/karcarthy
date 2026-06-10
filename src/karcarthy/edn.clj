(ns karcarthy.edn
  "Internal helpers for reading EDN fragments from model output."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn extract-map!
  "Pull the first EDN map out of `s`, which may contain prose or a fenced block.
  Prose may itself contain `{` characters; candidate positions are tried left
  to right until one parses as a map. Uses `clojure.edn/read-string` - data
  only, never evaluates code."
  [s]
  (let [s      (str s)
        fenced (re-find #"(?s)```(?:edn|clojure|clj)?\s*(.*?)```" s)
        body   (if fenced (second fenced) s)]
    (loop [idx (str/index-of body "{"), error nil]
      (if (nil? idx)
        (if error
          (throw (ex-info (str "could not parse EDN: " (.getMessage ^Exception error))
                          {:input s} error))
          (throw (ex-info "no EDN map found in output" {:input s})))
        (let [v (try
                  (edn/read-string (subs body idx))
                  (catch Exception e e))]
          (if (instance? Exception v)
            (recur (str/index-of body "{" (inc idx)) (or error v))
            v))))))
