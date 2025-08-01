(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.topic :as topic-state]))

(defn normalize-message [message]
  (update message :type keyword))

;; TODO: use Malli for coercion
(defn normalize-topic [topic]
  (update topic :messages #(mapv normalize-message %)))

;; TODO: create a Malli schema for Message
(defn create-topic []
  {:id "topic-1"
   :name "New Topic"
   :messages []})

(defn set-topic [_state topic-js]
  (when topic-js
    (let [clj-topic (js->clj topic-js :keywordize-keys true)
          normalized-topic (normalize-topic clj-topic)]
      [[:effects/save topic-state/path normalized-topic]])))

(defn restore-or-create-topic [_state loaded-topic]
  (if loaded-topic
    [[:topic.actions/set loaded-topic]]
    [[:topic.actions/start-new]]))

(defn bootstrap [_state]
  ;; WARN: requesting info on each re-render might be a bit costly. we're reading from disk each
  ;; time...
  [[:system.actions/request-info]
   [:topic.effects/load-topic {:on-success [:topic.actions/restore-or-create-topic]}]])

(defn start-new-topic [_state]
  [[:effects/save topic-state/path (create-topic)]])

(defn load-topic-error [_state error]
  (js/console.error "load-topic failed:" error)
  [])

(defn save-topic-success [_state topic-id filepath]
  (js/console.log "Topic" topic-id "saved to:" filepath)
  [])

(defn save-topic-error [_state topic-id error]
  (js/console.error "save-topic (topic-id: " topic-id ") failed:" error)
  [])

;; Effects for topic persistence
(nxr/register-effect! :topic.effects/load-topic
  (fn [{dispatch :dispatch} _store & [opts]]
    (let [on-success (or (:on-success opts) [:topic.actions/set])]
      (dispatch
        [[:effects/promise
          {:promise    (.loadTopic js/window.electronAPI)
           :on-success on-success
           :on-error   [:topic.actions/load-error]}]]))))

(nxr/register-effect! :topic.effects/save-topic
  (fn [{dispatch :dispatch} store topic-id]
    (dispatch
      [[:effects/promise
        {:promise    (.saveTopic js/window.electronAPI (clj->js (get-in @store topic-state/path)))
         :on-success [:topic.actions/save-success topic-id]
         :on-error   [:topic.actions/save-error topic-id]}]])))

