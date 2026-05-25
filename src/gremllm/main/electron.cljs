(ns gremllm.main.electron
  "Guarded access to Electron main-process modules.
   Returns nil when running outside Electron (e.g. in tests).")

(defn- require-electron-main []
  (when (exists? js/require)
    (try
      (js/require "electron/main")
      (catch :default _ nil))))

(defn get-dialog []
  (some-> (require-electron-main) .-dialog))

(defn get-browser-window []
  (some-> (require-electron-main) .-BrowserWindow))

(defn get-app []
  (some-> (require-electron-main) .-app))
