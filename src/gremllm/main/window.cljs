(ns gremllm.main.window
  (:require [gremllm.main.io :as io]
            ["electron/main" :refer [app BrowserWindow screen]]))

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
  (let [work-area (-> screen .getPrimaryDisplay .-workAreaSize)]
    {:width (calculate-dimension (.-width work-area) width-scale min-width max-width)
     :height (calculate-dimension (.-height work-area) height-scale 0 max-height)}))

(defn- handle-app-quit
  "Intercept app quit to close window first (for future unsaved changes check)."
  [^js main-window event]
  (when-not (.isDestroyed main-window)
    (js/console.log "App quit intercepted - closing window first")
    (.preventDefault event)
    (set! (.-isQuitting main-window) true)
    (.close main-window)))

(defn- handle-window-close
  "Handle window close, checking if app should quit after."
  [^js main-window _event]
  (let [quitting? (.-isQuitting main-window)]
    (js/console.log (str "Window closing" (when quitting? " (app quitting)")))
    ;; TODO: Check for unsaved changes here

    (when quitting?
      (.once main-window "closed"
             (fn []
               (js/console.log "Window closed - now quitting app")
               (.quit app))))))

(defn setup-close-handlers
  "Handle window close and app quit with unsaved changes protection."
  [^js main-window]
  (.on app "before-quit" (partial handle-app-quit main-window))
  (.on main-window "close" (partial handle-window-close main-window))
  main-window)

(defn create-window []
  (let [dimensions (calculate-window-dimensions window-dimension-specs)
        preload-path (io/path-join js/__dirname "../resources/public/js/preload.js")
        window-config (merge dimensions {:webPreferences {:preload preload-path}})
        main-window (BrowserWindow. (clj->js window-config))
        html-path "resources/public/index.html"]
    (.loadFile main-window html-path)
    main-window))

