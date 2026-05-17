(ns gremllm.main.window
  (:require [gremllm.main.io :as io]))

(defn- electron-main []
  (js/require "electron/main"))

(def window-dimension-specs
  {:width-scale  0.75
   :min-width    1200
   :max-width    1800
   :height-scale 0.80
   :max-height   1000})

(defn- calculate-dimension [value scale min-value max-value]
  (-> value
      (* scale)
      (max min-value)
      (min max-value)
      long))

(defn calculate-window-dimensions [{:keys [width-scale min-width max-width height-scale max-height]}]
  (let [work-area (-> (electron-main) .-screen .getPrimaryDisplay .-workAreaSize)]
    {:width (calculate-dimension (.-width work-area) width-scale min-width max-width)
     :height (calculate-dimension (.-height work-area) height-scale 0 max-height)}))

(def ^:private external-allowed-protocols #{"http:" "https:"})

(defn- external-allowed-url?
  "True when url parses to an http(s) protocol. Anything else (mailto, file, javascript, malformed) → false."
  [url]
  (try
    (contains? external-allowed-protocols (.-protocol (js/URL. url)))
    (catch :default _ false)))

(defn- open-externally! [url]
  (-> (electron-main) .-shell (.openExternal url)))

(defn- open-external-url!
  "If url is external (http/https), open it in the user's default browser.
   Anything else is silently dropped; in-window navigation is the caller's concern."
  [url]
  (when (external-allowed-url? url)
    (open-externally! url)))

(defn- handle-new-window [^js details]
  (open-external-url! (.-url details))
  #js {:action "deny"})

(defn- handle-will-navigate
  "Block all in-app navigation; if the target is external, hand it to the
   default browser. preventDefault is unconditional so unsupported URLs result
   in no navigation at all rather than an in-window load."
  [^js event url]
  (.preventDefault event)
  (open-external-url! url))

(defn- setup-navigation-guards
  "Prevent the BrowserWindow from becoming a web browser; route approved
   external links to the user's default browser instead."
  [^js main-window]
  (doto (.-webContents main-window)
    (.setWindowOpenHandler handle-new-window)
    (.on "will-navigate" handle-will-navigate))
  main-window)

(defn- handle-app-quit
  "Intercept app quit so the window closes first (future hook for unsaved-changes
   checks). The `isDestroyed` guard protects against re-entry after the window
   is already gone."
  [^js main-window quitting? event]
  (when-not (.isDestroyed main-window)
    (.preventDefault event)
    (reset! quitting? true)
    (.close main-window)))

(defn- handle-window-close
  "If the close was triggered by an app quit, follow up with .quit once the
   window is fully closed. A plain window-close leaves the app running."
  [^js main-window quitting? _event]
  (when @quitting?
    (.once main-window "closed"
           (fn [] (.quit (.-app (electron-main)))))))

(defn- setup-close-handlers
  "Bridge window-close and app-quit so a quit-initiated close also stops the
   app. `quitting?` is closed over by both handlers: the quit path flips it,
   and the close handler reads it to decide whether to follow up with .quit."
  [^js main-window]
  (let [quitting? (atom false)
        app       (.-app (electron-main))]
    (.on app         "before-quit" (partial handle-app-quit    main-window quitting?))
    (.on main-window "close"       (partial handle-window-close main-window quitting?))
    main-window))

(defn create-window []
  (let [BrowserWindow (.-BrowserWindow (electron-main))
        dimensions    (calculate-window-dimensions window-dimension-specs)
        preload-path  (io/path-join js/__dirname "../resources/public/js/preload.js")
        window-config (merge dimensions {:webPreferences {:preload preload-path}})
        main-window   (BrowserWindow. (clj->js window-config))]
    (.loadFile main-window "resources/public/index.html")
    (setup-navigation-guards main-window)
    (setup-close-handlers    main-window)
    main-window))

