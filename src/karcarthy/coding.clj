(ns karcarthy.coding
  "A deliberately small coding-agent profile: Pi's four orthogonal local
  tools, a ripgrep search tool, and a prompt derived from actual capabilities."
  (:refer-clojure :exclude [agent read])
  (:require [clojure.string :as str]
            [karcarthy.core :as core]
            [karcarthy.model.openai :as openai])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path Paths StandardOpenOption]
           [java.time LocalDate]
           [java.util.concurrent TimeUnit]))

(def ^:private no-link-options (make-array LinkOption 0))

(defn- absolute-path [value]
  (-> (Paths/get (str value) (make-array String 0))
      (.toAbsolutePath)
      (.normalize)))

(defn- existing-ancestor [^Path path]
  (loop [candidate path]
    (cond
      (nil? candidate) nil
      (Files/exists candidate no-link-options) candidate
      :else (recur (.getParent candidate)))))

(defn- workspace-path [^Path root value]
  (when-not (and (string? value) (not (str/blank? value)))
    (core/fail! :contract :tool-input "path must be a non-empty string"
                {:path value}))
  (let [given (Paths/get value (make-array String 0))
        candidate (-> (if (.isAbsolute given) given (.resolve root given))
                      (.toAbsolutePath)
                      (.normalize))]
    (when-not (.startsWith candidate root)
      (core/fail! :tool :path "Path escapes the coding workspace"
                  {:path value :workspace (str root)}))
    (when-let [ancestor (existing-ancestor candidate)]
      (let [real-root (.toRealPath root no-link-options)
            real-ancestor (.toRealPath ancestor no-link-options)]
        (when-not (.startsWith real-ancestor real-root)
          (core/fail! :tool :path "Path resolves outside the coding workspace"
                      {:path value :workspace (str root)}))))
    candidate))

(defn- approval [options tool-name]
  (let [configured (:approval options)]
    (cond
      (map? configured) (get configured tool-name :never)
      (nil? configured) :never
      :else configured)))

(defn- bounded-string [value max-chars]
  (if (> (count value) max-chars)
    {:text (subs value 0 max-chars) :truncated? true}
    {:text value :truncated? false}))

(defn- read-stream [stream max-bytes]
  (with-open [input stream
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [kept 0
             truncated? false]
        (let [n (.read input buffer)]
          (if (neg? n)
            {:text (.toString output StandardCharsets/UTF_8)
             :truncated? truncated?}
            (let [remaining (max 0 (- max-bytes kept))
                  write-n (min n remaining)]
              (when (pos? write-n) (.write output buffer 0 write-n))
              (recur (+ kept write-n) (or truncated? (> n write-n))))))))))

(defn- command-vector [command]
  (if (str/includes? (str/lower-case (System/getProperty "os.name")) "win")
    ["cmd.exe" "/d" "/s" "/c" command]
    ["/bin/sh" "-lc" command]))

(defn- run-process [^Path root command timeout-ms max-output-bytes]
  (let [builder (doto (ProcessBuilder. ^java.util.List (vec command))
                  (.directory (.toFile root))
                  (.redirectErrorStream true))
        process (.start builder)
        output (future (read-stream (.getInputStream process) max-output-bytes))
        completed? (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)]
    (when-not completed?
      (.destroyForcibly process)
      (.waitFor process 5 TimeUnit/SECONDS))
    (let [{:keys [text truncated?]}
          (deref output 5000 {:text "Process output did not close"
                              :truncated? true})]
      {:exit_code (if completed? (.exitValue process) 124)
       :output text
       :timed_out? (not completed?)
       :truncated? truncated?})))

(defn- occurrences [text needle]
  (loop [from 0 matches 0]
    (let [index (.indexOf ^String text ^String needle (int from))]
      (if (neg? index)
        matches
        (recur (+ index (max 1 (count needle))) (inc matches))))))

(defn- replace-once [text old-text new-text]
  (let [index (.indexOf ^String text ^String old-text)]
    (str (subs text 0 index)
         new-text
         (subs text (+ index (count old-text))))))

(defn- read-tool [root options]
  (core/make-tool
   {:name "read"
    :description
    "Read a UTF-8 text file inside the workspace. Use offset and limit for large files. Read a file before editing it."
    :input {:type "object"
            :properties
            {"path" {:type "string"}
             "offset" {:type "integer" :minimum 1}
             "limit" {:type "integer" :minimum 1 :maximum 5000}}
            :required ["path"]
            :additionalProperties false}
    :output map?
    :approval (approval options :read)}
   '(coding/read) nil
   (fn [_ {:keys [path offset limit]}]
     (let [file (workspace-path root path)
           offset (long (or offset 1))
           limit (long (or limit 2000))]
       (when-not (Files/isRegularFile file no-link-options)
         (core/fail! :tool :read "File does not exist or is not regular"
                     {:path path}))
       (with-open [reader (Files/newBufferedReader file StandardCharsets/UTF_8)]
         (let [selected (->> (line-seq reader)
                             (drop (dec offset))
                             (take (inc limit))
                             vec)
               more? (> (count selected) limit)
               content (str/join "\n" (take limit selected))
               bounded (bounded-string content
                                       (long (or (:max-read-chars options)
                                                 100000)))]
           {:path (str (.relativize root file))
            :offset offset
            :content (:text bounded)
            :truncated? (or more? (:truncated? bounded))}))))))

(defn- write-tool [root options]
  (core/make-tool
   {:name "write"
    :description
    "Create or replace a UTF-8 text file inside the workspace. Prefer edit for targeted changes to an existing file. Parent directories are created."
    :input {:type "object"
            :properties {"path" {:type "string"}
                         "content" {:type "string"}}
            :required ["path" "content"]
            :additionalProperties false}
    :output map?
    :approval (approval options :write)}
   '(coding/write) nil
   (fn [_ {:keys [path content]}]
     (let [file (workspace-path root path)
           parent (.getParent file)]
       (when parent (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
       (Files/writeString file content StandardCharsets/UTF_8
                          (into-array StandardOpenOption
                                      [StandardOpenOption/CREATE
                                       StandardOpenOption/TRUNCATE_EXISTING
                                       StandardOpenOption/WRITE]))
       {:path (str (.relativize root file))
        :bytes (count (.getBytes ^String content StandardCharsets/UTF_8))}))))

(defn- edit-tool [root options]
  (core/make-tool
   {:name "edit"
    :description
    "Replace exact text in an existing UTF-8 file. By default old_text must occur exactly once; set replace_all only when every occurrence should change."
    :input {:type "object"
            :properties
            {"path" {:type "string"}
             "old_text" {:type "string" :minLength 1}
             "new_text" {:type "string"}
             "replace_all" {:type "boolean"}}
            :required ["path" "old_text" "new_text"]
            :additionalProperties false}
    :output map?
    :approval (approval options :edit)}
   '(coding/edit) nil
   (fn [_ {:keys [path old_text new_text replace_all]}]
     (let [file (workspace-path root path)]
       (when-not (Files/isRegularFile file no-link-options)
         (core/fail! :tool :edit "File does not exist or is not regular"
                     {:path path}))
       (let [text (Files/readString file StandardCharsets/UTF_8)
             count (occurrences text old_text)]
         (when (zero? count)
           (core/fail! :tool :edit "old_text was not found" {:path path}))
         (when (and (not replace_all) (not= 1 count))
           (core/fail! :tool :edit
                       "old_text is not unique; provide more context or set replace_all"
                       {:path path :occurrences count}))
         (let [updated (if replace_all
                         (str/replace text old_text new_text)
                         (replace-once text old_text new_text))]
           (Files/writeString file updated StandardCharsets/UTF_8
                              (into-array StandardOpenOption
                                          [StandardOpenOption/TRUNCATE_EXISTING
                                           StandardOpenOption/WRITE]))
           {:path (str (.relativize root file))
            :replacements (if replace_all count 1)}))))))

(defn- bash-tool [root options]
  (core/make-tool
   {:name "bash"
    :description
    "Run a shell command with the workspace as its working directory. Use read, search, edit, and write for file operations when they fit. Avoid destructive commands unless explicitly requested."
    :input {:type "object"
            :properties
            {"command" {:type "string" :minLength 1}
             "timeout_ms" {:type "integer" :minimum 1 :maximum 600000}}
            :required ["command"]
            :additionalProperties false}
    :output map?
    :approval (approval options :bash)}
   '(coding/bash) nil
   (fn [_ {:keys [command timeout_ms]}]
     (run-process root (command-vector command)
                  (long (or timeout_ms (:bash-timeout-ms options) 120000))
                  (long (or (:max-process-output-bytes options) 100000))))))

(defn- search-tool [root options]
  (core/make-tool
   {:name "search"
    :description
    "Search workspace file contents with ripgrep regular expressions. Use this instead of Bash grep for bounded, path-scoped results."
    :input {:type "object"
            :properties
            {"pattern" {:type "string" :minLength 1}
             "path" {:type "string"}
             "glob" {:type "string"}
             "max_results" {:type "integer" :minimum 1 :maximum 1000}}
            :required ["pattern"]
            :additionalProperties false}
    :output map?
    :approval (approval options :search)}
   '(coding/search) nil
   (fn [_ {:keys [pattern path glob max_results]}]
     (let [max-results (long (or max_results 200))
           target (workspace-path root (or path "."))
           command (cond-> ["rg" "--line-number" "--no-heading" "--color" "never"
                            "--max-count" (str max-results)]
                     glob (into ["--glob" glob])
                     true (into [pattern (str target)]))
           result (run-process root command
                               (long (or (:search-timeout-ms options) 30000))
                               (long (or (:max-process-output-bytes options) 100000)))]
       (if (contains? #{0 1} (:exit_code result))
         (let [lines (str/split-lines (:output result))
               truncated? (or (:truncated? result)
                              (> (count lines) max-results))]
           (assoc result
                  :output (str/join "\n" (take max-results lines))
                  :matches (min max-results (count lines))
                  :truncated? truncated?))
         (core/fail! :tool :search "ripgrep failed" result))))))

(defn tools
  "Build the minimal local coding toolset for one workspace."
  ([] (tools {}))
  ([options]
   (let [root (absolute-path (or (:cwd options) "."))]
     (when-not (Files/isDirectory root no-link-options)
       (core/fail! :contract :configuration
                   "Coding workspace must be an existing directory"
                   {:cwd (str root)}))
     [(read-tool root options)
      (write-tool root options)
      (edit-tool root options)
      (bash-tool root options)
      (search-tool root options)])))

(defn- tool-summary [tool]
  (cond
    (core/tool? tool) (str "- " (:name tool) ": " (:description tool))
    (core/hosted-tool? tool)
    (str "- " (or (get-in tool [:spec :name])
                   (get-in tool [:spec :type])
                   "hosted-tool")
         ": provider-executed capability")
    :else "- unknown tool"))

(defn- project-guidance [root]
  (let [agent-file (.resolve root "AGENTS.md")
        claude-file (.resolve root "CLAUDE.md")
        file (cond
               (Files/isRegularFile agent-file no-link-options) agent-file
               (Files/isRegularFile claude-file no-link-options) claude-file)]
    (when file
      (:text (bounded-string (Files/readString file StandardCharsets/UTF_8)
                             30000)))))

(defn instructions
  "Build a compact coding prompt from the capabilities actually exposed."
  [{:keys [cwd tools append]}]
  (let [root (absolute-path (or cwd "."))
        guidance (project-guidance root)]
    (str
     "You are a coding agent operating inside the karcarthy harness. Work on "
     "the user's request until it is resolved, using the capabilities listed "
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

(def ^:private coding-config-keys
  #{:cwd :coding-options :web-search?})

(defn agent
  "Construct a model-backed coding Agent with the minimal local tool profile.
  Set `:web-search? true` for OpenAI's hosted web-search tool and add arbitrary
  local or MCP Tools through normal `:tools`."
  [config]
  (let [cwd (or (:cwd config) ".")
        local-tools (tools (merge (:coding-options config) {:cwd cwd}))
        extra-tools (vec (or (:tools config) []))
        all-tools (cond-> (into local-tools extra-tools)
                    (:web-search? config) (conj (openai/web-search)))
        prompt (instructions {:cwd cwd
                              :tools all-tools
                              :append (:instructions config)})
        agent-config (-> (apply dissoc config coding-config-keys)
                         (assoc :instructions prompt
                                :tools all-tools
                                :input (or (:input config) any?)
                                :output (or (:output config) string?)))]
    (core/make-agent agent-config
                     `(karcarthy.coding/agent ~config)
                     nil nil)))
