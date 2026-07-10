(ns build
  "Build the Harbor example application without adding examples to the library artifact."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "dist/build/classes")
(def uber-file "dist/karcarthy-harbor-example-standalone.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "dist/build"})
  (b/delete {:path uber-file}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["../../src" "../../resources" "../src"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :src-dirs ["../../src" "../src"]
                  :class-dir class-dir
                  :ns-compile ['karcarthy.cli]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'karcarthy.cli})
  (println "Wrote" uber-file))
