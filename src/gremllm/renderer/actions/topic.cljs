(ns gremllm.renderer.actions.topic
  (:require [nexus.registry :as nxr]))

(defn normalize-message [message]
  (update message :type keyword))

;; TODO: use Malli for coercion
(defn normalize-topic [topic]
  (update topic :messages #(mapv normalize-message %)))

;; TODO: create a Malli schema for Message
(defn create-topic []
  {:id "topic-1"
   :name "New Topic"
   :messages [{:id 1
               :type :user
               :text "Hello, can you help me understand Replicant?"}
              {:id 2
               :type :assistant
               :text "Of course! Replicant is a ClojureScript library for building reactive user interfaces using a functional approach."}
              {:id 3
               :type :user
               :text "How does it differ from React?"}
              {:id 4
               :type :assistant
               :text "Replicant takes a simpler approach - it renders Hiccup data structures directly to the DOM and automatically tracks changes for efficient updates."}]})



(defn set-topic [_state topic-js]
  (when topic-js
    (let [clj-topic (js->clj topic-js :keywordize-keys true)
          normalized-topic (normalize-topic clj-topic)]
      [[:effects/save [] normalized-topic]])))

(defn restore-or-create-topic [_state loaded-topic]
  (if loaded-topic
    [[:topic.actions/set loaded-topic]]
    [[:topic.actions/start-new]]))

(defn bootstrap [_state]
  [[:topic.effects/load-topic {:on-success [:topic.actions/restore-or-create-topic]}]])

(defn start-new-topic [_state]
  [[:effects/save [] (create-topic)]])

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
        {:promise    (.saveTopic js/window.electronAPI (clj->js @store))
         :on-success [:topic.actions/save-success topic-id]
         :on-error   [:topic.actions/save-error topic-id]}]])))

