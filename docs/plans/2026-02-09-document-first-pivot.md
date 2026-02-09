# Document-First Pivot — Master Architectural Plan

**Date:** 2026-02-09 (revised)
**References:** `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`

## Context

Gremllm pivoted from chat-first to document-first. The red-lining concept is the soul: AI produces, human reviews and steers via inline comments anchored to text. AI responds with tracked changes (diff-style suggestions) that the user accepts or rejects per block.

The current architecture is chat-centric — `Topic` is a named chat thread with messages and an ACP session. Everything downstream reflects that model.

**Why this revision:** The original plan treated the pivot as a naming change — Topic → Document across the codebase, then build new features. But Topic is a chat thread. Document is an artifact. These are structurally different concepts and we don't yet know what Topics become in the document-first model. A mechanical rename would create false confidence in a mapping we haven't validated. This revision punts the naming question and focuses on the thing that matters: making the document panel real.

## What We Want

- **The document is the center of gravity.** The artifact is what the user sees first and cares about most. Chat is secondary — a collaboration space that serves the document.
- **Red-lining as the core interaction.** User reads, selects, comments. AI proposes changes as tracked suggestions. User accepts or rejects. This loop IS the product.
- **Iterative delivery with learning gates.** Each version ships a working app. Spikes de-risk hard unknowns before we commit to schemas or architecture. We don't pretend to know things we haven't tested.
- **FCIS discipline maintained.** Pure functions for all business logic (content extraction, annotation management, offset recomputation, prompt enrichment, change extraction). Effects stay at the edges.
- **Schema-driven boundaries.** Schemas are validated at every boundary (IPC, disk, ACP response parsing). But schemas are informed by spikes, not designed upfront.

## What We Explicitly Don't Want

- **Don't rename Topic → Document yet.** The conceptual mapping is unclear. A Topic is a chat thread; a Document is an artifact. Punt until the relationship is understood through building.
- **Don't restructure sidebar/workspace for scooter.** The sidebar still lists Topics. The workspace folder structure stays. Focus effort on the document panel.
- **Don't commit to what Topics become.** They might become conversations, threads, or something else. TBD.
- **Don't design schemas before spikes.** The Annotation and TrackedChange schemas below are directional, not final. Spike findings override them.
- **Don't build "Fix now" in scooter.** Scooter is "Note for later" only. The immediate inline loop is bicycle.
- **Don't build advisors in scooter.** Research, Librarian, Analyst, Strategist, Editor — all bicycle. Scooter has one AI (the main ACP session).
- **Don't over-engineer the content model.** Scooter content is a markdown string. No structured blocks, no stable paragraph IDs, no rich text.
- **Don't build process trail capture.** Comment history, change history, provenance — all motorcycle.
- **Don't persist change history.** Only `:pending` changes are persisted. Accepted changes merge into content. Rejected changes are discarded.
- **Don't build document creation flows yet.** For scooter, the first AI response in chat becomes the document content.
- **Don't build multi-thread per document yet.** Eventually want branching and multiple chat threads, but not in scooter.

---

## The Core Loop

```
Document with content (AI-generated)
        ↓
User reads, selects text, adds annotations (margin comments)
        ↓
Annotations queue up → user switches to chat
        ↓
Queued annotations become structured context for AI
        ↓
AI responds in chat + proposes structured changes to document
        ↓
Changes appear INLINE in document as tracked changes (diff view)
  - Original text (strikethrough)
  - Proposed text (highlighted insertion)
  - Accept / Reject per block
        ↓
Accepted → merged into content
Rejected → quick feedback prompt → fed back to AI → retry
```

---

## Domain Model

### Current: `PersistedTopic` (stays for scooter)

```clojure
[:map
 [:id :string]
 [:name :string]
 [:acp-session-id {:optional true} :string]
 [:messages {:default []} [:vector Message]]]
```

This schema stays. No renames, no restructuring. Sidebar lists Topics. Persistence writes to `<workspace>/topics/<topic-id>.edn`.

### Scooter addition: `:content` field

The only schema change for v1b — an optional `:content` field on PersistedTopic:

```clojure
[:map
 [:id :string]
 [:name :string]
 [:acp-session-id {:optional true} :string]
 [:content {:optional true} :string]              ; markdown artifact, populated from AI response
 [:messages {:default []} [:vector Message]]]
```

How `:content` gets populated is the central question for Spike 0.

### Directional: Annotation (subject to Spike A findings)

```clojure
Annotation = [:map
 [:id :string]
 [:text :string]
 [:anchor [:map
   [:start :int]
   [:end :int]
   [:quoted-text :string]]]
 [:status [:enum :queued :active :resolved]]
 [:created-at :int]]
```

### Directional: TrackedChange (subject to Spike B findings)

```clojure
TrackedChange = [:map
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

These are directional. Do not build to them until their corresponding spike completes.

---

## Chat ↔ Document Data Flow

**Annotations → Chat Context:** When user sends a chat message, queued annotations are bundled into the prompt as structured context. Pure function: `(enrich-prompt user-text queued-annotations)`.

**AI Response → Document Content:** Mechanism TBD (Spike 0). The first AI response populates `:content`. Subsequent responses may update it via tracked changes.

**AI Response → Tracked Changes:** Structured output from ACP agent, parsed post-completion (not during streaming). Format TBD (Spike B).

**Rejection Feedback → AI:** Auto-sent as follow-up prompt with the original text, proposed text, and terse feedback.

---

## Persistence, IPC, Workspace

All unchanged for scooter. When `:content` is added in v1b, it persists alongside existing fields in the same EDN file.

- **Persistence:** `<workspace>/topics/<topic-id>.edn`
- **IPC:** `topic/save`, `topic/delete` — unchanged
- **Workspace:** Keep as-is. Folder of topics.

---

## What Stays the Same

- **Sidebar, topic list, workspace UX** — all unchanged for scooter
- **Topic naming, schema, and file structure** — PersistedTopic, `<workspace>/topics/`, state keys `:topics`, `:active-topic-id`
- Workspace as folder concept
- ACP subprocess model (single process, multiplexed sessions)
- ACP session 1:1 with topic
- IPC boundary pattern (preload, correlation IDs)
- Nexus state management + effect architecture
- Three-zone layout (nav strip, document panel, chat panel)
- Message schema (for chat history)
- Settings/secrets
- FCIS discipline

---

## Iterative Scooter Progression

Each version ships a working app. Spikes de-risk the hardest unknown before the version that depends on it.

### Spike 0: Content extraction from AI responses
- **Not shipped.** Research spike.
- AI responses mix conversational text with document content. How do we separate them?
- Explore: Does ACP support structured output, tool use, or artifacts that let the agent return document content separately from chat text?
- Explore: What do other ACP clients (Zed, etc.) do?
- Explore: Can we control this via system prompt / agent configuration?
- Explore: Is this a post-processing step (parse from response), a streaming concern, or an agent-side tool call?
- **Informs:** How `:content` gets populated in v1b — streamed vs post-processed, data shape, extraction logic.

### Scooter v1b: "The document is real"
- PersistedTopic gets `:content` field (markdown string)
- Content population mechanism informed by Spike 0
- Document panel renders formatted markdown (replaces static stub in `renderer.ui.document`)
- Content derived from the active topic's `:content` field
- Chat works exactly as before, secondary to document
- **Learns:** Does the two-panel model feel right? Is markdown the right content format? What does "AI generates a document" look like in practice?

### Spike A: Annotation anchoring
- **Not shipped.** Throwaway experiment.
- Try capturing text selection in rendered markdown
- Try mapping DOM selection back to markdown source positions
- Explore granularity: paragraph-level? quoted-text substring? character offsets?
- **Informs:** Annotation schema, annotation draft state shape, document rendering approach

### Scooter v2: "Annotate and discuss"
- Annotation schema informed by Spike A findings
- User annotates the document (mechanism proven viable by spike)
- Annotations queue up in margin
- Queued annotations bundled into ACP prompt as structured context
- AI responds with annotation awareness
- **Learns:** Does the "batch comments → discuss in chat" flow work? What does the AI actually produce in response to annotations?

### Spike B: ACP response shape for tracked changes
- **Not shipped.** Observational experiment.
- Using v2's annotation-aware prompts, observe raw ACP responses
- Learn: What does the AI return when asked to revise flagged passages?
- Learn: Can we reliably get structured change blocks? What format?
- **Informs:** TrackedChange schema, response parsing pipeline, streaming vs. post-completion extraction

### Scooter v3: "Tracked changes"
- TrackedChange schema informed by Spike B findings
- AI proposes changes as structured output (format learned from spike)
- Changes rendered inline in document (deletion strikethrough + insertion highlight)
- Accept/reject controls per change block
- Accept merges proposed text into `:content`, recomputes offsets
- Reject discards the change
- **Learns:** Does inline diff rendering work in markdown? Is offset recomputation viable?

### Scooter v4: "Rejection feedback loop"
- Quick feedback prompt on rejection ("too verbose", "wrong tone", "misses point", custom)
- Feedback auto-sent to AI as structured context
- AI retries with feedback awareness
- **Learns:** Does terse feedback produce better output?

---

## Punted Questions

Explicitly deferred. Should not influence scooter implementation decisions.

| Question | Why punted | When to revisit |
|----------|-----------|-----------------|
| What do Topics become? | Don't know yet. Need to build the document experience first. | After v1b — when we see how document + chat interact |
| Topic → Document rename | Premature. The mapping is structural, not cosmetic. | After domain relationship is understood |
| Sidebar/workspace restructuring | No value until document model is proven | After v2 at earliest |
| Multiple chat threads per document | Interesting but speculative | Bicycle |
| Document creation flows | First AI response populates content for now | After v1b validates the content model |

---

## Hard Problems (ranked by architectural impact)

1. **Content extraction from AI responses** — How to separate document content from conversational text in ACP responses (Spike 0).
2. **Structured output parsing** — Bridging ACP response → TrackedChange records. Format TBD (Spike B).
3. **Annotation anchoring** — Mapping DOM text selection to markdown source offsets (Spike A).
4. **Offset recomputation** — When a change is accepted, all downstream offsets shift. Pure function needed.
5. **Document rendering with overlays** — Markdown + highlights + margin comments + inline diffs.
6. **Text selection → annotation flow** — Capturing selection, popover UI, draft state management.

---

## Bicycle (next major stage)

Not in scope for scooter. Listed here for orientation only.

- Topic → Document domain model resolution
- Structured content model (stable block IDs for anchoring)
- "Fix now" immediate inline loop (annotation → instant AI fix, no chat roundtrip)
- Advisor agents (Research, Librarian, Analyst, Strategist, Editor)
- Multiple ACP sessions per document (one per advisor type)
- Process trail capture (annotation + change history as provenance)
