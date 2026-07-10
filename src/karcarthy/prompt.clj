(ns karcarthy.prompt
  "Renders the packaged system.md template for a workspace Agent."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as core])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Paths]
           [java.time LocalDate]))

(def ^:private no-link-options (make-array LinkOption 0))

(def ^:private system-template
  (delay
    (if-let [resource (io/resource "karcarthy/system.md")]
      (slurp resource)
      (throw (ex-info "Missing packaged system prompt"
                      {:resource "karcarthy/system.md"})))))

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

(defn- section [heading content]
  (if (seq content)
    (str heading "\n\n" content "\n\n")
    ""))

(defn- render [template replacements]
  (str/replace template #"\{\{([a-z_]+)\}\}"
               (fn [[placeholder key]]
                 (let [key (keyword key)]
                   (if (contains? replacements key)
                     (str (get replacements key))
                     (throw (ex-info "Unknown system prompt placeholder"
                                     {:placeholder placeholder})))))))

(defn workspace
  "Render system.md with the capabilities and workspace context actually exposed."
  [{:keys [cwd tools append]}]
  (let [root (absolute-path (or cwd "."))
        guidance (project-guidance root)]
    (render @system-template
            {:tools (str/join "\n" (map tool-summary tools))
             :project_instructions
             (section "## Project instructions from AGENTS.md or CLAUDE.md"
                      guidance)
             :additional_instructions
             (section "## Additional instructions" append)
             :current_date (LocalDate/now)
             :cwd root})))
