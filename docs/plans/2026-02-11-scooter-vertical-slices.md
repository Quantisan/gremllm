# Document-First Pivot — Scooter Master Plan (Vertical Slices)

**Date:** 2026-02-11
**Supersedes:** `2026-02-09-document-first-pivot.md`
**References:** `test/acp-native-tools-spike.mjs` (read behavior), `test/acp-agent-document-interaction.mjs` (write/diff behavior)

## Vision

Gremllm pivoted from chat-first to document-first. The red-lining concept is the core interaction: AI produces, human reviews and steers via inline comments anchored to text. AI responds with tracked changes (diff-style suggestions) that the user accepts or rejects per block.

**Conceptual model:**
- **One main document per workspace** — convention: `<workspace>/document.md`
- **Topics serve the Document** — topics are conversations in service of the document
- **Topics = Named ACP Sessions** — for Scooter, a Topic is a named ACP session with locally-cached messages

## The Core Loop

```
Document with content (AI-generated)
  → User reads, selects text, adds annotations (margin comments)
  → Annotations queue up → user switches to chat
  → Queued annotations become structured context for AI
  → AI responds in chat + proposes structured changes to document
  → Changes appear INLINE as tracked changes (diff view)
  → Accepted → merged into content
  → Rejected → quick feedback → fed back to AI → retry
```

## Approach: Vertical Slices

Each slice cuts through all implementation layers for one narrow capability. After each slice, there's a visible, testable result. Slices are ordered by dependency — each builds on the previous.

Research that the old plan handled via standalone spikes is folded into the slice that needs it.

---

### S1: Load and display a workspace document

**Capability:** Open a workspace containing `document.md`, see it rendered in the document panel.

| Layer | Work |
|-------|------|
| File I/O | Read `document.md` from workspace folder |
| IPC | `WorkspaceSyncData` includes document content (or path + content) |
| State | Renderer stores document content |
| UI | Replace hardcoded stub with rendered markdown |

**Testable result:** Open a workspace with a `document.md` file. The document panel shows its content formatted as markdown.

**Notes:**
- Markdown rendering library needed (marked, markdown-it, or similar)
- Document panel currently has a hardcoded PE investment memo stub — this gets replaced
- No Topic schema changes

---

### S2: Create a document from the UI

**Capability:** When no document exists, create one via a button. File appears on disk, panel shows it.

| Layer | Work |
|-------|------|
| UI | "Create Document" button in empty-state document panel |
| IPC | `document/create` channel |
| File I/O | Write template markdown file to `<workspace>/document.md` |
| State | Update renderer with new document content |
| UI | Panel switches from empty state to showing the new file |

**Testable result:** Open a workspace with no `document.md`. Click "Create Document." File appears on disk. Document panel shows it.

---

### S3: AI reads the document on demand

**Capability:** AI can reference document content without it being pasted into the prompt. Ask "what's in my document?" and AI answers correctly.

| Layer | Work |
|-------|------|
| ACP | Include `resource_link` pointing to `document.md` in prompts |
| Prompt context | Pass file URI/path context so the agent can invoke native read tools on demand |
| Observability | Capture native tool usage (`Read` tool calls, permission events, and success/failure) for debugging |
| Error handling | Return explicit assistant-visible failure context when document link is missing or inaccessible |

**Testable result:** Chat with AI, ask about document content. AI answers accurately by reading the linked document on demand via native tools, without pasting full file content into the prompt body.

**Research folded in:** `test/acp-native-tools-spike.mjs` proved read-on-demand works with `resource_link` + native agent tools (`Read` call observed) even with `clientCapabilities.fs = {}`. S3 adopts this path for read-only access. Write behavior remains covered in S4.

---

### S4a: ACP behavior spikes

**Goal: Learning, not shipping.** S4a runs targeted experiments in the spike harness to answer architectural questions before production wiring. No production code is touched.

**Learning unknowns to resolve:**

| # | Unknown | Spike approach |
|---|---------|----------------|
| L3 | Ambiguous anchoring | Test with a document containing repeated text. Determine if `oldText` + `locations[].line` reliably identifies the target |
| L5 | Re-read hint effectiveness | Run a multi-turn spike where each prompt includes `resource_link` plus a fixed re-read hint. Between prompts, apply accepted edits to disk and verify the agent performs a fresh read and proposes diffs against latest file state |

**Testable result:** Documented findings for L3 and L5 recorded in "Architectural Decisions from S4a" below.

**Research context:**
- Spike (`test/acp-agent-document-interaction.mjs`) proved dry-run interception works: `tool_call_update` with `{oldText, newText, path}` arrives before/concurrent with `writeTextFile`. Blocking `writeTextFile` (return `{}`) captures the edit without disk mutation.
- Expert analysis identified key risks: ambiguous content-addressed anchoring in repetitive prose, compound edit incoherence when agent re-reads stale state, partial accept of semantically coupled edits, and no back-channel for rejection communication.

**Takeaway findings (L3 spike `test/spike-l3-anchoring.mjs`, 2026-02-18):**
- Observed run classified as `content-only`: one diff item, `oldTextOccurrences=1`, `targetedThird=yes`, and only the intended paragraph content was edited.
- `locations[].line` did not validate the target in that run (`locationLine=11` vs expected third-paragraph range `15-18`), so line metadata looked noisy.
- Provisional decision for S4b: treat `oldText` as the primary anchor and `locations[].line` as advisory; keep L3 open until repeated runs and varied fixtures confirm reliability.

**Takeaway findings (L5 spike `test/spike-l5-reread.mjs`, 2026-02-18):**
- In one A/B run, both conditions (`with hint`, `without hint`) performed fresh reads in turn 2 (`turn2ReadCount=2`) and read post-turn-1 disk state (`turn2SawUpdatedContent=yes`).
- Turn-1 writes were confirmed in both conditions (`turn1WroteNewContent=yes`), so the turn-2 freshness check was valid.
- Provisional decision for S4b: keep `resource_link` on each prompt; treat explicit re-read hint text as optional/redundant for now, pending repeated runs across varied fixtures/models.

---

### S4b: Production wiring

**Capability:** AI proposes document edits via ACP. Edits are captured as structured diff data without touching disk. Diffs are visible in renderer state via Dataspex.

| Layer | Work |
|-------|------|
| ACP | Implement `readTextFile`/`writeTextFile` callbacks in JS adapter |
| ACP | Block `writeTextFile` (dry-run mode) to prevent disk mutation |
| ACP | Set `clientCapabilities.fs` to `{ readTextFile: true, writeTextFile: true }` to enable fs callback path |
| Schema | Expand `AcpUpdate` codec to model diff content items (`{type, path, oldText, newText}`) and edit tool kinds |
| State | Route `tool-call-update` diff events into renderer state (currently just `console.log`) |
| State | Store captured diffs as pending proposals at `[:document :pending-diffs]` |

**Testable result:** Ask AI to edit a document. Diff data appears in Dataspex at `[:document :pending-diffs]`. No file written to disk. (Verifies L1: end-to-end reliability through real app plumbing.)

---

### Architectural Decisions from S4 (to be filled after spikes)

Decisions that S4a learning will inform:

- **Prompt refresh strategy:** Is explicit re-read hinting (with `resource_link`) sufficient for reliable fresh reads on subsequent prompts after disk changes? (L5)
- **Edit anchoring:** Content-addressed (`oldText`) sufficient, or need `locations[].line` + content verification? (L3)

Decisions deferred to S5 (require a working pipeline to experiment against):

- **Accept/reject granularity:** Per-edit or per-batch (all edits in one response)? (L4)
- **Edit granularity control:** Prompt-driven, post-processed, or both? (L2)
- **Diff rendering strategy:** How to anchor `oldText`/`newText` regions in the rendered document for inline tracked changes? (L6)
- **Change identity:** How to assign stable IDs to proposed changes for S6 rejection feedback?

---

### S5: AI proposes tracked changes

**Builds on architectural decisions made in S4. The prompt refresh strategy, accept/reject granularity, and edit anchoring approach are determined by S4 spike findings.**

**Capability:** AI suggests edits as inline diffs. User sees deletions/insertions in the document flow and can accept or reject each block.

| Layer | Work |
|-------|------|
| ACP | Parse `tool_call_update` type:"diff" into structured change objects |
| State | Store pending changes (`{:original-text :proposed-text :anchor :status}`) |
| UI | Unified diff blocks embedded in document flow (deletion/insertion per block) |
| UI | Accept/reject controls per change block |
| File I/O | Apply accepted changes to disk, recompute offsets |
| Schema | TrackedChange data model (informed by ACP response shape and S4 findings) |

**Testable result:** Ask AI to "make the executive summary more concise." AI proposes a shorter version as a tracked change. See the diff block inline in the document. Accept it — content updates. Reject it — change disappears.

**Research folded in:** S4 spike findings determine interception strategy and anchoring approach. Key questions to resolve during S5:

- **Edit granularity (L2):** What the agent naturally produces; whether prompt instructions or `rawOutput` post-processing yields better change boundaries. Try prompt instructions for logical chunks. Try post-processing unified diff into sub-hunks.
- **Multi-edit composition (L4):** Observe independent vs. sequential-dependent edits. Does partial accept require dependency tracking? Implications for S6 rejection handling when edits are coupled.
- **Diff anchoring in rendered view (L6):** Locate `oldText`/`newText` regions in the rendered DOM. Options to spike: (a) text-search `oldText` in DOM text content to locate containing nodes and wrap them; (b) renderer that attaches source-position metadata to DOM nodes; (c) chunked rendering — split source into unchanged markdown chunks and change-region chunks, render unchanged as markdown, render change regions as raw diff markup. Note: markdown rendering destroys source addressability — formatting chars are collapsed, list/table markup is restructured, and DOM text offsets diverge from source offsets.
- Offset recomputation on accept; multiple simultaneous changes.

---

### S6: Rejection feedback loop

**Capability:** Rejecting a change prompts for quick feedback. Feedback is auto-sent to AI. AI retries.

| Layer | Work |
|-------|------|
| UI | Rejection triggers feedback input (quick options + custom text) |
| State | Format rejection + feedback as structured context |
| ACP | Auto-send feedback to AI |
| State | Track retry cycle |

**Testable result:** Reject a tracked change with feedback "too formal." AI automatically retries with a less formal version.

---

### S7: User selects text and adds annotations

**Capability:** Select text in the rendered document, type a note, see it in the margin.

| Layer | Work |
|-------|------|
| UI | Text selection handling on rendered markdown |
| UI | Annotation input (popover or inline) |
| State | Store annotations (text range + comment + status) |
| UI | Margin comments visible alongside document |
| Schema | Annotation data model (informed by implementation experience) |

**Testable result:** Select a paragraph in the document panel. Type "this claim needs evidence." See the annotation in the margin.

**Research folded in:** What was Spike A (annotation anchoring) happens during implementation. Key questions to resolve: DOM selection → markdown position mapping, granularity (paragraph-level vs. character offsets vs. quoted-text substring).

---

### S8: Annotations become AI context

**Capability:** Annotations are bundled into the next chat message as structured context. AI responds with awareness of the annotations.

| Layer | Work |
|-------|------|
| State | Format queued annotations as structured data |
| ACP | Enrich prompt with annotation context (pure function: `enrich-prompt`) |
| UI | Annotations marked as "sent" after inclusion |
| State | Annotation status transitions (`:queued` → `:active`) |

**Testable result:** Add annotation "this claim needs evidence." Send a chat message. AI response references the annotation and addresses it.

---

## Constraints

These carry forward from the original plan:

- **Don't rename Topic → Document.** The conceptual mapping is unclear. Build first, name later.
- **Don't restructure sidebar/workspace.** Sidebar still lists Topics. Focus on the document panel.
- **Don't build "Fix now."** Immediate inline loop is Bicycle scope.
- **Don't build advisor agents.** Research, Analyst, Editor — all Bicycle. Scooter has one AI.
- **Don't over-engineer content model.** Scooter content = markdown string. No structured blocks.
- **Don't build process trail capture.** Comment history, provenance — Motorcycle scope.
- **Don't persist change history.** Only `:pending` changes are persisted. Accepted → merged. Rejected → discarded.

## What Stays the Same

- Sidebar, topic list, workspace UX
- `PersistedTopic` schema and file structure (`<workspace>/topics/`)
- ACP subprocess model (single process, multiplexed sessions)
- ACP session 1:1 with topic
- IPC boundary pattern (preload, correlation IDs)
- Nexus state management + effect architecture
- Three-zone layout (nav strip, document panel, chat panel)
- Message schema, Settings/secrets, FCIS discipline

## Spike Findings (Reference)

Complete. Key findings for implementation:

1. Native read path: `resource_link` enables demand-read through native agent tools (no upfront context dumping), with `Read` tool invocation observed in `test/acp-native-tools-spike.mjs`.
2. Structured write/diff path: `tool_call_update` events with `type: "diff"` provide structured change proposals (`{oldText, newText, path}`) in `test/acp-agent-document-interaction.mjs`.
3. `writeTextFile` with dry-run mode blocks disk writes while capturing proposed edits (write-path reference spike).
4. Both structured diff and unified diff format are available in `rawOutput` for tracked-change rendering.
5. File-on-disk is source of truth.
6. **Prompt-scoped interaction loop:** The edit cycle is prompt-scoped — agent reads real file at prompt start, proposes edits, user accepts/rejects, accepted changes hit disk, next prompt sees updated file. S4a/L5 verifies whether explicit re-read hinting on each prompt reliably triggers fresh reads after file changes.
7. **Change display approach:** Unified diff blocks embedded in document flow (not side-by-side panels, not inline tracked changes in rendered HTML). Surrounding content renders as markdown; change regions show deletion/insertion with accept/reject controls.
8. **Expert-identified risks:** Ambiguous content-addressed anchoring, compound edit incoherence, partial accept of coupled edits, no rejection back-channel to agent, no change identity across cycles.

## Domain Model

### Current: `PersistedTopic` (unchanged through Scooter)

```clojure
[:map
 [:id :string]
 [:name :string]
 [:acp-session-id {:optional true} :string]
 [:messages {:default []} [:vector Message]]]
```

### Directional: Annotation (refined during S5)

```clojure
[:map
 [:id :string]
 [:text :string]
 [:anchor [:map
   [:start :int]
   [:end :int]
   [:quoted-text :string]]]
 [:status [:enum :queued :active :resolved]]
 [:created-at :int]]
```

### Directional: TrackedChange (refined during S7)

```clojure
[:map
 [:id :string]
 [:original-text :string]
 [:proposed-text :string]
 [:anchor [:map
   [:start :int]
   [:end :int]]]
 [:status [:enum :pending :accepted :rejected]]
 [:related-annotation-ids {:default []} [:vector :string]]
 [:rejection-feedback {:optional true} :string]]
```

These are directional. Final schemas are informed by implementation experience in their respective slices.

## Hard Problems (by slice)

| Problem | Slice | Notes |
|---------|-------|-------|
| Markdown rendering in document panel | S1 | Library choice, styling integration with PicoCSS |
| Native read-tool mediation (`resource_link` + permissions + observability) | S3 | Ensure reliable on-demand reads and clear failure handling when access is denied/unavailable |
| Content extraction from AI responses | S4b | Separating document content from conversation |
| Dry-run interception reliability through real app plumbing | S4b | Spike proved mechanism; S4b verifies end-to-end in real app |
| Re-read reliability after between-prompt disk changes | S4a/S4b | S4a validates always-on hint behavior in spike harness; production may later optimize to change-aware hinting |
| Content-addressed anchoring ambiguity in repetitive prose | S4a/S5 | Spiked in S4a; anchoring strategy applied in S5 |
| Edit granularity (agent-controlled vs post-processed) | S5 | Determines UX of change review |
| Markdown source↔DOM offset divergence for inline tracked changes | S5 | Rendering collapses formatting chars and restructures markup; `oldText` position in source does not map to DOM text offset |
| Annotation anchoring | S7 | DOM selection → markdown position mapping |
| Offset recomputation | S5 | Accepted changes shift downstream offsets |
| Document rendering with overlays | S5 | Markdown + highlights + inline diffs simultaneously |
| Structured output parsing | S5 | `tool_call_update` → TrackedChange records |

## Punted Questions

| Question | When to revisit |
|----------|-----------------|
| What do Topics become? | After S5 — when document + chat interaction is real |
| Topic → Document rename | After domain relationship is understood through building |
| Sidebar/workspace restructuring | After S8 at earliest |
| Multiple chat threads per document | Bicycle |
| Multi-document support | After S1 proves single-doc model |

## Beyond Scooter

**Bicycle:** Specialized advisor agents, structured content model, "Fix now" immediate inline loop, process trail capture.

**Motorcycle:** Full proof and methodology capture — evidence, methodology, and expert judgment visible and verifiable.
