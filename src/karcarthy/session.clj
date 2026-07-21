(ns karcarthy.session
  "Conversation history managed across Agent runs."
  (:import [java.util UUID]))

(defprotocol Session
  "Conversation history shared across Agent runs."
  (session-id [session]
    "Return the stable identifier for this Session.")
  (get-items [session]
    "Return stored conversation items in chronological order.")
  (add-items! [session items]
    "Append conversation items to this Session.")
  (pop-item! [session]
    "Remove and return the most recent item, or nil when empty.")
  (clear-session! [session]
    "Remove every item from this Session."))

(defn session?
  "Return true when value implements Session."
  [value]
  (satisfies? Session value))

(defn session
  "Create a process-local Session for tests, CLIs, and ACP sessions.

  Options are `:id` and initial `:items`. The contents are lost when the
  process exits. Implement Session with application storage for durability."
  ([] (session {}))
  ([{:keys [id items]
     :or {id (str "session_" (UUID/randomUUID))
          items []}}]
   (let [stored-items (atom (vec items))]
     (reify Session
       (session-id [_] id)
       (get-items [_] @stored-items)
       (add-items! [this new-items]
         (swap! stored-items into (vec new-items))
         this)
       (pop-item! [_]
         (loop []
           (let [before @stored-items]
             (when (seq before)
               (let [item (peek before)]
                 (if (compare-and-set! stored-items before (pop before))
                   item
                   (recur)))))))
       (clear-session! [this]
         (reset! stored-items [])
         this)))))
