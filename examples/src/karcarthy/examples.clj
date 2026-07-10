(ns karcarthy.examples
  "Examples-only command dispatcher. This namespace is not part of the library artifact."
  (:require [clojure.main :as main]
            [karcarthy.examples.basic :as basic]
            [karcarthy.examples.coding :as coding]))

(def help
  (str "karcarthy examples\n\n"
       "Usage:\n"
       "  clojure -M:examples basic [input]\n"
       "  clojure -M:examples coding <directory> <task>\n"
       "  clojure -M:examples repl\n"))

(defn -main [& args]
  (let [[command & command-args] args]
    (case command
      "basic" (apply basic/-main command-args)
      "coding" (apply coding/-main command-args)
      "repl" (main/repl)
      (do
        (print help)
        (flush)))))
