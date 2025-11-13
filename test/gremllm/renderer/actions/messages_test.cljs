(ns gremllm.renderer.actions.messages-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.messages :as msg]))

;; TODO: Refactor test data to reduce DRY violations
;; 1. Repeated message structures - inline maps like {:type :user :text "..."} duplicated
;;    across test-messages->api-format, test-append-to-state, test-send-messages
;; 2. Magic topic IDs and repetitive state setup - "t1", "topic-1" scattered throughout,
;;    same :topics {...} structure rebuilt in every test. Consider schema/create-topic
;; 3. Hardcoded assistant IDs and model strings - random values (12345, 456, 789) and
;;    model strings should be named constants or schema defaults
;; 4. Inline attachment test data - "includes file-paths when attachments present" has hardcoded maps,
;;    could use schema/AttachmentRef or fixture builder
;; 5. Schema defaults unused - schema/create-topic and schema/PersistedTopic defaults
;;    exist but tests manually rebuild what schema already provides


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
  (testing "returns send-llm-message effect with correct structure"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Hello"}]}}
                 :active-topic-id "t1"
                 :form {:pending-attachments []}}
          assistant-id 12345
          model "claude-3-5-sonnet-20241022"
          effects (msg/send-messages state assistant-id model)]
      (is (= 1 (count effects))
          "Should return exactly one effect")
      (let [[effect-type effect-data] (first effects)]
        (is (= :effects/send-llm-messages effect-type)
            "Effect should be send-llm-message")
        (is (= [{:role "user" :content "Hello"}]
               (:messages effect-data))
            "Messages should be in API format")
        (is (= model (:model effect-data))
            "Model should be passed through")
        (is (nil? (:file-paths effect-data))
            "file-paths should be nil when no attachments")
        (is (= [[:llm.actions/response-received assistant-id]]
               (:on-success effect-data))
            "Success callback should receive assistant-id")
        (is (= [[:llm.actions/response-error assistant-id]]
               (:on-error effect-data))
            "Error callback should receive assistant-id"))))

  (testing "file-paths is nil when attachments empty"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Test"}]}}
                 :active-topic-id "t1"
                 :form {:pending-attachments []}}
          effects (msg/send-messages state 123 "model")
          [_ effect-data] (first effects)]
      (is (nil? (:file-paths effect-data))
          "Empty attachments should result in nil file-paths")))

  (testing "includes file-paths when attachments present"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Check this"}]}}
                 :active-topic-id "t1"
                 :form {:pending-attachments [{:name "file.txt"
                                               :size 1024
                                               :type "text/plain"
                                               :path "/tmp/file.txt"}
                                              {:name "image.png"
                                               :size 2048
                                               :type "image/png"
                                               :path "/tmp/image.png"}]}}
          effects (msg/send-messages state 456 "model")
          [_ effect-data] (first effects)]
      (is (= ["/tmp/file.txt" "/tmp/image.png"]
             (:file-paths effect-data))
          "Should extract paths from attachments")))

  (testing "file-paths is nil when pending-attachments missing"
    (let [state {:topics {"t1" {:messages [{:type :user :text "Test"}]}}
                 :active-topic-id "t1"
                 :form {}}
          effects (msg/send-messages state 789 "model")
          [_ effect-data] (first effects)]
      (is (nil? (:file-paths effect-data))
          "Missing pending-attachments should result in nil file-paths"))))
