(ns karcarthy.cli
  "Small executable entry point. Agent programs are authored in Clojure; the
  former JSON workflow CLI has been removed."
  (:gen-class)
  (:require [karcarthy.acp :as acp]
            [karcarthy.demo :as demo]))

(def help
  (str "karcarthy - native Clojure agent harness\n\n"
       "Usage:\n"
       "  karcarthy demo [input]   run the offline model/tool-loop demo\n"
       "  karcarthy acp ns/var     serve an Agent or Agent factory over ACP\n"
       "  karcarthy --help         show this help\n\n"
       "Define programs with karcarthy/defagent and run them with karcarthy/run!.\n"))

(defn -main [& args]
  (let [command (first args)]
    (cond
      (= "demo" command)
      (apply demo/-main (rest args))

      (= "acp" command)
      (if-let [qualified-symbol (second args)]
        (acp/serve-var! qualified-symbol)
        (do
          (binding [*out* *err*]
            (println "Usage: karcarthy acp namespace/agent-var"))
          (System/exit 2)))

      (or (nil? command) (contains? #{"--help" "-h"} command))
      (do (print help) (flush))

      :else
      (do
        (binding [*out* *err*]
          (println "Unknown command:" command)
          (print help)
          (flush))
        (System/exit 2)))))
