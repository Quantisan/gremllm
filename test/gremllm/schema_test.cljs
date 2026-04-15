(ns gremllm.schema-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.schema :as schema]
            [malli.core :as m]
            [malli.transform :as mt]))

(defn create-message
  "Build a schema/Message fixture from Malli defaults and explicit overrides."
  [overrides]
  (merge (m/decode schema/Message {} mt/default-value-transformer)
         overrides))

(deftest test-provider->api-key-keyword
  (testing "maps Anthropic to anthropic-api-key"
    (is (= :anthropic-api-key (schema/provider->api-key-keyword :anthropic))))

  (testing "maps OpenAI to openai-api-key"
    (is (= :openai-api-key (schema/provider->api-key-keyword :openai))))

  (testing "maps Google to gemini-api-key"
    (is (= :gemini-api-key (schema/provider->api-key-keyword :google)))))

(deftest test-keyword-to-provider
  (testing "maps anthropic-api-key to :anthropic"
    (is (= :anthropic (schema/keyword-to-provider :anthropic-api-key))))

  (testing "maps openai-api-key to :openai"
    (is (= :openai (schema/keyword-to-provider :openai-api-key))))

  (testing "maps gemini-api-key to :google"
    (is (= :google (schema/keyword-to-provider :gemini-api-key))))

  (testing "throws on unknown storage keyword"
    (is (thrown? js/Error (schema/keyword-to-provider :unknown-api-key)))
    (is (thrown? js/Error (schema/keyword-to-provider :mistral-api-key)))))

(deftest test-model->provider
  (testing "identifies Anthropic models"
    (is (= :anthropic (schema/model->provider "claude-3-5-haiku-latest")))
    (is (= :anthropic (schema/model->provider "claude-3-opus-20240229"))))

  (testing "identifies OpenAI models"
    (is (= :openai (schema/model->provider "gpt-4o")))
    (is (= :openai (schema/model->provider "gpt-4o-mini")))
    (is (= :openai (schema/model->provider "gpt-3.5-turbo"))))

  (testing "identifies Google models"
    (is (= :google (schema/model->provider "gemini-2.0-flash-exp")))
    (is (= :google (schema/model->provider "gemini-pro"))))

  (testing "throws on unknown model prefix"
    (is (thrown? js/Error (schema/model->provider "unknown-model")))
    (is (thrown? js/Error (schema/model->provider "mistral-large")))))

(deftest test-provider-display-name
  (testing "returns display name for Anthropic"
    (is (= "Anthropic" (schema/provider-display-name :anthropic))))

  (testing "returns display name for OpenAI"
    (is (= "OpenAI" (schema/provider-display-name :openai))))

  (testing "returns display name for Google"
    (is (= "Google" (schema/provider-display-name :google)))))

;; ========================================
;; Excerpt (Selection Capture)
;; ========================================

;; Fixtures captured from selections on resources/gremllm-launch-log.md

(def single-word-selection
  {:text "Dispatch"
   :range-count 1
   :anchor-node "#text"
   :anchor-offset 19
   :focus-node "#text"
   :focus-offset 27
   :range {:bounding-rect {:height 33 :left 350.96875 :top 27 :width 117.140625}
           :client-rects  [{:height 33 :left 350.96875 :top 27 :width 117.140625}]
           :common-ancestor "#text"
           :start-container "#text"
           :start-text "Pangalactic Wombat Dispatch"
           :start-offset 19
           :end-container "#text"
           :end-text "Pangalactic Wombat Dispatch"
           :end-offset 27}})

(def mixed-format-selection
  {:text "Our Gremllm crew"
   :range-count 1
   :anchor-node "#text"
   :anchor-offset 0
   :focus-node "#text"
   :focus-offset 5
   :range {:bounding-rect {:height 17 :left 76 :top 75.5 :width 120.9375}
           :client-rects  [{:height 17 :left 76 :top 75.5 :width 27.640625}
                           {:height 17 :left 103.640625 :top 75.5 :width 58.296875}
                           {:height 17 :left 103.640625 :top 75.5 :width 58.296875}
                           {:height 17 :left 161.9375 :top 75.5 :width 35}]
           :common-ancestor "P"
           :start-container "#text"
           :start-text "Our "
           :start-offset 0
           :end-container "#text"
           :end-text " crew tuned the "
           :end-offset 5}})

(def multi-node-selection
  {:text "Tonight's Wins\nFixed council chat jitter.\nRun npm run test:ci before demos."
   :range-count 1
   :anchor-node "#text"
   :anchor-offset 0
   :focus-node "#text"
   :focus-offset 14
   :range {:bounding-rect {:height 92.171875 :left 76 :top 130.25 :width 789.796875}
           :client-rects  [{:height 29 :left 76 :top 130.25 :width 168.296875}
                           {:height 21 :left 116 :top 173.421875 :width 749.796875}
                           {:height 17 :left 116 :top 175.421875 :width 152.71875}
                           {:height 17 :left 116 :top 200.171875 :width 28.828125}
                           {:height 24.5 :left 144.828125 :top 197.921875 :width 121.140625}
                           {:height 14 :left 150.078125 :top 203.171875 :width 110.640625}
                           {:height 17 :left 265.96875 :top 200.171875 :width 97.0625}]
           :common-ancestor "DIV"
           :start-container "#text"
           :start-text "Tonight's Wins"
           :start-offset 0
           :end-container "#text"
           :end-text " before demos."
           :end-offset 14}})

(deftest captured-selection-schema-test
  (testing "single word selection validates against CapturedSelection"
    (is (m/validate schema/CapturedSelection single-word-selection)))

  (testing "mixed format selection validates against CapturedSelection"
    (is (m/validate schema/CapturedSelection mixed-format-selection)))

  (testing "multi-node selection validates against CapturedSelection"
    (is (m/validate schema/CapturedSelection multi-node-selection))))

(deftest anchor-context-schema-test
  (testing "valid AnchorContext validates"
    (is (m/validate schema/AnchorContext
                    {:panel-rect {:top 100 :left 50 :width 800 :height 600}
                     :panel-scroll-top 20}))))

(deftest block-ref-test
  (testing "valid BlockRef"
    (is (m/validate schema/BlockRef
                    {:kind :paragraph
                     :index 2
                     :start-line 3
                     :end-line 3
                     :block-text-snippet "Our Gremllm launched on a Tuesday."})))
  (testing "missing required field fails"
    (is (not (m/validate schema/BlockRef
                         {:kind :paragraph :index 2 :start-line 3 :end-line 3})))))

(deftest document-excerpt-test
  (let [block {:kind :paragraph
               :index 2
               :start-line 3
               :end-line 3
               :block-text-snippet "Our Gremllm launched on a Tuesday."}]
    (testing "same-block excerpt with offsets"
      (is (m/validate schema/DocumentExcerpt
                      {:id "excerpt-abc"
                       :text "launched on a Tuesday"
                       :locator {:document-relative-path "document.md"
                                 :start-block block
                                 :end-block block
                                 :start-offset 4
                                 :end-offset 25}})))
    (testing "cross-block excerpt without offsets"
      (is (m/validate schema/DocumentExcerpt
                      {:id "excerpt-xyz"
                       :text "Gremllm Launch Log\nOur Gremllm"
                       :locator {:document-relative-path "document.md"
                                 :start-block (assoc block
                                                     :kind :heading
                                                     :index 1
                                                     :start-line 1
                                                     :end-line 1
                                                     :block-text-snippet "Gremllm Launch Log")
                                 :end-block block}})))
    (testing "missing :locator fails"
      (is (not (m/validate schema/DocumentExcerpt
                           {:id "e" :text "t"}))))))

(deftest message-with-context-test
  (let [excerpt {:id "e1"
                 :text "snippet"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :paragraph
                                         :index 2
                                         :start-line 3
                                         :end-line 3
                                         :block-text-snippet "Our Gremllm..."}
                           :end-block {:kind :paragraph
                                       :index 2
                                       :start-line 3
                                       :end-line 3
                                       :block-text-snippet "Our Gremllm..."}
                           :start-offset 4
                           :end-offset 11}}]
    (testing "message with excerpt context"
      (is (m/validate schema/Message
                      (create-message {:id 1
                                       :type :user
                                       :text "reword these"
                                       :context {:excerpts [excerpt]}}))))
    (testing "message without context still valid"
      (is (m/validate schema/Message
                      (create-message {:id 1 :type :user :text "hello"}))))))

(deftest persisted-topic-excerpts-are-document-excerpts-test
  (let [excerpt {:id "e1"
                 :text "snippet"
                 :locator {:document-relative-path "document.md"
                           :start-block {:kind :paragraph
                                         :index 2
                                         :start-line 3
                                         :end-line 3
                                         :block-text-snippet "Our..."}
                           :end-block {:kind :paragraph
                                       :index 2
                                       :start-line 3
                                       :end-line 3
                                       :block-text-snippet "Our..."}}}]
    (is (m/validate schema/PersistedTopic
                    {:id "t1"
                     :name "T"
                     :session {:pending-diffs []}
                     :messages []
                     :excerpts [excerpt]}))))
