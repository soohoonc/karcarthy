(ns karcarthy.prompt
  "Small prompt values for constructing Agent instructions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn prompt-file
  "Read a UTF-8 prompt from a file."
  [path]
  (with-open [reader (io/reader (io/file path) :encoding "UTF-8")]
    (slurp reader)))

(defn- resolve-part [part run-context]
  (let [value (if (fn? part) (part run-context) part)]
    (cond
      (nil? value) nil
      (string? value) value
      :else (throw (ex-info "Prompt parts must resolve to strings or nil"
                            {:value value})))))

(defn prompt
  "Compose strings and functions into dynamic Agent instructions.

  Each function receives the immutable run context and returns a string or nil."
  [& parts]
  (fn [run-context]
    (->> parts
         (map #(resolve-part % run-context))
         (remove str/blank?)
         (str/join "\n\n"))))
