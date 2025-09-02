(ns gremllm.main.window
  (:require ["electron/main" :refer [screen]]))

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

