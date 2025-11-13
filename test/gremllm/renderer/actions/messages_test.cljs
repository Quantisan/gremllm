(ns gremllm.renderer.actions.messages-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.messages :as msg]))

;; Mock helper for window.electronAPI
(defn mock-electron-api []
  #js {:sendMessage (fn [_messages _model _file-paths]
                      (js/Promise.resolve #js {:text "mock response"}))})

;; Ensure window object exists in Node test environment
(when-not (exists? js/window)
  (set! js/window #js {}))

(deftest test-messages->api-format
  (testing "converts messages to API format"
    (is (= [{:role "user" :content "Hello"}
            {:role "assistant" :content "Hi there"}]
           (msg/messages->api-format
            [{:type :user :text "Hello"}
             {:type :assistant :text "Hi there"}])))))

(deftest test-append-to-state
  (testing "returns action to append message to the active topic's messages"
    (let [state {:topics {"topic-1" {:messages [{:id 1 :type :user :text "Hello"}]}}
                 :active-topic-id "topic-1"}
          new-message {:id 2 :type :assistant :text "Hi there"}]
      (is (= [[:effects/save [:topics "topic-1" :messages]
               [{:id 1 :type :user :text "Hello"}
                {:id 2 :type :assistant :text "Hi there"}]]]
             (msg/append-to-state state new-message)))))
  (testing "throws an error if no active topic is set"
    (let [state {:topics {"topic-1" {:messages []}}
                 :active-topic-id nil}
          new-message {:id 1, :text "test"}]
      (is (thrown-with-msg? js/Error #"Cannot append message: no active topic"
            (msg/append-to-state state new-message))))))

(deftest test-llm-response-received
  (testing "extracts message text from normalized LLMResponse"
    (let [assistant-id "asst-123"
          llm-response (clj->js {:text "Hello from AI"
                                 :usage {:input-tokens 10
                                         :output-tokens 5
                                         :total-tokens 15}})]
      (is (= [[:loading.actions/set-loading? assistant-id false]
              [:messages.actions/add-to-chat {:id "asst-123"
                                              :type :assistant
                                              :text "Hello from AI"}]]
             (msg/llm-response-received {} assistant-id llm-response))
          "Should extract text from :text field of normalized LLMResponse")))
  (testing "handles malformed response with missing text"
    (let [assistant-id "asst-123"
          api-response (clj->js {})]
      (is (= [[:loading.actions/set-loading? assistant-id false]
              [:messages.actions/add-to-chat {:id "asst-123"
                                              :type :assistant
                                              :text nil}]]
             (msg/llm-response-received {} assistant-id api-response))
          "Missing text should result in nil text, not throw"))))

(deftest test-send-messages
  (testing "returns promise effect with correct callback structure"
    (let [original-api js/window.electronAPI
          state {:topics {"t1" {:messages [{:type :user :text "Hello"}]}}
                 :active-topic-id "t1"
                 :form {:pending-attachments []}}
          assistant-id 12345
          model "claude-3-5-sonnet-20241022"]
      (try
        (set! js/window.electronAPI (mock-electron-api))
        (let [effects (msg/send-messages state assistant-id model)]
          (is (= 1 (count effects))
              "Should return exactly one effect")
          (let [[effect-type effect-data] (first effects)]
            (is (= :effects/promise effect-type)
                "Effect should be a promise effect")
            (is (= [[:llm.actions/response-received assistant-id]]
                   (:on-success effect-data))
                "Success callback should receive assistant-id")
            (is (= [[:llm.actions/response-error assistant-id]]
                   (:on-error effect-data))
                "Error callback should receive assistant-id")))
        (finally
          (set! js/window.electronAPI original-api)))))

  (testing "handles state with no attachments"
    (let [original-api js/window.electronAPI
          state {:topics {"t1" {:messages [{:type :user :text "Test"}]}}
                 :active-topic-id "t1"
                 :form {:pending-attachments []}}]
      (try
        (set! js/window.electronAPI (mock-electron-api))
        (let [effects (msg/send-messages state 123 "model")]
          (is (= 1 (count effects))
              "Should return one effect even with no attachments"))
        (finally
          (set! js/window.electronAPI original-api)))))

  (testing "handles state with attachments"
    (let [original-api js/window.electronAPI
          state {:topics {"t1" {:messages [{:type :user :text "Check this"}]}}
                 :active-topic-id "t1"
                 :form {:pending-attachments [{:name "file.txt"
                                               :size 1024
                                               :type "text/plain"
                                               :path "/tmp/file.txt"}
                                              {:name "image.png"
                                               :size 2048
                                               :type "image/png"
                                               :path "/tmp/image.png"}]}}]
      (try
        (set! js/window.electronAPI (mock-electron-api))
        (let [effects (msg/send-messages state 456 "model")]
          (is (= 1 (count effects))
              "Should return one effect with attachments"))
        (finally
          (set! js/window.electronAPI original-api)))))

  (testing "handles state with nil pending-attachments"
    (let [original-api js/window.electronAPI
          state {:topics {"t1" {:messages [{:type :user :text "Test"}]}}
                 :active-topic-id "t1"
                 :form {}}]
      (try
        (set! js/window.electronAPI (mock-electron-api))
        (let [effects (msg/send-messages state 789 "model")]
          (is (= 1 (count effects))
              "Should handle missing pending-attachments gracefully"))
        (finally
          (set! js/window.electronAPI original-api))))))
