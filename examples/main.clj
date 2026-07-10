(load-file "examples/basic/main.clj")
(load-file "examples/coding/main.clj")

(ns main
  "Command dispatcher for the repository examples."
  (:require [clojure.main :as clojure-main]
            [example.basic :as basic]
            [example.coding :as coding]))

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
      "repl" (clojure-main/repl)
      (do
        (print help)
        (flush)))))
