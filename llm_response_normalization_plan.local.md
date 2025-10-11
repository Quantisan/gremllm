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
  "Transforms Anthropic API response to LLMResponse schema.
  Validates the result, throwing if Anthropic returns unexpected shape."
  [response]
  (m/coerce schema/LLMResponse
            {:text (get-in response [:content 0 :text])
             :usage {:input-tokens (get-in response [:usage :input_tokens])
                     :output-tokens (get-in response [:usage :output_tokens])
                     :total-tokens (+ (get-in response [:usage :input_tokens])
                                      (get-in response [:usage :output_tokens]))}}))

(defmethod query-llm-provider :anthropic
  [messages model api-key]
  ;; ... existing fetch logic ...
  (-> (js/fetch "https://api.anthropic.com/v1/messages" ...)
      (.then #(handle-response % model (count messages)))
      (.then #(js->clj % :keywordize-keys true))
      (.then normalize-anthropic-response))) ; <-- Add normalization + validation
```

**Why this matters:** This shows the minimal change to existing code—just add a normalization step at the end of the promise chain. The function is pure and easily testable. **`m/coerce` validates the output matches `LLMResponse` schema, throwing on invalid data** for fail-fast behavior at the trust boundary. OpenAI and Gemini follow the same pattern with different field paths.

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

### Phase 1: Add Schema & Anthropic Normalization ✅ COMPLETE
**Goal:** Define the contract and normalize the existing working provider first.

**Files modified:**
- `src/gremllm/schema.cljs` - Added `LLMResponse` schema
- `src/gremllm/main/effects/llm.cljs` - Added `normalize-anthropic-response` with `m/coerce` validation
- `test/gremllm/main/effects/llm_test.cljs` - Updated tests to verify normalized output

**Actions completed:**
1. ✅ Defined `LLMResponse` schema using existing `Message` as pattern
2. ✅ Wrote `normalize-anthropic-response` function (pure, testable)
3. ✅ **Added Malli validation using `m/coerce`** - throws on invalid provider responses
4. ✅ Updated `:anthropic` defmethod to chain normalization after `js->clj`
5. ✅ Updated tests to verify normalized shape matches `LLMResponse` schema
6. ✅ Committed: "feat: validate Anthropic responses with Malli at provider boundary"

**Validation:** ✅ All 51 tests pass. Anthropic normalization validates at trust boundary.

### Phase 2: Update Renderer to Use Normalized Response ✅ COMPLETE
**Goal:** Simplify renderer code and remove provider-specific logic. Complete Anthropic path end-to-end.

**Files modified:**
- `src/gremllm/renderer/actions/messages.cljs` - Updated `llm-response-received`
- `test/gremllm/renderer/actions/messages_test.cljs` - Updated tests to use normalized shape

**Actions completed:**
1. ✅ Changed `llm-response-received` from `(get-in [:content 0 :text])` to `(:text response)`
2. ✅ Removed the TODO comment about Anthropic hardcoding
3. ✅ Updated renderer tests to expect `{:text "..." :usage {...}}` instead of provider-specific shapes
4. ✅ Ran full test suite - all 51 tests pass
5. ✅ Included in same commit as Phase 1: "feat: validate Anthropic responses with Malli at provider boundary"

**Validation:** ✅ Anthropic chat works end-to-end with validated, normalized responses.

### Phase 3: Normalize OpenAI & Gemini Responses
**Goal:** Implement normalization for remaining providers.

**Files to modify:**
- `src/gremllm/main/effects/llm.cljs` - Add `normalize-openai-response` and `normalize-gemini-response`
- `test/gremllm/main/effects/llm_test.cljs` - Add normalization tests for both providers

**Actions:**
1. Implement `normalize-openai-response`:
   - Text from: `[:choices 0 :message :content]`
   - Usage: `{:input-tokens :prompt_tokens, :output-tokens :completion_tokens, :total-tokens :total_tokens}`
   - Wrap with `m/coerce` for validation
2. Implement `normalize-gemini-response`:
   - Text from: `[:candidates 0 :content :parts 0 :text]`
   - Usage: `{:input-tokens :promptTokenCount, :output-tokens :candidatesTokenCount, :total-tokens (+ prompt candidates)}`
   - Wrap with `m/coerce` for validation
3. Add unit tests for each using `mock-openai-response` and `mock-gemini-response` fixtures
4. Wire normalization into `:openai` and `:google` defmethods
5. Run full test suite
6. Commit: "feat: normalize OpenAI and Gemini responses to LLMResponse"

**Validation:** All unit tests pass, including integration tests if API keys are set. Can successfully chat with all three providers end-to-end.

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

- [x] `LLMResponse` schema defined in `schema.cljs`
- [ ] All three providers normalize responses to `LLMResponse` shape (Anthropic ✅, OpenAI pending, Gemini pending)
- [x] Renderer uses `(:text response)` instead of provider-specific paths
- [x] TODO comment removed from `llm-response-received`
- [x] All existing tests pass
- [ ] New tests cover normalization for all three providers (Anthropic ✅, OpenAI pending, Gemini pending)
- [ ] Can successfully chat with Claude, GPT, and Gemini models end-to-end (Claude ✅, GPT pending, Gemini pending)
- [x] Token usage data is preserved and accessible (even if not yet displayed)
- [x] **Malli validation at trust boundary** - `m/coerce` throws on invalid provider responses
