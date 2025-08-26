(ns gremllm.main.effects.workspace
  (:require [gremllm.main.io :as io]))

(defn ls [workspace-dir]
  (if-not (io/file-exists? workspace-dir)
    []
    (->> (io/read-dir workspace-dir)
         vec)))
