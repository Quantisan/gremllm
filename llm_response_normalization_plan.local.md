# LLM Response Normalization Implementation Plan

## Context & Key Decisions

**Problem:** The renderer's `llm-response-received` function (renderer/actions/messages.cljs:35-42) hardcodes Anthropic's response structure: `(get-in clj-response [:content 0 :text])`. OpenAI and Gemini responses fail silently because they have different structures:
- **Anthropic:** `{:content [{:text "..."}] :usage {:input_tokens N :output_tokens M}}`
- **OpenAI:** `{:choices [{:message {:content "..."}}] :usage {:prompt_tokens N :completion_tokens M}}`
- **Gemini:** `{:candidates [{:content {:parts [{:text "..."}]}}] :usageMetadata {:promptTokenCount N :candidatesTokenCount M}}`

**Chosen Approach:** Normalize responses at the main process boundary (Option A from /idea discussion). Each `query-llm-provider` method transforms its provider's native response into a common `LLMResponse` shape before returning across IPC.

**Normalized Schema:** Text + usage (Option B). Includes the response text and token counts, which enables future cost tracking and usage metrics without overengineering.

**Key Decisions:**
- Transformation happens in main process, not renderer—maintains "explicit boundaries" principle
- Renderer stays provider-agnostic and simple
- Usage tokens normalized across providers for future features (cost tracking, metrics dashboard)
- Schema validation at the IPC boundary catches transformation bugs early

**Tradeoffs:**
- ✅ Renderer completely decoupled from provider-specific formats
- ✅ Single source of truth for response shape (LLMResponse schema)
- ✅ Easy to add new providers—just implement the normalization
- ⚠️ Slightly more work in main process (acceptable—keeps complexity at the edge)

## System Design Overview

The transformation adds a normalization step at the trust boundary between external APIs and internal state. Each `query-llm-provider` defmethod will chain a provider-specific `normalize-response` function after `js->clj` conversion. These pure normalization functions extract text from the provider's response structure and map usage fields to a common shape. The `LLMResponse` schema in `schema.cljs` defines the contract, serving as both documentation and validation. The flow becomes: API fetch → error handling → js→clj conversion → **normalize to LLMResponse** → return to renderer via IPC. The renderer's `llm-response-received` changes from `(get-in [:content 0 :text])` to `(:text response)`, becoming provider-agnostic. All normalization logic stays in `main/effects/llm.cljs`, maintaining the single LLM effects boundary.

## Critical Patterns

### 1. LLMResponse Schema Definition

```clojure
;; In schema.cljs
(def LLMResponse
  "Normalized LLM response shape, independent of provider.
   Main process transforms provider responses to this format before IPC."
  [:map
   [:text :string]
   [:usage [:map
            [:input-tokens :int]
            [:output-tokens :int]
            [:total-tokens :int]]]])
```

**Why this matters:** This schema is the contract between main and renderer. It decouples the renderer from provider specifics and serves as validation at the trust boundary. Any provider that doesn't produce this shape will fail loudly rather than silently.

### 2. Provider-Specific Normalization (Anthropic Example)

```clojure
;; In main/effects/llm.cljs
(defn- normalize-anthropic-response
  "Transforms Anthropic API response to LLMResponse schema."
  [response]
  {:text (get-in response [:content 0 :text])
   :usage {:input-tokens (get-in response [:usage :input_tokens])
           :output-tokens (get-in response [:usage :output_tokens])
           :total-tokens (+ (get-in response [:usage :input_tokens])
                            (get-in response [:usage :output_tokens]))}})

(defmethod query-llm-provider :anthropic
  [messages model api-key]
  ;; ... existing fetch logic ...
  (-> (js/fetch "https://api.anthropic.com/v1/messages" ...)
      (.then #(handle-response % model (count messages)))
      (.then #(js->clj % :keywordize-keys true))
      (.then normalize-anthropic-response))) ; <-- Add normalization step
```

**Why this matters:** This shows the minimal change to existing code—just add a normalization step at the end of the promise chain. The function is pure and easily testable. OpenAI and Gemini follow the same pattern with different field paths.

### 3. Renderer Consumption (Simplified)

```clojure
;; In renderer/actions/messages.cljs
(defn llm-response-received [_state assistant-id response]
  (let [clj-response (js->clj response :keywordize-keys true)]
    [[:loading.actions/set-loading? assistant-id false]
     [:messages.actions/add-to-chat {:id   assistant-id
                                     :type :assistant
                                     :text (:text clj-response)}]])) ; <-- Simplified!
```

**Why this matters:** The TODO comment disappears. The renderer no longer knows or cares about provider differences. This is the proof that normalization at the boundary succeeded.

## Implementation Path

### Phase 1: Add Schema & Anthropic Normalization
**Goal:** Define the contract and normalize the existing working provider first.

**Files to modify:**
- `src/gremllm/schema.cljs` - Add `LLMResponse` schema
- `src/gremllm/main/effects/llm.cljs` - Add `normalize-anthropic-response` and wire into `:anthropic` method
- `test/gremllm/main/effects/llm_test.cljs` - Add unit tests for `normalize-anthropic-response`

**Actions:**
1. Define `LLMResponse` schema using existing `Message` as pattern
2. Write `normalize-anthropic-response` function (pure, testable)
3. Write tests using `mock-claude-response` fixture—verify output matches schema
4. Update `:anthropic` defmethod to chain normalization after `js->clj`
5. Run tests—existing Anthropic tests should still pass (same data, new shape)
6. Commit: "feat: add LLMResponse schema and Anthropic normalization"

**Validation:** `npm run test` passes, Anthropic responses still work in manual testing.

### Phase 2: Normalize OpenAI & Gemini Responses
**Goal:** Implement normalization for remaining providers.

**Files to modify:**
- `src/gremllm/main/effects/llm.cljs` - Add `normalize-openai-response` and `normalize-gemini-response`
- `test/gremllm/main/effects/llm_test.cljs` - Add normalization tests for both providers

**Actions:**
1. Implement `normalize-openai-response`:
   - Text from: `[:choices 0 :message :content]`
   - Usage: `{:input-tokens :prompt_tokens, :output-tokens :completion_tokens, :total-tokens :total_tokens}`
2. Implement `normalize-gemini-response`:
   - Text from: `[:candidates 0 :content :parts 0 :text]`
   - Usage: `{:input-tokens :promptTokenCount, :output-tokens :candidatesTokenCount, :total-tokens (+ prompt candidates)}`
3. Add unit tests for each using `mock-openai-response` and `mock-gemini-response` fixtures
4. Wire normalization into `:openai` and `:google` defmethods
5. Run full test suite
6. Commit: "feat: normalize OpenAI and Gemini responses to LLMResponse"

**Validation:** All unit tests pass, including integration tests if API keys are set.

### Phase 3: Update Renderer to Use Normalized Response
**Goal:** Simplify renderer code and remove provider-specific logic.

**Files to modify:**
- `src/gremllm/renderer/actions/messages.cljs` - Update `llm-response-received`
- `test/gremllm/renderer/actions/messages_test.cljs` - Update tests to use normalized shape

**Actions:**
1. Change `llm-response-received` from `(get-in [:content 0 :text])` to `(:text response)`
2. Remove the TODO comment about Anthropic hardcoding
3. Update renderer tests to expect `{:text "..." :usage {...}}` instead of provider-specific shapes
4. Run full test suite (unit + integration if possible)
5. Manual smoke test: Send messages using Claude, then switch to GPT model, verify both work
6. Commit: "refactor: use normalized LLMResponse in renderer"

**Validation:** Can successfully chat with all three providers (Claude, GPT, Gemini) end-to-end.

### Phase 4: Consider Usage Display (Optional Polish)
**Goal:** Decide if token usage should be visible in UI.

**Deferred decision:** Now that usage data is available, should we display it? Options:
- Show token counts below each message
- Add a usage summary in settings/stats
- Keep it internal for future cost tracking only

**Validation:** This phase is optional—system is fully functional after Phase 3.

## Open Questions (Deferred)

1. **Usage metrics persistence:** Should we persist token usage to topic files for historical tracking? *Recommendation: Wait until there's a clear user need (e.g., cost tracking feature).*

2. **Error response normalization:** Should we also normalize error responses across providers, or keep current error handling? *Recommendation: Current error handling is adequate; defer until we see provider-specific error quirks in practice.*

3. **Additional metadata:** Should we preserve `finish_reason` / `stop_reason` in the normalized response for debugging? *Recommendation: Add only if debugging requires it—YAGNI for now.*

## First Files to Read

Before starting implementation:
1. `src/gremllm/schema.cljs` - Study `Message` and `Topic` schemas for patterns
2. `src/gremllm/main/effects/llm.cljs` - Current multimethod implementation
3. `test/gremllm/main/effects/llm_test.cljs` - Mock response fixtures and testing patterns
4. `src/gremllm/renderer/actions/messages.cljs:35-42` - Current broken implementation with TODO

## Acceptance Criteria

- [ ] `LLMResponse` schema defined in `schema.cljs`
- [ ] All three providers normalize responses to `LLMResponse` shape
- [ ] Renderer uses `(:text response)` instead of provider-specific paths
- [ ] TODO comment removed from `llm-response-received`
- [ ] All existing tests pass
- [ ] New tests cover normalization for all three providers
- [ ] Can successfully chat with Claude, GPT, and Gemini models end-to-end
- [ ] Token usage data is preserved and accessible (even if not yet displayed)
