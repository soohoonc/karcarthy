(ns karcarthy.context
  "Small prompt values for constructing model-visible context."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private packaged-system-prompt
  (delay
    (if-let [resource (io/resource "karcarthy/system.md")]
      (with-open [reader (io/reader resource :encoding "UTF-8")]
        (slurp reader))
      (throw (ex-info "Missing packaged system prompt"
                      {:resource "karcarthy/system.md"})))))

(defn system-prompt
  "Return karcarthy's readable, packaged system.md prompt."
  []
  @packaged-system-prompt)

(defn prompt-file
  "Read a UTF-8 prompt from a file. The returned string is ordinary Agent
  context and carries no filesystem or working-directory semantics."
  [path]
  (with-open [reader (io/reader (io/file path) :encoding "UTF-8")]
    (slurp reader)))

(defn- resolve-part [part turn]
  (let [value (if (fn? part) (part turn) part)]
    (cond
      (nil? value) nil
      (string? value) value
      :else (throw (ex-info "Prompt parts must resolve to strings or nil"
                            {:value value})))))

(defn prompt
  "Compose prompt strings and turn-dependent prompt functions.

  Returns a context function suitable for Agent `:context`. Each function
  receives the context assembly view and must return a string or nil."
  [& parts]
  (fn [turn]
    (->> parts
         (map #(resolve-part % turn))
         (remove str/blank?)
         (str/join "\n\n"))))
