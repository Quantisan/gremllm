(ns gremllm.renderer.core
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [gremllm.renderer.ui :as ui]
            [gremllm.renderer.actions]
            [gremllm.schema :as schema]
            [gremllm.renderer.state.topic :as topic-state]))

;; State Shape Documentation
;; =========================
;; The state atom holds the following structure that flows to the UI:
;;
;; {:topics          {<topic-id> Topic}  ; Map of all topics by ID
;;  :active-topic-id string?              ; Currently selected topic ID
;;  ...}
;;
;; Topic Schema:
;; {:id       string?   ; "topic-<timestamp>-<random>"
;;  :name     string?   ; Display name in sidebar
;;  :messages [Message]} ; Conversation history
;;
;; Message Schema:
;; {:type    keyword?  ; :user or :assistant
;;  :role    string?   ; "user" or "assistant"
;;  :content string?}  ; The actual message text

(defn ^:export main []
  ;; Set up the atom
  (let [store (atom nil)
        el    (js/document.getElementById "app")]
    ;; Handle menu commands - these originate from main process menus
    (.onMenuCommand js/window.electronAPI "menu:command"
                    (fn [_ command-str]
                      (case (keyword command-str)
                        :save-topic    (nxr/dispatch store {} [[:topic.effects/save-active-topic]])
                        :show-settings (nxr/dispatch store {} [[:ui.actions/show-settings]])
                        nil)))

    ;; Handle workspace sync from main process
    (.onWorkspaceOpened js/window.electronAPI
                        (fn [_ topics-data]
                          (nxr/dispatch store {} [[:workspace.actions/opened topics-data]])))

    ;; Render on every change
    (add-watch store ::render-topic
               (fn [_ _ _ state]
                 (when-not (schema/valid-workspace-topics? (topic-state/get-topics-map state))
                   (js/console.error "Invalid topics in state!"))

                 (->> state
                      (ui/render-app)
                      (r/render el))))

    (r/set-dispatch!
      (fn [dispatch-data actions]
        (nxr/dispatch store dispatch-data actions)))

    ;; Trigger the first render
    (nxr/dispatch store {}
                  [[:system.actions/request-info]
                   [:workspace.actions/bootstrap]])))
