# Design: Docs Retention Cleanup and ACP Closeout

**Date:** 2026-04-21
**Status:** Approved for cleanup work

## Goal

Reduce `docs/` to material worth archiving:

- keep durable product decisions, active governing specs, and hard-to-rediscover technical findings
- drop superseded plans, spike execution logs, and raw diagnostic notes once their conclusions are preserved
- make the current ACP packaged-host branch self-explanatory by moving load-bearing findings into code comments and one short closeout note backed by primary sources

## Retention Standard

Keep a document only if it is at least one of:

- the current master plan for a still-live product track
- an approved feature design that still governs code expected to be built or maintained
- a technical finding that is still referenced by production code or would be expensive to rediscover

Drop or replace a document if it is one of:

- a superseded plan
- a spike execution log
- raw research that mattered only before a decision was made
- diagnostics whose useful conclusion can live in tests, code comments, or a short closeout note

## Decisions

### Keep

- `docs/plans/2026-02-11-scooter-vertical-slices.md`
- `docs/specs/2026-04-14-s8-staged-selections-ai-context-design.md`
- `docs/2026-03-05-diff-match-patch-anchoring-proposal.md`
- `docs/gemini-generated-flyer.jpg`
- `docs/specs/2026-04-20-acp-packaged-app-bridge-loading-fix-design.md`
- `docs/specs/2026-04-20-acp-packaged-app-npx-agent-launch-investigation.md`

### Remove

- `docs/plans/2026-02-09-document-first-pivot.md`
- `docs/plans/2026-04-20-acp-packaged-app-launch-research-spikes.md`
- `docs/plans/2026-04-21-spike-in-process-acp-host.md`
- `docs/2026-04-20-acp-packaged-app-launch-r1-r4-research-memo.md`
- `docs/acp-poc-learnings.md`
- `docs/specs/2026-04-18-instrument-acp-stack.md`
- `docs/2026-04-20-acp-current-test-diagnostic-findings.md`

## ACP Closeout Note

Replace the removed ACP spike cluster with one short durable note in `docs/` that records:

- the final packaged-host decision
- the exact production files that depend on that decision
- the primary-source references that justify the non-obvious behavior
- the security and maintenance trade-offs that a future reviewer should re-evaluate before changing the implementation

The closeout note should be short. It is not a replay of the spike. It exists to preserve the final reasoning after the execution notes are deleted.

Filename:

- `docs/2026-04-21-acp-packaged-host-closeout.md`

Required contents:

- final outcome: packaged ACP host uses an in-process bridge
- why `RunAsNode` is enabled in `forge.config.js`
- why `ELECTRON_RUN_AS_NODE=1` is injected via `session-meta`
- why `settingSources: []` is present
- why the bridge uses paired `TransformStream`s instead of subprocess stdio
- what remains an explicit trade-off or open maintenance concern

## Code Comment Policy

Move only load-bearing spike findings into code comments. Comments should explain the local consequence, not narrate the whole experiment.

### Required comment sites

- `src/gremllm/main/effects/acp.cljs`
  - explain that the adapter reads `_meta.claudeCode.options`
  - explain that the adapter later hardcodes `executable: process.execPath`, so `_meta.claudeCode.options.executable` is not a working override in the pinned adapter
  - explain why `ELECTRON_RUN_AS_NODE=1` and `settingSources: []` are injected on session creation

- `forge.config.js`
  - explain why `FuseV1Options.RunAsNode` is enabled
  - record the security trade-off that enabling it makes `ELECTRON_RUN_AS_NODE` meaningful again

- `src/js/acp/index.js`
  - explain that the bridge is intentionally in-process
  - explain that the ACP SDK supports generic streams, so paired `TransformStream`s are the chosen transport instead of stdio

- `src/js/acp/permission.js`
  - keep the product-specific nuance that approving in-workspace edits allows a reviewable proposal path rather than direct disk mutation

## Primary-Source Policy

When a retained code comment or closeout note cites implementation behavior, it should point to official docs or upstream code, not branch-local notes.

Preferred sources for this cleanup:

- Electron fuse docs:
  - `https://packages.electronjs.org/fuses`
  - use this for the statement that `RunAsNode` controls whether `ELECTRON_RUN_AS_NODE` is disabled

- Upstream `claude-agent-acp` code for the pinned package version in this repo:
  - upstream repo: `https://github.com/agentclientprotocol/claude-agent-acp`
  - tagged source root for the pinned version: `https://github.com/agentclientprotocol/claude-agent-acp/tree/v0.29.2`
  - local pinned version: `@agentclientprotocol/claude-agent-acp@0.29.2`
  - `node_modules/@agentclientprotocol/claude-agent-acp/dist/acp-agent.js:1095-1137`
  - the useful anchors in that range are:
    - `_meta.claudeCode.options` extraction
    - default `settingSources`
    - env merge behavior
    - hardcoded `executable: process.execPath`
    - `CLAUDE_CODE_EXECUTABLE` and static-binary override path

- Upstream ACP SDK code for stream transport capability:
  - upstream repo: `https://github.com/agentclientprotocol/typescript-sdk`
  - `node_modules/@agentclientprotocol/sdk/dist/acp.js`
  - `node_modules/@agentclientprotocol/sdk/dist/stream.js`
  - these are sufficient to justify `ClientSideConnection`, `AgentSideConnection`, and `ndJsonStream` over generic streams

Do not cite deleted spike plans from comments. The new closeout note may mention that earlier spike notes were consolidated, but it should not depend on them for authority.

## Cleanup Rules

Before deleting a doc:

- search the repo for references to it
- migrate any surviving conclusion or primary-source citation needed by kept code or docs
- update references so no remaining doc points at the deleted file

After cleanup:

- the surviving docs tree should read as current guidance, not archaeology
- the branch should be understandable from the kept design docs, the ACP closeout note, and the code comments alone

## Verification

The cleanup is complete when all of the following are true:

- no remaining repo references point to deleted docs
- the kept docs set matches the decisions above
- the ACP closeout note exists and cites primary sources
- the required code comment sites explain the non-obvious ACP packaged-host behavior
- a reviewer can answer "why is this here?" for `RunAsNode`, `ELECTRON_RUN_AS_NODE`, `settingSources: []`, and the in-process `TransformStream` bridge without reading deleted spike notes

## Non-Goals

- changing the ACP architecture beyond clarifying comments and references
- preserving every exploratory branch note under `docs/archive/`
- rewriting unrelated product specs for style only
