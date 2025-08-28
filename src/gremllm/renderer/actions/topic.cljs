(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]
            [gremllm.renderer.state.topic :as topic-state]))

(defn normalize-message [message]
  (update message :type keyword))

;; TODO: use Malli for coercion
(defn normalize-topic [topic]
  (update topic :messages #(mapv normalize-message %)))

(defn generate-topic-id []
;; NOTE: We call `js/Date.now` and js/Math.random directly for pragmatic FCIS. Passing these values
;; as argument would complicate the call stack for a benign, testable effect.
  (let [timestamp (js/Date.now)
        random-suffix (-> (js/Math.random) (.toString 36) (.substring 2))]
    (str "topic-" timestamp "-" random-suffix)))

;; TODO: create a Malli schema for Message
(defn create-topic []
  {:id       (generate-topic-id)
   :name     "New Topic"
   :messages []})

(defn set-topic [_state topic-js]
  (when topic-js
    (let [clj-topic (js->clj topic-js :keywordize-keys true)
          normalized-topic (normalize-topic clj-topic)
          topic-id (:id normalized-topic)]
      [[:effects/save (conj topic-state/topics-path topic-id) normalized-topic]
       [:effects/save topic-state/active-topic-id-path topic-id]])))

(defn restore-or-create-topic [_state loaded-topic]
  (if loaded-topic
    [[:topic.actions/set loaded-topic]]
    [[:topic.actions/start-new]]))

(defn determine-initial-topic [_state topics-js]
  (let [entries (js->clj topics-js :keywordize-keys true)]
    (if (seq entries)
      []
      [[:topic.actions/start-new]])))

(defn list-topics-error [_state error]
  (js/console.error "list-topics failed:" error)
  [[:topic.actions/start-new]])

(defn bootstrap [_state]
  [[:topic.effects/list {:on-success [[:topic.actions/determine-initial-topic]]
                         :on-error   [[:topic.actions/list-topics-error]]}]])

(defn start-new-topic [_state]
  (let [new-topic (create-topic)]
    [[:effects/save (conj topic-state/topics-path (:id new-topic)) new-topic]
     [:effects/save topic-state/active-topic-id-path (:id new-topic)]]))

(defn save-topic-success [_state topic-id filepath]
  (js/console.log "Topic" topic-id "saved to:" filepath)
  [])

(defn save-topic-error [_state topic-id error]
  (js/console.error "save-topic (topic-id: " topic-id ") failed:" error)
  [])

(defn switch-topic [_state topic-id]
  [[:effects/save topic-state/active-topic-id-path (keyword topic-id)]])

(nxr/register-effect! :topic.effects/list
  (fn [{dispatch :dispatch} _store & [opts]]
   (dispatch
     [[:effects/promise
       {:promise    (.listTopics js/window.electronAPI)
        :on-success (:on-success opts)
        :on-error   (:on-error opts)}]])))

(nxr/register-effect! :topic.effects/save-active-topic
  (fn [{dispatch :dispatch} store]
    (let [active-topic (topic-state/get-active-topic @store)]
      (if-let [topic-id (:id active-topic)]
        (dispatch
         [[:effects/promise
           {:promise    (.saveTopic js/window.electronAPI (clj->js active-topic))
            :on-success [[:topic.actions/save-success topic-id]]
            :on-error   [[:topic.actions/save-error topic-id]]}]])
        (dispatch [[:topic.actions/save-error nil (js/Error. "No active topic to save")]])))))

