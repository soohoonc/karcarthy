(ns example.chat
  "A minimal terminal chat built from an Agent, run!, and a Session."
  (:require [karcarthy :as k]))

(def assistant
  (k/agent
   {:name "chat-assistant"
    :model "gpt-5.6"
    :instructions "Be helpful and concise."
    :output-schema string?}))

(def session (k/session))
(def last-run (atom nil))

(defn ask! [input]
  (let [run (k/run! assistant input {:session session})]
    (reset! last-run run)
    run))

(defn reset-chat! []
  (k/clear-session! session))

(defn chat! []
  (println "Type /reset to clear the conversation or /quit to exit.")
  (loop []
    (print "you> ")
    (flush)
    (when-let [input (read-line)]
      (case input
        "/quit" :done
        "/reset" (do (reset-chat!)
                      (println "Conversation cleared.")
                      (recur))
        (let [run (ask! input)]
          (if (= :completed (:status run))
            (println "agent>" (:output run))
            (println "error>" (get-in run [:error :message])))
          (recur))))))
