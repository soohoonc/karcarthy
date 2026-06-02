(ns build
  "Build script for karcarthy.

      clojure -T:build jar      ; build the library jar into target/
      clojure -T:build uber     ; build the executable standalone jar
      clojure -T:build all      ; build both jars
      clojure -T:build install  ; build + install to the local Maven repo (~/.m2)
      clojure -T:build clean    ; remove target/"
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.soohoonc/karcarthy)
(def version "0.0.2")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn- prep []
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url "https://github.com/soohoonc/karcarthy"}})
  ;; ship sources + resources (the openai runner) - Clojure libs distribute source
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir}))

(defn- compile-main []
  (b/compile-clj {:basis      @basis
                  :src-dirs   ["src"]
                  :class-dir  class-dir
                  :ns-compile ['karcarthy.cli]}))

(defn jar [_]
  (clean nil)
  (prep)
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Wrote" jar-file))

(defn uber [_]
  (clean nil)
  (prep)
  (compile-main)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'karcarthy.cli})
  (println "Wrote" uber-file))

(defn all [_]
  (clean nil)
  (prep)
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Wrote" jar-file)
  (compile-main)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'karcarthy.cli})
  (println "Wrote" uber-file))

(defn install [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :class-dir class-dir
              :jar-file  jar-file})
  (println "Installed" lib version))
