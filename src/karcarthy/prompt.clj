(ns karcarthy.prompt
  "Capability-derived system instructions for a workspace Agent."
  (:require [clojure.string :as str]
            [karcarthy.core :as core])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Paths]
           [java.time LocalDate]))

(def ^:private no-link-options (make-array LinkOption 0))

(defn- absolute-path [value]
  (-> (Paths/get (str value) (make-array String 0))
      (.toAbsolutePath)
      (.normalize)))

(defn- bounded [value max-chars]
  (subs value 0 (min (count value) max-chars)))

(defn- tool-summary [tool]
  (cond
    (core/tool? tool) (str "- " (:name tool) ": " (:description tool))
    (core/hosted-tool? tool)
    (str "- " (or (get-in tool [:spec :name])
                   (get-in tool [:spec :type])
                   "hosted-tool")
         ": transport-executed capability")
    :else "- unknown tool"))

(defn- project-guidance [root]
  (let [agent-file (.resolve root "AGENTS.md")
        claude-file (.resolve root "CLAUDE.md")
        file (cond
               (Files/isRegularFile agent-file no-link-options) agent-file
               (Files/isRegularFile claude-file no-link-options) claude-file)]
    (when file
      (bounded (Files/readString file StandardCharsets/UTF_8) 30000))))

(defn workspace
  "Build compact system instructions from the capabilities actually exposed."
  [{:keys [cwd tools append]}]
  (let [root (absolute-path (or cwd "."))
        guidance (project-guidance root)]
    (str
     "You are an agent operating inside the karcarthy harness. Work on the "
     "user's request until it is resolved, using the capabilities listed "
     "below. Tool schemas are authoritative; never invent a capability.\n\n"
     "Available tools:\n"
     (str/join "\n" (map tool-summary tools))
     "\n\nGuidelines:\n"
     "- Inspect relevant files before editing and preserve unrelated changes.\n"
     "- Prefer read/search/edit/write over shell equivalents when they fit.\n"
     "- Use bash for tests, builds, version control, and commands without a dedicated tool.\n"
     "- Make focused changes, avoid destructive operations, and verify proportionally to risk.\n"
     "- Lead the final response with the outcome, evidence, and any remaining caveat.\n"
     (when guidance
       (str "\nProject instructions from AGENTS.md or CLAUDE.md:\n" guidance "\n"))
     (when (seq append) (str "\nAdditional instructions:\n" append "\n"))
     "\nCurrent date: " (LocalDate/now)
     "\nCurrent working directory: " root)))
