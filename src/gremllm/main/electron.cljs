(ns gremllm.main.electron
  "Guarded access to Electron main-process modules.
   Returns nil when running outside Electron (e.g. in tests).")

(defn- require-electron-main []
  (when (exists? js/require)
    (try
      (js/require "electron/main")
      (catch :default _ nil))))

(defn ^js get-dialog []
  (some-> (require-electron-main) .-dialog))

(defn ^js get-browser-window []
  (some-> (require-electron-main) .-BrowserWindow))

(defn ^js get-app []
  (some-> (require-electron-main) .-app))
