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

- Each session gets a distinct color from a small rotating palette.
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

When a document opens with existing sessions, the latest one (by last-modified timestamp) is auto-activated.

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

These must be investigated before Stage 3 removes the topic list fallback.

### OQ1: Return intent — users come back to a document for different reasons

The spec auto-activates the latest session and expects users to navigate by scanning margin bars. But return behavior splits at least three ways: continuing where you left off, surveying what's been done, and looking for a specific past conversation. The spec treats all three as the same. Which return intent is primary for our users, and does auto-activation serve or disrupt it?

### OQ2: Single-channel navigation — margin bars are the only way in

The spec bets everything on one navigation channel and removes the fallback in Stage 3. Three independent concerns converge here:

- If users scan bars directly rather than reading document text to find them, bars are just an unlabeled, unsearchable list — possibly worse than what they replace.
- At 25+ sessions on a long document, bars may become indistinguishable noise. The spec defers density management but defines no signal for when bars have failed and no criteria for reverting.
- For non-visual users (screen readers, keyboard-only), the channel doesn't exist at all — sessions have no accessible name, no keyboard trigger for the creation popover, and no programmatic active-state signal (CSS opacity and Custom Highlights carry no accessibility tree exposure).

### OQ3: Degraded states — what the user sees when the design's assumptions break

The spec describes the happy path precisely and is silent on every degraded path. Specific cases that need answers:

- **~~Anchor drift~~:** Resolved — anchors are scoped to a document version. When content changes, prior anchors are archived and not displayed (see Anchor Versioning). No drift occurs.
- **Wrong auto-activation:** If the latest session belongs to a different work context (e.g., a colleague's prior session in a shared draft), AI output goes into the wrong conversation silently. Does auto-activation signal itself as automatic?
- **No retrieval cue:** When a user returns after days and doesn't remember where in the document they were working, how do they find the right session without a list or search?

## Staged Delivery

This work is divided into three stages. Each stage leaves the app fully functional. The sequence front-loads design learning and defers irreversible UI changes.

### Stage 1: Anchor Model (backend, small)

Add the anchor concept to the data model. A topic gains an `:anchor` field that records its spawning excerpt. Persistence stores and loads the new field.

The app works identically — the anchor is stored but not yet used by any UI. The old topic list and creation flow remain.

**Learns:** Does the anchor fit cleanly into the schema and persistence layer?

### Stage 2: Margin Bar Rendering (frontend, additive)

Render colored margin bars in the document panel for topics that have an excerpt anchor. The old topic list still works — bars are a new layer alongside the existing UI, not a replacement.

Clicking a bar switches the active topic, same as clicking in the list today. Both navigation surfaces coexist.

**Learns:** Do bars position correctly against real document content? Does the visual treatment work? How does click-to-switch feel? This is the riskiest unknown — test it while the old UI is still a fallback.

### Stage 3: Navigation Overhaul (frontend, the commitment)

Remove the topic list and nav overlay. Change the excerpt selection popover to offer "Start session" and "Add excerpt." Wire up the empty state.

This is the point of no return for the old UI. By this stage the data model is proven (Stage 1) and bars are validated (Stage 2). The Topic → Session rename happens when it's natural — during this stage or after — not as a dedicated phase.

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
