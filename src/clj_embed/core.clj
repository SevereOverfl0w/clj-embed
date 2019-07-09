(ns clj-embed.core
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as deps.reader]
            [clojure.java.io :as io])
  (:import (java.net URL URLClassLoader)
           (java.util.regex Pattern)
           (java.io File)))

(def ^:const DEFAULT_DEPS
  {'org.projectodd.shimdandy/shimdandy-api  {:mvn/version "1.2.1"}
   'org.projectodd.shimdandy/shimdandy-impl {:mvn/version "1.2.1"}
   'org.clojure/tools.namespace             {:mvn/version "0.2.11"}})

(def ^:const RUNTIME_SHIM_CLASS
  "org.projectodd.shimdandy.impl.ClojureRuntimeShimImpl")

(defn- resolve-deps
  ([] (resolve-deps {}))
  ([deps]
   (deps/resolve-deps
     (deps.reader/merge-deps
       [(deps.reader/install-deps)
        {:deps DEFAULT_DEPS}
        {:deps deps}])
     nil)))

(defn- get-paths [lib-map]
  (mapcat :paths (vals lib-map)))

(defn- new-rt-shim [^ClassLoader classloader]
  (doto (.newInstance (.loadClass classloader RUNTIME_SHIM_CLASS))
    (.setClassLoader classloader)
    (.setName (name (gensym "clj-embed-runtime")))
    (.init)))

(defn construct-class-loader [classes]
  (URLClassLoader.
    (into-array
      (map #(.toURL (io/file %)) classes))
    ;; This is the boot class loader, the highest classloader, and importantly
    ;; the one without Clojure in.
    (.getParent (ClassLoader/getSystemClassLoader))))

;; public API

(defn close-runtime! [runtime]
  (.close runtime))

(defn eval-in-runtime [runtime code-as-string]
  (letfn [(call [fqsym code] (.invoke runtime fqsym code))]
    (call "clojure.core/load-string" code-as-string)))

(defmacro with-runtime [runtime & body]
  (let [text (pr-str (conj body 'do))]
    `(eval-in-runtime ~runtime ~text)))

(defmacro with-piped-runtime [runtime & body]
  (let [text (pr-str (conj body 'do))]
    `(.invoke runtime "clj-embed.shims/piped-load-string" *in* *out* *err* ~text)))

(defn start-repl-session
  ([runtime] (start-repl-session runtime *in* *out* *err*))
  ([runtime input output error]
   (.invoke runtime "clj-embed.shims/start-repl-session" input output error)))

(defn refresh-namespaces! [runtime]
  (with-runtime runtime
    (require '[clojure.tools.namespace.repl])
    (clojure.tools.namespace.repl/refresh)))

(defn load-namespaces! [runtime & directories]
  (let [code `(do
                (require '[clojure.tools.namespace.repl])
                (clojure.tools.namespace.repl/set-refresh-dirs ~@directories)
                (clojure.tools.namespace.repl/refresh-all))]
    (eval-in-runtime runtime (pr-str code))))

(defn load-shim-lib [runtime]
  (let [runtime-shim (slurp (io/resource "shims.clj"))]
    (eval-in-runtime runtime runtime-shim)
    runtime))

(defn new-runtime
  ([] (new-runtime {}))
  ([deps]
   (->> deps
        (resolve-deps)
        (get-paths)
        (construct-class-loader)
        (new-rt-shim)
        (load-shim-lib))))

(defmacro with-temporary-runtime [& body]
  `(let [runtime# (new-runtime)]
     (try (with-runtime runtime# ~@body)
          (finally (close-runtime! runtime#)))))
