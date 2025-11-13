(ns gremllm.renderer.state.form)

;; Path constants
(def user-input-path [:form :user-input])
(def pending-attachments-path [:form :pending-attachments])

;; State accessor functions
(defn get-user-input [state]
  (get-in state user-input-path ""))

;; TODO: Shape opacity issue - this returns DOM File metadata {:name :size :type :path}
;; (created by :event/dropped-files placeholder in renderer/actions.cljs), NOT the
;; persisted AttachmentRef schema {:ref :name :mime-type :size} from schema.cljs.
;; Understanding what this returns requires tracing :form.actions/handle-file-drop →
;; :event/dropped-files → DOM extraction logic. Consider:
;; - Explicit PendingAttachment schema for documentation
;; - Namespace colocation (move placeholder near accessor)
;; - Inline docstring describing exact shape
;; Goal: make data shape obvious without requiring multi-file archaeology.
(defn get-pending-attachments [state]
  (get-in state pending-attachments-path []))
