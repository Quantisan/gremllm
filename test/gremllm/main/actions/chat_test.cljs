(ns gremllm.main.actions.chat-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.chat :as chat]
            [gremllm.schema :as schema]
            [malli.core :as m]))

(def ^:private test-model "claude-haiku-4-5-20251001")
(def ^:private test-api-key "sk-test-key")

(defn- make-attachment-ref
  "Create valid AttachmentRef with test defaults. Validates against production schema."
  ([] (make-attachment-ref {}))
  ([overrides]
   (m/coerce schema/AttachmentRef
             (merge {:ref "abc12345"
                     :name "test.png"
                     :mime-type "image/png"
                     :size 1024}
                    overrides))))

(defn- make-message
  "Create valid Message with test defaults. Validates against production schema."
  ([] (make-message {}))
  ([overrides]
   (m/coerce schema/Message
             (merge {:id 1 :type :user :text "Hello"}
                    overrides))))

(defn- effect-with-messages
  "Destructure effect result and return [effect-name messages model api-key]."
  [result]
  (let [[[effect-name messages model api-key]] result]
    [effect-name messages model api-key]))

(deftest test-attach-and-send
  (testing "enriches first message with valid attachments and returns send effect"
    (let [attachment-ref (make-attachment-ref)
          buffer (js/Buffer.from "test-content" "utf-8")
          loaded-pairs [[attachment-ref buffer]]
          messages [(make-message {:text "Check this image"})
                    (make-message {:id 2 :type :assistant :text "OK"})]
          result (chat/attach-and-send {} loaded-pairs messages test-model test-api-key)
          [effect-name api-messages model api-key] (effect-with-messages result)
          [first-msg second-msg] api-messages
          attachment (first (:attachments first-msg))]

      (is (= :chat.effects/send-message effect-name))
      (is (= test-model model))
      (is (= test-api-key api-key))

      (testing "first message enriched with attachment and transformed to API format"
        (is (= "user" (:role first-msg)))
        (is (= "Check this image" (:content first-msg)))
        (is (= 1 (count (:attachments first-msg))))
        (is (= "image/png" (:mime-type attachment)))
        (is (= (.toString buffer "base64") (:data attachment))))

      (testing "second message transformed to API format"
        (is (= {:role "assistant" :content "OK"} second-msg)))))

  (testing "handles empty attachments collection"
    (let [messages [(make-message)]
          result (chat/attach-and-send {} [] messages test-model test-api-key)
          [effect-name api-messages] (effect-with-messages result)]

      (is (= :chat.effects/send-message effect-name))
      (is (= [{:role "user" :content "Hello" :attachments []}]
             api-messages))))

  (testing "propagates validation error for invalid attachment data"
    (let [invalid-ref {:ref "abc12345"
                       :name "test.png"
                       ;; Missing :mime-type - intentionally invalid
                       :size 1024}
          buffer (js/Buffer.from "test-content" "utf-8")
          loaded-pairs [[invalid-ref buffer]]
          messages [(make-message {:text "Check this"})]]

      (is (thrown? js/Error
                   (chat/attach-and-send {} loaded-pairs messages test-model test-api-key))))))
