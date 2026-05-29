(ns build
  "Build script for karcarthy.

      clojure -T:build jar      ; build a jar into target/
      clojure -T:build install  ; build + install to the local Maven repo (~/.m2)
      clojure -T:build clean    ; remove target/"
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.soohoonc/karcarthy)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url "https://github.com/soohoonc/karcarthy"}})
  ;; ship sources + resources (the openai runner) — Clojure libs distribute source
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Wrote" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :class-dir class-dir
              :jar-file  jar-file})
  (println "Installed" lib version))
