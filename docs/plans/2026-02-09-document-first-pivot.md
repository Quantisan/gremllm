# Document-First Pivot — Master Architectural Plan

**Date:** 2026-02-09
**References:** `/Users/paul/Google Drive/My Drive/Qintaur
shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining.md`, `/Users/paul/Google Drive/My Drive/Qintaur shared/!Explorations/ide_for_knowledge_workers/experiments/red-lining-mockup.html`

## Context

Gremllm pivoted from chat-first to document-first. The red-lining concept is the soul: AI produces, human reviews and steers via inline comments anchored to text. AI responds with tracked changes (diff-style suggestions) that the user accepts or rejects per block.

The current architecture is chat-centric — `Topic` is a named chat thread with messages and an ACP session. Everything downstream reflects that model. This plan maps what needs to change and in what order.

## What We Want

- **The document is the center of gravity.** The artifact is what the user sees first and cares about most. Chat is secondary — a collaboration space that serves the document.
- **Red-lining as the core interaction.** User reads, selects, comments. AI proposes changes as tracked suggestions. User accepts or rejects. This loop IS the product.
- **Iterative delivery with learning gates.** Each version ships a working app. Spikes de-risk hard unknowns before we commit to schemas or architecture. We don't pretend to know things we haven't tested.
- **Clean domain language.** Topic → Document everywhere. No legacy naming, no gradual migration, no aliases. One clean sweep.
- **FCIS discipline maintained.** Pure functions for all business logic (annotation management, offset recomputation, prompt enrichment, change extraction). Effects stay at the edges.
- **Schema-driven boundaries.** Annotation, TrackedChange, and Document schemas are validated at every boundary (IPC, disk, ACP response parsing). But schemas are informed by spikes, not designed upfront.

## What We Explicitly Don't Want

- **Don't design schemas before spikes.** The Annotation and TrackedChange schemas shown below are directional, not final. Spike findings override them. Don't commit to character-offset anchoring, structured output format, or change block shape until we've tested them.
- **Don't build "Fix now" in scooter.** The red-lining doc describes two paths: "Fix now" (immediate inline) and "Note for later" (batched). Scooter is "Note for later" only. The immediate inline loop is bicycle.
- **Don't build advisors in scooter.** Research, Librarian, Analyst, Strategist, Editor — all bicycle. Scooter has one AI (the main ACP session).
- **Don't over-engineer the content model.** Scooter content is a markdown string. No structured blocks, no stable paragraph IDs, no rich text. Structured content is bicycle.
- **Don't build process trail capture.** Comment history, change history, provenance — all motorcycle. Scooter just makes the core loop work.
- **Don't persist change history.** Only `:pending` changes are persisted. Accepted changes merge into content. Rejected changes are discarded. History is a later concern.
- **Don't worry about prompt engineering.** ACP handles the AI interaction. We control what context we send (annotations, feedback) and what we parse from responses. The agent's behavior is ACP's problem.
- **Don't build document creation flows yet.** How the initial draft gets generated (template? interview? upload?) is punted. For scooter, the first AI response in chat becomes the document content.
- **Don't add backward compatibility.** No migration of existing topic files to document files. Clean break.

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

## Domain Model: Topic → Document (Clean Sweep)

### Current `PersistedTopic`
```clojure
[:map
 [:id :string]
 [:name :string]
 [:acp-session-id {:optional true} :string]
 [:messages [:vector Message]]]
```

### Proposed `Document`
```clojure
[:map
 [:id :string]
 [:name :string]
 [:acp-session-id {:optional true} :string]
 [:content {:optional true} :string]                ; the document artifact (markdown)
 [:annotations {:default []} [:vector Annotation]]   ; user's red-line comments
 [:changes {:default []} [:vector TrackedChange]]    ; AI's proposed edits
 [:messages {:default []} [:vector Message]]]         ; chat history (secondary)
```

Three new first-class concepts: **content**, **annotations**, **tracked changes**.

### Directional Schema: Annotation (subject to Spike A findings)

```clojure
Annotation = [:map
 [:id :string]
 [:text :string]                           ; the user's comment
 [:anchor [:map
   [:start :int]                           ; character offset in :content
   [:end :int]
   [:quoted-text :string]]]                ; snapshot of selected text
 [:status [:enum :queued :active :resolved]]
 [:created-at :int]]
```

### Directional Schema: TrackedChange (subject to Spike B findings)

```clojure
TrackedChange = [:map
 [:id :string]
 [:original-text :string]                           ; what's being replaced
 [:proposed-text :string]                           ; AI's suggestion
 [:anchor [:map
   [:start :int]
   [:end :int]]]
 [:status [:enum :pending :accepted :rejected]]
 [:related-annotation-ids {:default []} [:vector :string]]
 [:rejection-feedback {:optional true} :string]]
```

---

## State Model Changes

### Current → Proposed
```
:topics            → :documents           {id => Document}
:active-topic-id   → :active-document-id  "doc-123"
:form                                      (unchanged)
:topics-ui         → :documents-ui        {:renaming-id nil}
                     :annotation-draft    {:anchor {...} :text ""}  (new)
```

---

## Chat ↔ Document Data Flow

**Annotations → Chat Context:** When user sends a chat message, queued annotations are bundled into the prompt as structured context. Pure function: `(enrich-prompt user-text queued-annotations)`.

**AI Response → Tracked Changes:** Structured output from ACP agent, parsed post-completion (not during streaming). Chat text streams normally; change extraction is a separate post-completion step.

**Rejection Feedback → AI:** Auto-sent as follow-up prompt with the original text, proposed text, and terse feedback.

---

## Persistence, IPC, Workspace

- **Persistence:** `<workspace>/documents/<doc-id>.edn` — same EDN format, richer schema
- **IPC:** `topic/save` → `document/save`, `topic/delete` → `document/delete`. ACP channels unchanged.
- **Workspace:** Keep as-is. Folder of documents instead of topics.

---

## What Stays the Same

- Workspace as folder concept
- ACP subprocess model (single process, multiplexed sessions)
- ACP session 1:1 with document
- IPC boundary pattern (preload, correlation IDs)
- Nexus state management + effect architecture
- Two-panel layout (document left, chat right)
- Message schema (for chat history)
- Settings/secrets
- FCIS discipline

---

## Namespace Rename Map

**Note:** The renames below happen in Scooter v1a as a clean mechanical refactor, not interleaved with new functionality.

| Current | Proposed |
|---------|----------|
| `renderer.state.topic` | `renderer.state.document` |
| `renderer.actions.topic` | `renderer.actions.document` |
| `renderer.ui.topics` | `renderer.ui.documents` |
| `main.actions.topic` | `main.actions.document` |
| — | `renderer.state.annotation` (new, later) |
| — | `renderer.actions.annotation` (new, later) |
| — | `renderer.actions.change` (new, later) |
| — | `renderer.ui.document` (existing stub → real in v1b) |

---

## Hard Problems (ranked by architectural impact)

1. **Structured output parsing** — Bridging ACP response → TrackedChange records. Format TBD (Spike B).
2. **Annotation anchoring** — Mapping DOM text selection to markdown source offsets. Mechanism TBD (Spike A).
3. **Offset recomputation** — When a change is accepted, all downstream offsets shift. Pure function needed.
4. **Document rendering with overlays** — Markdown + highlights + margin comments + inline diffs.
5. **Text selection → annotation flow** — Capturing selection, popover UI, draft state management.

---

## Iterative Scooter Progression

Each version ships a working app. Spikes de-risk the hardest unknown before the version that depends on it.

### Scooter v1a: "Clean sweep rename"
- Topic → Document rename across entire codebase
- Files, namespaces, state keys, action/effect keywords, IPC channels, preload JS, CSS, filesystem paths, schema, docs
- App works identically to skateboard — just new names
- No backward compatibility, no migration (per existing plan)
- **Learns:** Nothing product-wise. De-risks the mechanical refactor so v1b changes are clearly about content, not naming.

### Spike 0: Content extraction from AI responses
- Not shipped. Research spike.
- AI responses mix conversational text with document content. How do we separate them?
- Explore: Does ACP support structured output, tool use, or artifacts that let the agent return document content separately from chat text?
- Explore: What do other ACP clients (Zed, etc.) do?
- Explore: Can we control this via system prompt / agent configuration?
- **Informs:** The content model for v1b — how `:content` gets populated, whether it's streamed or post-processed, what the data shape looks like.

### Scooter v1b: "The document is real"
- Document gets `:content` field (markdown string)
- Content population mechanism informed by Spike 0
- Document panel renders formatted markdown (replaces static stub)
- Chat works exactly as before, secondary to document
- **Learns:** Does the two-panel model feel right? Is markdown the right content format? What does "AI generates a document" look like in practice?

### Spike A: Annotation anchoring
- Not shipped. Throwaway experiment.
- Try capturing text selection in rendered markdown
- Try mapping DOM selection back to markdown source positions
- Explore granularity: paragraph-level? quoted-text substring? character offsets?
- **Learns:** What annotation model is actually achievable? Determines the Annotation schema.
- **Informs:** Annotation schema, annotation draft state shape, document rendering approach

### Scooter v2: "Annotate and discuss"
- Annotation schema informed by Spike A findings
- User annotates the document (mechanism proven viable by spike)
- Annotations queue up in margin
- Queued annotations bundled into ACP prompt as structured context
- AI responds with annotation awareness
- **Learns:** Does the "batch comments → discuss in chat" flow work? What does the AI actually produce in response to annotations? (This naturally feeds into Spike B.)

### Spike B: ACP response shape for tracked changes
- Not shipped. Observational experiment.
- Using v2's annotation-aware prompts, observe raw ACP responses
- Learn: What does the AI return when asked to revise flagged passages?
- Learn: Can we reliably get structured change blocks? What format?
- Learn: How does the response mix conversational text with proposed changes?
- **Informs:** TrackedChange schema, response parsing pipeline, streaming vs. post-completion extraction

### Scooter v3: "Tracked changes"
- TrackedChange schema informed by Spike B findings
- AI proposes changes as structured output (format learned from spike)
- Changes rendered inline in document (deletion strikethrough + insertion highlight)
- Accept/reject controls per change block
- Accept merges proposed text into `:content`, recomputes offsets
- Reject discards the change
- **Learns:** Does inline diff rendering work in markdown? Is offset recomputation viable? Does accept/reject feel right?

### Scooter v4: "Rejection feedback loop"
- Quick feedback prompt on rejection ("too verbose", "wrong tone", "misses point", custom)
- Feedback auto-sent to AI as structured context
- AI retries with feedback awareness
- **Learns:** Does terse feedback produce better output? How many retry cycles before diminishing returns?

---

## Bicycle (next major stage)

Not in scope for scooter. Listed here for orientation only.

- Structured content model (stable block IDs for anchoring)
- "Fix now" immediate inline loop (annotation → instant AI fix, no chat roundtrip)
- Advisor agents (Research, Librarian, Analyst, Strategist, Editor)
- Multiple ACP sessions per document (one per advisor type)
- Process trail capture (annotation + change history as provenance)
