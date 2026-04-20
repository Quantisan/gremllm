# ACP current test diagnostic reads and findings

Date: 2026-04-20
Branch: `diag/instrument-acp-stack`

This note records the current diagnostic read from the ACP instrumentation work on this branch. It is based on the latest passing verification run and the trace files emitted by the integration tests.

## Verification run used for this read

- `npm run test`
  - Result: 123 tests, 410 assertions, 0 failures, 0 errors
- `npm run test:integration`
  - Result: 4 tests, 10 assertions, 0 failures, 0 errors
  - Note: this had to be run outside the sandbox because the sandboxed run hit npm cache permission errors unrelated to ACP behavior

## Latest trace artifacts

- `target/acp-traces/read-only-2026-04-19T22-01-03-389-.edn`
- `target/acp-traces/write-new-file-2026-04-19T22-01-12-608-.edn`
- `target/acp-traces/document-first-edit-2026-04-19T22-01-22-244-.edn`

## Smoke-test read

The plain ACP happy-path test still works:

- session creation succeeds
- prompt execution succeeds
- streaming thought/message chunks arrive
- the final response is `hi`

Read:

- ACP session lifecycle and message streaming are healthy
- the observed problem is not basic ACP connectivity

## Read-only linked-document scenario

Scenario:

- Prompt asked the agent to summarize the first paragraph of the linked document without making changes
- Test path: `test-live-read-only` in `test/gremllm/main/effects/acp_integration_test.cljs`

Observed trace summary:

- `:stop-reason` = `"end_turn"`
- `:event-count` = `15`
- counts = `{:session-update 15}`
- `:read` events = `0`
- `:write` events = `0`
- `:permission` events = `0`

Read:

- ACP did not invoke the client `onReadTextFile` callback for this prompt
- A `resource_link` prompt does not guarantee that ACP will call client file-read hooks
- The agent may be answering from prompt/context alone, or from a read path that bypasses the client callback we instrumented

## Write-new-file scenario

Scenario:

- Prompt asked the agent to create `notes.md` with a single line of content
- Test path: `test-live-write-new-file` in `test/gremllm/main/effects/acp_integration_test.cljs`

Observed trace summary:

- `:stop-reason` = `"end_turn"`
- `:event-count` = `14`
- counts = `{:session-update 13 :permission 1}`
- `:write` events = `0`
- `notes.md` was not created on disk

Observed permission payload:

- tool name = `"Write"`
- coerced kind = `"edit"`
- diff content was present
- diff showed `oldText = nil` and `newText = "hello\n"`

Read:

- ACP recognized the operation as a mutating file action and requested permission for it
- The operation surfaced as a permissioned diff proposal, not a `writeTextFile` callback
- Dry-run behavior still prevented the file from landing on disk
- This suggests that current ACP Write behavior is not flowing through the client `writeTextFile` hook

## Edit-existing-file scenario

Scenario:

- Prompt asked the agent to change the linked document title and nothing else
- Test path: `test-live-document-first-edit` in `test/gremllm/main/effects/acp_integration_test.cljs`

Observed trace summary:

- `:stop-reason` = `"end_turn"`
- `:event-count` = `22`
- counts = `{:session-update 21 :permission 1}`
- `:write` events = `0`

Observed permission payload:

- tool name = `"Edit"`
- coerced kind = `"edit"`
- diff content was present for the title change

Read:

- Existing-file edits follow the same path as new-file writes
- ACP emits a permissioned diff proposal
- ACP does not invoke the client `writeTextFile` callback for this edit path either

## Cross-scenario findings

- `writeTextFile` is not currently the mechanism by which these mutating ACP operations surface to the client
- Both `Write` and `Edit` currently appear as permissioned diff proposals instead
- This confirms the original suspicion behind issue `#211`
- It also extends the suspicion beyond `Edit`: `Write` appears to bypass `writeTextFile` as well
- `resource_link` does not guarantee a `readTextFile` callback

## Schema/coercion gap observed during diagnostics

During the live integration runs, the app logged repeated `ACP session update coercion failed` errors for `usage_update` payloads and some newer `tool_call_update` shapes.

Relevant code paths:

- `src/gremllm/main/effects/acp.cljs`
- `src/gremllm/schema/codec.cljs`

Current read:

- the normalized session-update schema does not currently cover `usage_update`
- those updates are dropped during coercion
- this means trace counts for `:session-update` are lower-bound observations of what ACP emitted
- by contrast, the permission/read/write tap counts are strong signals because they come directly from the instrumented client callbacks

## Bottom line

What the diagnostics say today:

- ACP base prompt/streaming behavior works
- linked-document read-only prompts do not currently hit the client read callback
- mutating prompts do hit permission with diff-shaped payloads
- mutating prompts do not hit the client write callback
- the current app should treat permission + diff proposals as the real mutation signal, not `writeTextFile`
