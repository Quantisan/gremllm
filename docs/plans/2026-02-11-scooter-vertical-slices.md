# Document-First Pivot — Scooter Master Plan (Vertical Slices)

**Date:** 2026-02-11
**Supersedes:** `2026-02-09-document-first-pivot.md`
**References:** `test/acp-agent-document-interaction.mjs` (Spike 0 output)

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
| ACP | Register `readTextFile` callback in main process |
| File I/O | Read file content when ACP subprocess requests it |
| IPC | Wire callback response through main ↔ ACP subprocess |
| ACP | Include `resource_link` pointing to document file in prompts |

**Testable result:** Chat with AI, ask about document content. AI reads it via `readTextFile` callback and answers accurately.

**Research folded in:** Spike 0 already proved this pattern works. Implementation follows Spike 0 findings: `resource_link` + `readTextFile` callback enables demand-read.

---

### S4: AI writes/updates the document

**Capability:** Ask AI to generate or modify document content. Document panel updates with new content.

| Layer | Work |
|-------|------|
| ACP | Handle `writeTextFile` callback (dry-run mode) or `tool_call_update` events |
| File I/O | Write updated content to disk |
| IPC | Notify renderer of document change |
| State | Update document content reactively |
| UI | Document panel re-renders with new content |

**Testable result:** Ask AI "write me an executive summary." Document panel updates. File on disk reflects the changes.

**Research folded in:** Spike 0 proved `writeTextFile` dry-run + `tool_call_update` with `type: "diff"` works. This slice implements the write path. For S4, changes apply directly (no accept/reject yet — that's S7).

---

### S5: User selects text and adds annotations

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

### S6: Annotations become AI context

**Capability:** Annotations are bundled into the next chat message as structured context. AI responds with awareness of the annotations.

| Layer | Work |
|-------|------|
| State | Format queued annotations as structured data |
| ACP | Enrich prompt with annotation context (pure function: `enrich-prompt`) |
| UI | Annotations marked as "sent" after inclusion |
| State | Annotation status transitions (`:queued` → `:active`) |

**Testable result:** Add annotation "this claim needs evidence." Send a chat message. AI response references the annotation and addresses it.

---

### S7: AI proposes tracked changes

**Capability:** AI suggests edits as inline diffs. User sees insertions/deletions in the document and can accept or reject each one.

| Layer | Work |
|-------|------|
| ACP | Parse `tool_call_update` type:"diff" into structured change objects |
| State | Store pending changes (`{:original-text :proposed-text :anchor :status}`) |
| UI | Inline diff rendering (strikethrough deletions, highlighted insertions) |
| UI | Accept/reject controls per change block |
| File I/O | Apply accepted changes to disk, recompute offsets |
| Schema | TrackedChange data model (informed by ACP response shape) |

**Testable result:** Annotate "too verbose." Chat with AI. AI proposes a shorter version as a tracked change. See the diff inline in the document. Accept it — content updates. Reject it — change disappears.

**Research folded in:** What was Spike B (ACP response shape for tracked changes) happens during implementation. Spike 0 already proved the `tool_call_update` format. Key questions to resolve: offset recomputation on accept, multiple simultaneous changes.

---

### S8: Rejection feedback loop

**Capability:** Rejecting a change prompts for quick feedback. Feedback is auto-sent to AI. AI retries.

| Layer | Work |
|-------|------|
| UI | Rejection triggers feedback input (quick options + custom text) |
| State | Format rejection + feedback as structured context |
| ACP | Auto-send feedback to AI |
| State | Track retry cycle |

**Testable result:** Reject a tracked change with feedback "too formal." AI automatically retries with a less formal version.

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

## Spike 0 Findings (Reference)

Complete. Key findings for implementation:

1. `resource_link` + `readTextFile` callback enables demand-read (no upfront context dumping)
2. `tool_call_update` events with `type: "diff"` provide structured change proposals: `{oldText, newText, path}`
3. `writeTextFile` with dry-run mode blocks disk writes while capturing proposed edits
4. Both structured diff and unified diff format available in `rawOutput`
5. File-on-disk is source of truth

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
| ACP readTextFile callback wiring | S3 | Spike 0 proved the pattern; implementation is plumbing |
| Content extraction from AI responses | S4 | Separating document content from conversation |
| Annotation anchoring | S5 | DOM selection → markdown position mapping |
| Offset recomputation | S7 | Accepted changes shift downstream offsets |
| Document rendering with overlays | S7 | Markdown + highlights + inline diffs simultaneously |
| Structured output parsing | S7 | `tool_call_update` → TrackedChange records |

## Punted Questions

| Question | When to revisit |
|----------|-----------------|
| What do Topics become? | After S4 — when document + chat interaction is real |
| Topic → Document rename | After domain relationship is understood through building |
| Sidebar/workspace restructuring | After S6 at earliest |
| Multiple chat threads per document | Bicycle |
| Multi-document support | After S1 proves single-doc model |

## Beyond Scooter

**Bicycle:** Specialized advisor agents, structured content model, "Fix now" immediate inline loop, process trail capture.

**Motorcycle:** Full proof and methodology capture — evidence, methodology, and expert judgment visible and verifiable.
