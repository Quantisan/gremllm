(ns gremllm.renderer.state.form)

;; Path constants
(def user-input-path [:form :user-input])
(def pending-attachments-path
  "Path to pending attachments in state.
  Stores DOM File metadata (not AttachmentRef which is for persisted attachments).
  Shape: vector of {:name :size :type :path} from drag-drop events."
  [:form :pending-attachments])

;; State accessor functions
(defn get-user-input [state]
  (get-in state user-input-path ""))

(defn get-pending-attachments
  "Returns pending attachments from form state.
  Shape: vector of {:name :size :type :path} from DOM File objects.
  Saved by [:form.actions/handle-file-drop [:event/dropped-files]]."
  [state]
  (get-in state pending-attachments-path []))
