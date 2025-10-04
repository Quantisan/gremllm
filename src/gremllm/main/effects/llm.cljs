(ns gremllm.main.effects.llm
  "LLM provider side effects and HTTP operations")

(defn query-llm-provider
  "Performs HTTP request to Anthropic API"
  [messages model api-key]
  (let [request-body {:model model
                      :max_tokens 8192
                      :messages messages}
        headers {"x-api-key" api-key
                 "anthropic-version" "2023-06-01"
                 "content-type" "application/json"}]
    (-> (js/fetch "https://api.anthropic.com/v1/messages"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then (fn [response]
                 (if (.-ok response)
                   (.json response)
                   ;; Capture detailed error info for diagnostics
                   (-> (.text response)
                       (.then (fn [body]
                                (let [error-msg (str "API request failed (" (.-status response) " " (.-statusText response) ")")]
                                  (js/console.error "LLM API Error Details:"
                                                    (clj->js {:status (.-status response)
                                                              :statusText (.-statusText response)
                                                              :model model
                                                              :messageCount (count messages)
                                                              :responseBody body}))
                                  (throw (js/Error. (str error-msg ": " body))))))))))
        (.then #(js->clj % :keywordize-keys true)))))