# Multi-Provider LLM Support Implementation Plan

## Context & Key Decisions

**Problem:** The current `query-llm-provider` function in `main/effects/llm.cljs` is hardcoded to Anthropic's API. We need to support OpenAI (GPT models) and Google Gemini while maintaining strict FCIS separation and keeping the codebase decomplected.

**Chosen Approach:** Clojure multimethods dispatching on provider keyword (`:anthropic`, `:openai`, `:google`).

**Key Decisions:**
- **Provider selection:** Infer from model string (e.g., "gpt-4" → `:openai`, "claude-3" → `:anthropic`)
- **Transport:** Continue using `fetch` directly—no SDK dependencies yet. We'll migrate to official SDKs when we outgrow raw HTTP.
- **Error handling:** Keep current minimal pattern (catch and display). No retry logic or sophisticated error handling yet.
- **API keys:** Separate environment variables (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`) with individual Nexus placeholders.

**Tradeoffs:**
- ✅ Zero new dependencies, minimal bundle size
- ✅ Easy to add providers without touching existing code
- ⚠️ We manually construct HTTP requests for each provider (acceptable for skateboard→scooter phase)
- ⚠️ Provider inference couples us to model naming conventions (mitigated by centralized `model->provider` function)

## System Design Overview

The transformation refactors `query-llm-provider` from a single function into a multimethod with provider-specific implementations. A pure `model->provider` helper extracts the provider keyword from model strings using pattern matching. The IPC flow remains unchanged: `chat/send-message` → `:chat.effects/send-message` effect → `query-llm-provider` multimethod → provider-specific method (`:anthropic`, `:openai`, or `:google`). Each provider method encapsulates its API-specific details (endpoint URL, headers, request body shape, response parsing) while maintaining the same signature: `[messages model api-key] → Promise<response>`. The multimethod boundary cleanly decomplects provider selection from request execution. All code stays in `main/effects/llm.cljs` to maintain the single LLM effects boundary, with new placeholders registered in `main/actions.cljs` for additional API keys.

## Critical Patterns

### 1. Multimethod Definition & Dispatch Function

```clojure
(defn model->provider
  "Infers provider from model string. Pure function for easy testing."
  [model]
  (cond
    (str/starts-with? model "gpt-")    :openai
    (str/starts-with? model "claude-") :anthropic
    (str/starts-with? model "gemini-") :google
    :else (throw (js/Error. (str "Unknown provider for model: " model)))))

(defmulti query-llm-provider
  "Dispatches to provider-specific implementation based on model string."
  (fn [_messages model _api-key] (model->provider model)))
```

**Why this matters:** The dispatch function is pure and testable. It's the single point of coupling between model names and providers—when naming conventions change, we update one function. The multimethod signature remains unchanged from the current function, so no call sites need modification.

### 2. Provider-Specific Implementation (Anthropic Example)

```clojure
(defmethod query-llm-provider :anthropic
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
        (.then #(handle-response % model (count messages)))
        (.then #(js->clj % :keywordize-keys true)))))
```

**Why this matters:** This is the existing `query-llm-provider` logic moved into a method implementation. It shows that refactoring to multimethods requires minimal code changes—we're extracting, not rewriting. OpenAI and Gemini implementations will follow the same structure with different endpoints/headers.

### 3. API Key Placeholder Registration

```clojure
;; In main/actions.cljs
(nxr/register-placeholder! :env/openai-api-key
  (fn [_]
    (or (.-OPENAI_API_KEY (.-env js/process))
        (some-> (.getPath app "userData")
                (io/secrets-file-path)
                (secrets-actions/load :openai-api-key)
                :ok))))
```

**Why this matters:** Each provider gets its own placeholder following the existing pattern. This maintains consistency with the current `:env/anthropic-api-key` implementation and integrates with the safeStorage fallback mechanism.

## Implementation Path

### Phase 1: Extract Multimethod Infrastructure
**Goal:** Refactor existing code to use multimethod pattern without changing behavior.

**Files to modify:**
- `src/gremllm/main/effects/llm.cljs` - Define multimethod and `:anthropic` implementation
- `test/gremllm/main/effects/llm_test.cljs` - Add tests for `model->provider`

**Actions:**
1. Add `model->provider` pure function with tests covering known model prefixes
2. Define `query-llm-provider` multimethod with dispatch function
3. Move existing fetch logic into `(defmethod query-llm-provider :anthropic ...)`
4. Run existing tests—they should pass unchanged since call signature is identical
5. Commit: "refactor: convert query-llm-provider to multimethod"

**Validation:** `npm run test` passes, manual smoke test with Claude model still works.

### Phase 2: Generalize API Key Resolution ✅ COMPLETED
**Goal:** Make API key selection dynamic based on model using parameterized placeholders.

**Files modified:**
- `src/gremllm/main/effects/llm.cljs` - Added provider mapping helpers
- `src/gremllm/main/actions.cljs` - Registered parameterized and provider-specific placeholders
- `src/gremllm/main/core.cljs` - Updated effect to use `:env/api-key-for-model`
- `test/gremllm/main/effects/llm_test.cljs` - Added tests for helper functions
- `.env.example` - Documented `OPENAI_API_KEY` and `GEMINI_API_KEY`

**Completed actions:**
1. Added `provider->api-key-keyword` helper mapping provider keywords to safeStorage keys
2. Added `provider->env-var-name` helper mapping provider keywords to environment variable names
3. Registered `:env/openai-api-key` and `:env/google-api-key` placeholders (mirror Anthropic pattern)
4. Registered `:env/api-key-for-model` parameterized placeholder using `model->provider` + helpers
5. Updated `main/core.cljs:42` to use `[:env/api-key-for-model model]` instead of hardcoded Anthropic key
6. Added tests validating provider->api-key-keyword and provider->env-var-name for all three providers
7. Committed: "feat: add parameterized API key resolution"

**Result:** Effect signature stays clean with explicit api-key argument. Placeholder system handles dynamic resolution. Infrastructure ready for multiple providers.

### Phase 3a: Implement OpenAI Provider
**Goal:** Add OpenAI GPT model support with actual API integration.

**Files to modify:**
- `src/gremllm/main/effects/llm.cljs` - Add `:openai` method implementation
- `test/gremllm/main/effects/llm_test.cljs` - Add OpenAI request/response tests

**Actions:**
1. Implement `(defmethod query-llm-provider :openai ...)` using OpenAI Chat Completions API:
   - Endpoint: `https://api.openai.com/v1/chat/completions`
   - Headers: `Authorization: Bearer {api-key}`, `Content-Type: application/json`
   - Request body: `{model, messages, max_tokens: 8192}`
   - Response parsing: Extract from `.choices[0].message`
2. Add test with mock fetch validating OpenAI-specific request structure and response parsing
3. Manual test: Create topic with `gpt-5-mini`, verify end-to-end flow
4. Commit: "feat: add OpenAI provider support"

**Validation:** Tests pass, can successfully chat with GPT models when `OPENAI_API_KEY` is set.

**Key decision:** OpenAI's response format differs from Anthropic's. We'll need to normalize the response structure or handle differences at the boundary. Start by just returning the raw response and see if downstream code needs changes.

### Phase 3b: Implement Google Gemini Provider
**Goal:** Add Gemini model support.

**Files to modify:**
- `src/gremllm/main/effects/llm.cljs` - Add `:google` method
- `src/gremllm/main/actions.cljs` - Register `:env/gemini-api-key` placeholder
- `test/gremllm/main/effects/llm_test.cljs` - Add Gemini-specific tests
- `.env.example` - Document `GEMINI_API_KEY`

**Actions:**
1. Register `:env/gemini-api-key` placeholder
2. Implement `(defmethod query-llm-provider :google ...)` using Gemini API:
   - Endpoint: `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api-key}`
   - Headers: `Content-Type: application/json`
   - Request body: Gemini's content format (parts/role structure)
   - Response parsing: Extract from `.candidates[0].content`
3. Add tests for request/response handling
4. Manual test with `gemini-2.5-flash-exp`
5. Commit: "feat: add Google Gemini provider support"

**Validation:** Can successfully chat with Gemini models when `GEMINI_API_KEY` is set.

**Note:** Gemini's message format is most different from Anthropic/OpenAI. May need a message transformation helper.

### Phase 4: Response Normalization (If Needed)
**Goal:** Handle provider-specific response format differences.

**Trigger:** If downstream code (message rendering, usage tracking) breaks with different providers.

**Files to modify:**
- `src/gremllm/main/effects/llm.cljs` - Add response normalization per provider
- `src/gremllm/schema.cljs` - Potentially add `LLMResponse` schema if normalization is needed

**Actions:**
1. Define what fields downstream code actually needs (text content, role, usage tokens, etc.)
2. Add provider-specific response transformations within each method
3. Add tests validating normalized output shape
4. Commit: "refactor: normalize LLM responses across providers"

**Defer if:** Current renderer code already handles different response shapes gracefully.

## Open Questions (Deferred)

1. **Response format normalization:** Should we normalize all provider responses to a common shape, or let the renderer handle different formats? *Recommendation: Wait to see if it's actually a problem first.*

2. **Model validation:** Should we validate that requested models actually exist for each provider (e.g., error early if someone types "gpt-5")? *Recommendation: Let the API return the error for now.*

3. **Settings UI changes:** Should users see a dropdown of available models grouped by provider, or just type model names? *Recommendation: Current free-text input works fine for now; enhance UI in a later iteration.*

## First Files to Read

Before starting implementation:
1. `src/gremllm/main/effects/llm.cljs` - Current implementation to refactor
2. `src/gremllm/main/actions.cljs:67-69` - Effect registration pattern
3. `test/gremllm/main/effects/llm_test.cljs` - Testing patterns to follow
4. OpenAI Chat Completions API docs: https://platform.openai.com/docs/api-reference/chat
5. Gemini API docs: https://ai.google.dev/api/generate-content

## Acceptance Criteria

- [ ] Can send messages using Claude models (existing functionality preserved)
- [ ] Can send messages using GPT models when `OPENAI_API_KEY` is set
- [ ] Can send messages using Gemini models when `GEMINI_API_KEY` is set
- [ ] All existing tests pass
- [ ] New tests cover `model->provider` function and provider-specific request/response handling
- [ ] Unknown model prefixes throw clear error messages
- [ ] API key resolution follows existing pattern (env var → safeStorage fallback)
