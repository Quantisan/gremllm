(ns gremllm.main.effects.llm-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.llm :as llm]
            [gremllm.test-utils :refer [with-console-silenced]]))

;; Example send-message response:
;;
;; cljs.user=> (-> (llm/send-message test-messages api-key)
;;     (.then (fn [response]
;;              (println "Response:" response)
;;              (println "Content:" (-> response :content first :text))))
;;     (.catch (fn [error]
;;               (println "Error:" error)))))
;; #object[Promise [object Promise]]
;; cljs.user=> Response: {:id msg_01LaTD7HNzYujxh6ASPpTQ6T, :type message, :role assistant, :model claude-3-5-haiku-20241022, :content [{:type text, :text 4}], :stop_reason end_turn, :stop_sequence nil, :usage {:input_tokens 16, :cache_creation_input_tokens 0, :cache_read_input_tokens 0, :output_tokens 5, :service_tier standard}}
;; Content: 4

(defn mock-successful-fetch [response-data]
  (fn [url opts]
    (js/Promise.resolve
     #js {:ok true
          :json (fn []
                  (js/Promise.resolve
                   (clj->js response-data)))})))

(deftest test-query-llm-provider
  (testing "successfully parses Claude API response"
    (let [original-fetch       js/fetch
          test-messages        [{:role "user" :content "2+2"}]
          test-model           "claude-3-5-haiku-latest"
          test-api-key         "test-key"
          mock-claude-response {:id "msg_01LaTD7HNzYujxh6ASPpTQ6T"
                                :type "message"
                                :role "assistant"
                                :model "claude-3-5-haiku-20241022"
                                :content [{:type "text" :text "4"}]
                                :stop_reason "end_turn"
                                :stop_sequence nil
                                :usage {:input_tokens 16
                                        :cache_creation_input_tokens 0
                                        :cache_read_input_tokens 0
                                        :output_tokens 5
                                        :service_tier "standard"}}]

      (set! js/fetch (mock-successful-fetch mock-claude-response))

      (-> (llm/query-llm-provider test-messages test-model test-api-key)
          (.then (fn [response]
                   (testing "response structure"
                     (is (= (:id response) "msg_01LaTD7HNzYujxh6ASPpTQ6T"))
                     (is (= (:role response) "assistant")))

                   (testing "content extraction"
                     (is (= (-> response :content first :text) "4")))))
          (.finally #(set! js/fetch original-fetch))))))

(defn mock-failed-fetch [error-message]
  (fn [url opts]
    (js/Promise.reject (js/Error. error-message))))

(deftest test-query-llm-provider-error
  (testing "API errors are propagated as rejected promises"
    (let [original-fetch js/fetch
          test-messages [{:role "user" :content "Hello"}]
          test-model "claude-3-5-haiku-latest"
          test-api-key "test-key"
          error-message "Network error"]

      (set! js/fetch (mock-failed-fetch error-message))

      (-> (llm/query-llm-provider test-messages test-model test-api-key)
          (.then (fn [_]
                   (is false "Should not succeed")))
          (.catch (fn [error]
                    (is (= (.-message error) error-message))))
          (.finally #(set! js/fetch original-fetch))))))

(defn mock-http-error-fetch [status status-text body]
  (fn [_url _opts]
    (js/Promise.resolve
     #js {:ok false
          :status status
          :statusText status-text
          :text #(js/Promise.resolve body)})))

(deftest test-query-llm-provider-http-error
  (testing "HTTP error responses include status and body"
    (let [original-fetch js/fetch
          restore-console (with-console-silenced)]
      (set! js/fetch (mock-http-error-fetch 401 "Unauthorized" "Invalid API key"))

      (-> (llm/query-llm-provider [{:role "user" :content "test"}] "claude-3-5-haiku-latest" "bad-key")
          (.then #(is false "Should not succeed"))
          (.catch (fn [error]
                    (is (re-find #"401.*Unauthorized.*Invalid API key" (.-message error)))))
          (.finally (fn []
                      (set! js/fetch original-fetch)
                      (restore-console)))))))

