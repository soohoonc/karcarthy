(ns karcarthy.examples
  "Examples-only command dispatcher. This namespace is not part of the library artifact."
  (:require [clojure.main :as main]
            [karcarthy.examples.dynamic :as dynamic]
            [karcarthy.examples.hello :as hello]))

(def help
  (str "karcarthy examples\n\n"
       "Usage:\n"
       "  clojure -M:examples hello [input]\n"
       "  clojure -M:examples dynamic\n"
       "  clojure -M:examples hill-climb\n"
       "  clojure -M:examples repl\n"))

(defn -main [& args]
  (let [[command & command-args] args]
    (case command
      "hello" (apply hello/-main command-args)
      "dynamic" (dynamic/run-dynamic-demo!)
      "hill-climb" (dynamic/run-hill-climb-demo!)
      "repl" (main/repl)
      (do
        (print help)
        (flush)))))
