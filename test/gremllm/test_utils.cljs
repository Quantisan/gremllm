(ns gremllm.test-utils
  (:require [gremllm.main.io :as io]
            ["os" :as os]
            ["fs" :as fs])
  (:require-macros [gremllm.test-utils]))

(defn with-temp-dir
  "Creates a temporary directory, executes f with its path, then cleans up."
  [suffix f]
  (let [dir (io/path-join (os/tmpdir)
                          (str "gremllm-test-" (random-uuid) "-" suffix))]
    (try
      (io/ensure-dir dir)
      (f dir)
      (finally
        (when (io/file-exists? dir)
          (.rmSync fs dir #js {:recursive true :force true}))))))
