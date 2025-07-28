(ns gremllm.main.window
  (:require ["electron/main" :refer [screen]]))

(defn- calculate-dimension [value scale max-value]
  (-> value
      (* scale)
      (min max-value)
      long))

(defn calculate-window-dimensions [{:keys [width-scale max-width height-scale max-height]}]
  (let [work-area (-> screen .getPrimaryDisplay .-workAreaSize)]
    {:width (calculate-dimension (.-width work-area) width-scale max-width)
     :height (calculate-dimension (.-height work-area) height-scale max-height)}))

