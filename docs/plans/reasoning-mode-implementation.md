# Reasoning Mode Implementation Plan

## Overview

Add extended thinking (reasoning mode) support for Claude models in Gremllm. This enables Claude to show its step-by-step reasoning process before providing answers, improving response quality for complex problems.

## API Reference

### Claude Extended Thinking API

**Request format:**
```json
{
  "model": "claude-sonnet-4-5-20250929",
  "max_tokens": 16000,
  "thinking": {
    "type": "enabled",
    "budget_tokens": 10000
  },
  "messages": [...]
}
```

**Response format:**
```json
{
  "content": [
    {
      "type": "thinking",
      "thinking": "Let me analyze this step by step...",
      "signature": "WaUjzkypQ2mUEVM36O2TxuC06KN8xyfbJwyem2dw3URve..."
    },
    {
      "type": "text",
      "text": "Based on my analysis..."
    }
  ],
  "usage": {
    "input_tokens": 100,
    "output_tokens": 500
  }
}
```

### Constraints

- **Minimum budget**: 1,024 tokens
- **budget_tokens** must be less than **max_tokens**
- **Incompatible** with `temperature` and `top_k` modifications
- Works best on Claude 4 Sonnet and Opus models
- Streaming required when `max_tokens` > 21,333

**Documentation:**
- https://platform.claude.com/docs/en/build-with-claude/extended-thinking
- https://docs.aws.amazon.com/bedrock/latest/userguide/claude-messages-extended-thinking.html

---

## Implementation Tasks (Main Process)

### Task 1: Extend Schema (`src/gremllm/schema.cljs`)

**Location:** After `LLMResponse` definition (line ~56)

**Add ReasoningConfig schema:**
```clojure
(def ReasoningConfig
  "Configuration for extended thinking mode. Nil means disabled."
  [:maybe
   [:map
    [:budget-tokens {:default 10000} [:int {:min 1024}]]]])
```

**Modify LLMResponse schema** (line 56-64) to include optional thinking:
```clojure
(def LLMResponse
  "Normalized LLM response shape, independent of provider.
   Main process transforms provider responses to this format before IPC."
  [:map
   [:text :string]
   [:thinking {:optional true} [:maybe :string]]
   [:usage [:map
            [:input-tokens :int]
            [:output-tokens :int]
            [:total-tokens :int]]]])
```

---

### Task 2: Add Request Builder (`src/gremllm/main/effects/llm.cljs`)

**Location:** Before `fetch-raw-provider-response` multimethod (around line 200)

**Add pure function for Anthropic request body construction:**
```clojure
(defn build-anthropic-request-body
  "Pure function: builds Anthropic request body from messages and options.
   When reasoning-config is provided, enables extended thinking mode."
  [messages model {:keys [reasoning-config]}]
  (let [base-max-tokens 8192
        ;; When reasoning enabled, max_tokens must exceed budget_tokens
        max-tokens (if reasoning-config
                     (max 16000 (* 2 (:budget-tokens reasoning-config)))
                     base-max-tokens)]
    (cond-> {:model model
             :max_tokens max-tokens
             :messages (messages->anthropic-format messages)}
      reasoning-config
      (assoc :thinking {:type "enabled"
                        :budget_tokens (:budget-tokens reasoning-config)}))))
```

---

### Task 3: Update Response Normalizer (`src/gremllm/main/effects/llm.cljs`)

**Location:** `normalize-anthropic-response` function (line ~161-170)

**Replace with:**
```clojure
(defn normalize-anthropic-response
  "Transforms Anthropic API response to LLMResponse schema.
   Extracts thinking block if present (extended thinking mode)."
  [response]
  (let [content-blocks (:content response)
        thinking-block (first (filter #(= (:type %) "thinking") content-blocks))
        text-block (first (filter #(= (:type %) "text") content-blocks))]
    (m/coerce schema/LLMResponse
              (cond-> {:text (or (:text text-block) "")
                       :usage {:input-tokens (get-in response [:usage :input_tokens])
                               :output-tokens (get-in response [:usage :output_tokens])
                               :total-tokens (+ (get-in response [:usage :input_tokens])
                                                (get-in response [:usage :output_tokens]))}}
                thinking-block
                (assoc :thinking (:thinking thinking-block))))))
```

---

### Task 4: Extend Multimethod Signature (`src/gremllm/main/effects/llm.cljs`)

**Location:** `fetch-raw-provider-response` multimethod (line ~195)

**Update dispatch function to accept options:**
```clojure
(defmulti fetch-raw-provider-response
  "Returns promise of unnormalized, provider-specific response.
   Options map supports :reasoning-config for extended thinking (Anthropic only)."
  (fn [_messages model _api-key _opts] (schema/model->provider model)))
```

**Update Anthropic implementation** (line ~205-218):
```clojure
(defmethod fetch-raw-provider-response :anthropic
  [messages model api-key opts]
  (let [request-body (build-anthropic-request-body messages model opts)
        headers {"x-api-key" api-key
                 "anthropic-version" "2023-06-01"
                 "content-type" "application/json"}]
    (-> (js/fetch "https://api.anthropic.com/v1/messages"
                  (clj->js {:method "POST"
                            :headers headers
                            :body (js/JSON.stringify (clj->js request-body))}))
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true)))))
```

**Update OpenAI and Gemini implementations** to accept and ignore opts:
```clojure
(defmethod fetch-raw-provider-response :openai
  [messages model api-key _opts]
  ;; ... existing implementation unchanged
  )

(defmethod fetch-raw-provider-response :google
  [messages model api-key _opts]
  ;; ... existing implementation unchanged
  )
```

---

### Task 5: Update Query Entry Point (`src/gremllm/main/effects/llm.cljs`)

**Location:** `query-llm-provider` function (find current definition)

**Update to support options with backward compatibility:**
```clojure
(defn query-llm-provider
  "Queries LLM provider and returns normalized LLMResponse promise.
   Options: {:reasoning-config {:budget-tokens 10000}}"
  ([messages model api-key]
   (query-llm-provider messages model api-key {}))
  ([messages model api-key opts]
   (let [provider (schema/model->provider model)]
     (.then (fetch-raw-provider-response messages model api-key opts)
            (response-normalizers provider)))))
```

---

### Task 6: Update Tests (`test/gremllm/main/effects/llm_test.cljs`)

**Add test fixtures for thinking responses:**
```clojure
(def mock-claude-thinking-response
  {:id "msg_01ABC123"
   :type "message"
   :role "assistant"
   :model "claude-sonnet-4-5-20250929"
   :content [{:type "thinking"
              :thinking "Let me work through this step by step..."}
             {:type "text"
              :text "The answer is 42."}]
   :stop_reason "end_turn"
   :usage {:input_tokens 50 :output_tokens 150}})
```

**Add tests:**
1. `build-anthropic-request-body` includes thinking config when provided
2. `build-anthropic-request-body` omits thinking config when nil
3. `normalize-anthropic-response` extracts thinking from response
4. `normalize-anthropic-response` works without thinking block (backward compat)

---

## Data Flow Diagram

```
Renderer                      IPC                       Main Process
─────────────────────────────────────────────────────────────────────
User toggles reasoning mode
        │
        ▼
[:form :reasoning-mode] true
        │
        ▼
chat/send-message ──────────▶  {messages, model,
                                reasoning-config}
                                        │
                                        ▼
                              build-anthropic-request-body (pure)
                                        │
                                        ▼
                              fetch-raw-provider-response (effect)
                                        │
                                        ▼
                              normalize-anthropic-response (pure)
                                        │
                                        ▼
chat/message-received  ◀──────  {:text "..."
                                 :thinking "..."
                                 :usage {...}}
```

---

## File References

| File | Line | Purpose |
|------|------|---------|
| `src/gremllm/schema.cljs` | 56-64 | LLMResponse schema to extend |
| `src/gremllm/main/effects/llm.cljs` | 161-170 | normalize-anthropic-response |
| `src/gremllm/main/effects/llm.cljs` | 195-218 | fetch-raw-provider-response |
| `src/gremllm/main/actions.cljs` | 102-105 | chat.effects/send-message |
| `test/gremllm/main/effects/llm_test.cljs` | - | Unit tests |
| `test/gremllm/main/effects/llm_integration_test.cljs` | - | Integration tests |

---

## Design Principles Applied

- **FCIS**: `build-anthropic-request-body` is pure; HTTP fetch is isolated effect
- **Modelarity**: Uses domain terms (`reasoning-config`, `budget-tokens`)
- **Backward Compatible**: Options map defaults to `{}`, existing calls unchanged
- **Schema Validation**: ReasoningConfig validates budget constraints via Malli

---

## Future Considerations (Not in Scope)

- IPC boundary changes for passing reasoning-config from renderer
- Renderer UI toggle for reasoning mode
- Displaying thinking content in chat UI
- Per-topic reasoning mode preference
- Streaming support for large thinking budgets (>21,333 max_tokens)
