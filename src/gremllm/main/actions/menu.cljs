(ns gremllm.main.actions.menu)

(defn save-topic [_state]
  ;; Menu wants to save topic. The topic state lives in renderer,
  ;; so we send the command there via IPC.
  [[:menu.effects/send-command :save-topic]])

(defn show-settings [_state]
  [[:menu.effects/send-command :show-settings]])

(defn open-folder [_state]
  [[:workspace.effects/pick-folder-dialog]])
