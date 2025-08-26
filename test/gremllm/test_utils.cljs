(ns gremllm.test-utils
  (:require [gremllm.main.io :as io]))

(defn with-temp-dir 
  "Creates a temporary directory, executes function f with the directory path,
   then cleans up the directory and its contents."
  [suffix f]
  (let [os  (js/require "os")
        dir (io/path-join (.tmpdir os) (str "gremllm-test-" (.getTime (js/Date.)) "-" suffix))]
    (try
      (io/ensure-dir dir)
      (f dir)
      (finally
        (when (io/file-exists? dir)
          (doseq [file (io/read-dir dir)]
            (io/delete-file (io/path-join dir file)))
          (io/remove-dir dir))))))
