(ns gremllm.main.window
  (:require [gremllm.main.io :as io]
            ["electron/main" :refer [app BrowserWindow screen]]))

(def window-dimension-specs
  {:width-scale  0.60
   :max-width    1400
   :height-scale 0.80
   :max-height   1000})

(defn- calculate-dimension [value scale max-value]
  (-> value
      (* scale)
      (min max-value)
      long))

(defn calculate-window-dimensions [{:keys [width-scale max-width height-scale max-height]}]
  (let [work-area (-> screen .getPrimaryDisplay .-workAreaSize)]
    {:width (calculate-dimension (.-width work-area) width-scale max-width)
     :height (calculate-dimension (.-height work-area) height-scale max-height)}))

(defn setup-close-handlers
  "Handle window close and app quit with unsaved changes protection."
  [^js main-window]
  
  ;; When app tries to quit, close the window first (which may check for unsaved)
  (.on app "before-quit"
       (fn [event]
         (when-not (.isDestroyed main-window)
           (js/console.log "App quit intercepted - closing window first")
           (.preventDefault event)
           (set! (.-isQuitting main-window) true)  ; Mark that we're quitting
           (.close main-window))))

  ;; Window close is where we'd check for unsaved changes
  (.on main-window "close"
       (fn [_event]
         (let [quitting? (.-isQuitting main-window)]
           (js/console.log (str "Window closing" (when quitting? " (app quitting)")))
           ;; TODO: Check for unsaved changes here
           
           ;; If app is quitting, quit after window closes
           (when quitting?
             (.once main-window "closed"
                    (fn []
                      (js/console.log "Window closed - now quitting app")
                      (.quit app)))))))
  
  main-window)

(defn create-window []
  (let [dimensions (calculate-window-dimensions window-dimension-specs)
        preload-path (io/path-join js/__dirname "../resources/public/js/preload.js")
        window-config (merge dimensions {:webPreferences {:preload preload-path}})
        main-window (BrowserWindow. (clj->js window-config))
        html-path "resources/public/index.html"]
    (.loadFile main-window html-path)
    main-window))

