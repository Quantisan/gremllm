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
  "Setup coordinated quit/close handlers for unsaved changes protection (stub)."
  [^js main-window]
  (let [closing? (atom false)
        quitting? (atom false)]

    ;; Intercept app quit
    (.on app "before-quit"
         (fn [event]
           (when-not (or @closing? (.isDestroyed main-window))
             (.preventDefault event)
             (reset! quitting? true)
             (js/console.log "Quit intercepted!")
             (.close main-window))))

    ;; Intercept window close
    (.on main-window "close"
         (fn [event]
           (when-not @closing?
             (.preventDefault event)
             (reset! closing? true)
             (js/console.log "Close intercepted - closing now...")
             ;; TODO: Send notification to renderer for unsaved check
             (.destroy main-window)
             (when @quitting?
               (js/console.log "Now quitting app...")
               (.quit app)))))))

(defn create-window []
  (let [dimensions (calculate-window-dimensions window-dimension-specs)
        preload-path (io/path-join js/__dirname "../resources/public/js/preload.js")
        window-config (merge dimensions {:webPreferences {:preload preload-path}})
        main-window (BrowserWindow. (clj->js window-config))
        html-path "resources/public/index.html"]
    (.loadFile main-window html-path)
    main-window))

