(ns example.chat
  "A minimal terminal chat built from an Agent, run!, and a Session."
  (:require [karcarthy :as k]))

(def assistant
  (k/agent
   {:name "chat-assistant"
    :model "gpt-5.6"
    :instructions "Be helpful and concise."}))

(def session (k/session))
(def last-run (atom nil))

(defn ask!
  ([input]
   (ask! input nil))
  ([input on-event]
   (let [run (k/run! assistant input
                     (cond-> {:session session}
                       on-event (assoc :on-event on-event)))]
     (reset! last-run run)
     run)))

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
        (let [streamed? (atom false)
              on-event (fn [event]
                         (when (= :model/text-delta (:type event))
                           (reset! streamed? true)
                           (print (:delta event))
                           (flush)))]
          (print "agent> ")
          (flush)
          (let [run (ask! input on-event)]
            (if (= :completed (:status run))
              (if @streamed?
                (println)
                (println (:output run)))
              (println "error:" (get-in run [:error :message]))))
          (recur))))))

(defn -main [& _]
  (chat!))
