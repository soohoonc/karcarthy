(load-file "examples/basic/main.clj")
(load-file "examples/architect/main.clj")
(load-file "examples/coding/main.clj")

(ns main
  "Command dispatcher for the repository examples."
  (:require [clojure.main :as clojure-main]
            [example.architect :as architect]
            [example.basic :as basic]
            [example.coding :as coding]))

(def help
  (str "karcarthy examples\n\n"
       "Usage:\n"
       "  clojure -M:examples basic [input]\n"
       "  clojure -M:examples architect [task]\n"
       "  clojure -M:examples coding <directory> <task>\n"
       "  clojure -M:examples repl\n"))

(defn repl-print [value]
  (when-not (or (nil? value) (var? value))
    (prn value)))

(defn repl-read [request-prompt request-exit]
  (loop [value (clojure-main/repl-read request-prompt request-exit)]
    (if (identical? value request-prompt)
      (recur (clojure-main/repl-read request-prompt request-exit))
      value)))

(defn -main [& args]
  (let [[command & command-args] args]
    (case command
      "basic" (apply basic/-main command-args)
      "architect" (apply architect/-main command-args)
      "coding" (apply coding/-main command-args)
      "repl" (clojure-main/repl :read repl-read :print repl-print)
      (do
        (print help)
        (flush)))))
