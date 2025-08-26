(ns gremllm.test-utils
  (:require [gremllm.main.io :as io]))

(defn with-temp-dir 
  "Creates a temporary directory, executes function f with the directory path,
   then cleans up the directory and its contents."
  [suffix f]
  (let [os  (js/require "os")
        fs  (js/require "fs")
        dir (io/path-join (.tmpdir os) (str "gremllm-test-" (.getTime (js/Date.)) "-" suffix))]
    (try
      (io/ensure-dir dir)
      (f dir)
      (finally
        (when (io/file-exists? dir)
          ;; Use fs.rmSync with recursive option to handle nested directories
          (.rmSync fs dir #js {:recursive true :force true}))))))
