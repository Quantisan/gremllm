# Excerpt-Anchored Sessions

**Date:** 2026-05-25
**Status:** Draft

## Context

Gremllm organizes AI conversations as "Topics" — a flat list in a nav overlay, disconnected from the document content. Users create a topic, optionally add excerpts, and chat. The topic list is the only navigation surface.

This works for early prototyping but breaks the document-first principle. Topics float free of the document; finding a previous conversation means scanning a list of names. The connection between "I was working on this paragraph" and "I had an AI conversation about it" exists only in the user's memory.

## Goal

Anchor AI conversations to the document text that motivated them. A session is a conversation that began at a specific place in the document, stays visually tied to that place, and is discoverable by reading the document itself.

The document becomes the navigation surface. The topic list goes away.

## Design Principles Applied

- **Document-first, not chat-first** — sessions are subordinate to the document. You find them by reading the document, not by browsing a separate list.
- **Simple by default** — one creation path (select text → start session), one navigation mechanism (margin bars), one anchor per session.
- **Expert judgment is elevated** — the anchor records what the user chose to investigate, preserving the "why here?" signal as part of the session's identity.

## Product Decisions

### Anchoring Model

Each session is anchored to exactly one excerpt — the text selection that spawned it. This is the session's visual anchor in the document. The relationship is 1:1: one session, one anchor point, one margin bar.

During a conversation, the user can add more excerpts as context for subsequent turns. These conversation excerpts are distinct from the anchor — they inform the AI but do not create additional visual markers in the document.

**Rationale:** Research across Google Docs, Figma, Notion, GitHub PRs, Hypothesis, and legal annotation tools shows universal 1:1 anchoring. Multi-anchor models create ambiguity ("which marker is the real one?") without solving the discoverability problem that motivated them. The "where's that session?" concern is better addressed by navigation tools than by multiplying anchors.

### Session Creation

Sessions are always created from a text selection. There are no unanchored, free-floating sessions.

When text is selected and a session is already active, the popover offers two actions: **"Start session"** (creates a new session anchored to this selection) and **"Add excerpt"** (adds the selection as conversation context to the current session). Both are always visible.

### Visual Markers

Sessions appear in the document as **colored vertical bars in the right margin**, positioned alongside the anchored excerpt text. Bars span the height of the excerpt's block range. The right-side placement creates a left-to-right flow — document content → margin hints → chat panel — so bars sit adjacent to the session they activate.

- Each session gets a color from a small rotating palette. (Slice 1 derives it as `hash(session-id) mod 5`, so adjacent bars are not guaranteed distinct; a persisted per-session color slot is deferred.)
- The active session's bar is fully opaque; inactive bars are dimmed.
- Clicking a bar switches the chat panel to that session.

Bars do not modify the document text or its rendering pipeline. They are an overlay layer.

When a session is active, its anchor excerpt text receives a subtle highlight (the app already uses the CSS Custom Highlight API for excerpt highlighting). Inactive sessions show only the margin bar.

### Navigation

Margin bars are the sole navigation surface, replacing the topic list entirely. The nav overlay, its toggle button, and all topic list UI (rename, delete, new topic button) are removed.

No session list panel, search, or minimap is included in this version. These are additive features if margin bars prove insufficient for documents with many sessions.

### Chat Panel Behavior

The chat panel remains always-visible on the right. Clicking a margin bar switches which session the panel displays — same interaction as clicking a topic in today's list, just triggered from the document margin.

### Empty State and Document Open

When a document opens with no existing sessions, the chat panel shows an empty state prompting the user to select text. No session is auto-created.

When a document opens with existing sessions, the latest one is auto-activated. (Slice 1 sorts by session id, i.e. creation order, not last-modified — last-modified ordering is a known-deferred refinement.)

### Session Lifecycle

Sessions are resumable and open-ended, identical to today's topics. "Session" refers to the AI interaction context, not a time-bounded event. No lifecycle states (open/closed/archived) are introduced.

### Anchor Versioning

Anchors are scoped to the document version that existed when the session was created. When document content changes, prior anchors are archived as belonging to a previous version and are not displayed. The session data is preserved, not deleted.

This eliminates anchor drift as a design problem — anchors never follow edits, so there is no ambiguity about whether a bar still points to the right text. The mechanics of versioning (how versions are identified, what triggers archival, how archived sessions surface) are deferred.

### Deletion

Session deletion is not included in the initial version. Sessions accumulate. Deletion can be added later through a context menu or session management UI.

## Domain Model

### Session (replaces Topic)

A Session is an AI conversation anchored to a specific place in a document.

- **Anchor** — the document excerpt this session is tied to (the full `DocumentExcerpt` locator data). Required, immutable after creation.
- **Excerpts** — conversation excerpts added during the session, distinct from the anchor. Consumed after each turn, same as today.
- **ACP session** — the underlying agent session (ID, pending diffs). Unchanged.
- **Messages** — conversation history. Unchanged.

The anchor is a new concept. Everything else carries forward from Topic with a name change.

### What "Anchor" Captures

The anchor records the user's intent: "I want to discuss *this specific text*." It stores the selected text and its document location (block refs, line spans) — the same `DocumentExcerpt` shape the app already uses, promoted from a conversation artifact to a session identity.

## Backward Compatibility

None required. This is a pre-release MVP. Existing persisted topic data can be discarded. No migration path is needed.

## Open Questions (Blocking)

These must be investigated before Slice 1 removes the topic list fallback.

### OQ1: Return intent — users come back to a document for different reasons

The spec auto-activates the latest session and expects users to navigate by scanning margin bars. But return behavior splits at least three ways: continuing where you left off, surveying what's been done, and looking for a specific past conversation. The spec treats all three as the same. Which return intent is primary for our users, and does auto-activation serve or disrupt it?

### OQ2: Single-channel navigation — margin bars are the only way in

The spec bets everything on one navigation channel and removes the fallback in Slice 1. Three independent concerns converge here:

- If users scan bars directly rather than reading document text to find them, bars are just an unlabeled, unsearchable list — possibly worse than what they replace.
- At 25+ sessions on a long document, bars may become indistinguishable noise. The spec defers density management but defines no signal for when bars have failed and no criteria for reverting.
- For non-visual users (screen readers, keyboard-only), the channel doesn't exist at all — sessions have no accessible name, no keyboard trigger for the creation popover, and no programmatic active-state signal (CSS opacity and Custom Highlights carry no accessibility tree exposure).

### OQ3: Degraded states — what the user sees when the design's assumptions break

The spec describes the happy path precisely and is silent on every degraded path. Specific cases that need answers:

- **~~Anchor drift~~:** Resolved — anchors are scoped to a document version. When content changes, prior anchors are archived and not displayed (see Anchor Versioning). No drift occurs.
- **Wrong auto-activation:** If the latest session belongs to a different work context (e.g., a colleague's prior session in a shared draft), AI output goes into the wrong conversation silently. Does auto-activation signal itself as automatic?
- **No retrieval cue:** When a user returns after days and doesn't remember where in the document they were working, how do they find the right session without a list or search?

## Design Bets

These are the design's load-bearing assumptions — not problems to solve before shipping, but hypotheses to test with users after implementation. If any bet is wrong, the fix is structural, not incremental. Watch for disconfirming signal early.

### Bet 1: AI sessions behave like annotations

> "Research across Google Docs, Figma, Notion, GitHub PRs, Hypothesis, and legal annotation tools shows universal 1:1 anchoring."

The 1:1 anchoring model and the cited evidence from annotation tools rest on a structural analogy: that AI conversation sessions are the same kind of artifact as comments or annotations. But annotations are reactive — a thought pinned to a fixed referent. AI sessions are exploratory, evolving, and routinely span multiple document regions. The analogy is doing enormous load-bearing work without examination. If AI sessions are a structurally different artifact class, the 1:1 model, the margin bar identity system, and the "no multi-anchor" decision all inherit a flawed premise.

**Watch for:** Sessions that outgrow their anchor — conversations where the anchor text becomes irrelevant halfway through, or where users wish they could "move" a session to a different part of the document.

### Bet 2: The atomic unit of "what I want to discuss" is a text region, not a conceptual thread

> "Sessions are always created from a text selection."
> "The anchor records the user's intent: 'I want to discuss this specific text.'"

The spec treats the document as a collection of nodes — selectable passages — and anchors conversations to them. But knowledge work often follows arrows: a pricing assumption in paragraph 3 that constrains the risk assessment in paragraph 12 that undermines the thesis in the executive summary. The meaningful unit isn't a passage; it's the thread of reasoning connecting passages. A text selection captures a node. It can't capture "the tension between these two claims" or "the logic chain running through sections 2, 4, and 7." If the interesting conversations happen along arrows rather than at nodes, 1:1 anchoring to a text region systematically misses the conversations that carry the most expert signal.

**Watch for:** Users who select text as a starting point but immediately paste or reference other document sections in their first message — a signal that the real unit of discussion is a cross-cutting thread, not a passage.

### Bet 3: Users navigate past conversations by spatial document context, not by content recall

> "The document becomes the navigation surface. The topic list goes away."

The spec bets that users will remember AI conversations by where in the document they happened — spatial recall — rather than by what they were about — content recall. This is a cognitive model claim about how PE analysts actually think. If they search for "the pricing assumptions conversation" rather than "the one near paragraph 12," the document-as-navigation thesis is wrong regardless of how margin bars are implemented. Spatial recall works for small counts and recent sessions; content recall dominates as sessions accumulate and time passes.

**Watch for:** Users scrolling through the document hunting for a margin bar they can't find, or asking "where was that conversation about X?" — both signals that content recall, not spatial recall, is the dominant retrieval mode.

## Staged Delivery

Two slices. Slice 1 front-loads the riskiest unknown — do margin bars work against real document content? — by building the UI first with stubbed data. The app will be broken between slices. Clean-slate assumption: existing topic data is discarded.

### Slice 1: Rendering + Navigation (UI, the risk)

Add the anchor as an in-memory field reusing the existing `DocumentExcerpt` shape (id + text + locator) — no new schema, no timestamp. Stub session creation from the text-selection popover — "Start session" captures a real anchor from the current selection. Render colored margin bars positioned by anchor locator data. Remove the nav overlay and topic list. Wire empty state, bar-click switching, and auto-activation of the latest session on document open. Persistence ignores anchors (the anchor lives only on the in-memory `Topic`, stripped before disk) — sessions don't survive restart. A newly created session is a non-functional **shell**: it shows its anchor and a margin bar, but the composer is disabled and no ACP conversation runs yet (deferred to Slice 2).

**Learns:** Do bars position correctly against real document content? Does the visual treatment work? Does popover-driven creation feel right? How does click-to-switch from the margin feel?

### Slice 2: Anchor Model + Integration (data, the plumbing)

Persist the anchor into `PersistedTopic` as a **required** field and update disk codecs — every session is anchored, so unanchored sessions cannot round-trip. Wire anchor into the full save/load pipeline. Wire the shell session to a live ACP conversation — activating a session initializes ACP and enables the composer. Clean up the creation flow — remove the old "New Topic" / whole-doc creation path entirely (left orphaned in Slice 1). Anchor feeds the ACP prompt as first-message context. Sessions survive restart.

**Learns:** Does the anchor fit cleanly into the schema and persistence layer?

The Topic → Session rename happens when natural — during either slice or after — not as a dedicated phase.

## Out of Scope

- Whole-document sessions (anchor type `:document`) — select text to start any session
- Session naming — the margin bar and excerpt highlight identify sessions visually
- Session list panel, search, or minimap — additive if margin bars prove insufficient
- Session deletion UI
- Session rename UI
- Session lifecycle states (open/closed/archived)
- Annotation density management (clustering, progressive disclosure)
- Scrollbar minimap marks
- Cross-session references or linking
- Anchor versioning mechanics (version identity, archival triggers, surfacing archived sessions) — the design choice is made (see Anchor Versioning); the implementation is deferred
