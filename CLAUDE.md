# CLAUDE.md (Gremllm Project)

Gremllm-specific guidance for Claude Code.

## Overview

Gremllm is an Idea Development Environment for verified knowledge work. It helps knowledge workers produce artifacts that carry their own proof—stakeholders can see the methodology, evidence, and expert judgment that produced the deliverable. **The artifact is portable; the proof lives in the platform.**

Built with Electron and ClojureScript. The current app is document-first: the user opens any markdown file, and topics are ACP-backed conversations in service of that document. Per-document state (topics, metadata) is stored in `userData` keyed by a hash of the file's absolute path. Key tech: Replicant (reactive UI), Nexus (state management), Dataspex (state inspection), Shadow-CLJS (build tool), PicoCSS (styling), and `markdown-it` (document/chat rendering).

## Product Principles

Use these to judge whether a feature or change fits the product. They are judgment heuristics for *what* to build, not hard rules:

1. **Document-first, not chat-first** - The artifact is the center of gravity, not the conversation history
2. **Expert judgment and taste are elevated, not hidden** - Where human expertise made the difference is visible and valued
3. **You direct; the AI writes** - You shape the work by steering, accepting, rejecting, and redirecting changes. The AI does the typing.
4. **Context is managed, not dumped** - Information is progressively disclosed for each task, not pasted wholesale
5. **Simple by default, powerful when needed** - Core workflows are straightforward; advanced capabilities available when required

## Target User

**PE due diligence teams** (initial beachhead)

Why PE due diligence:
- High-stakes artifacts where methodology matters (investment memos, target assessments)
- Decades of established process: market analysis, financial review, management assessment, risk evaluation
- Expert judgment is critical—numbers don't tell the whole story
- Clear stakeholders who need to trust the work (partners, investment committee, LPs)
- Scrutiny is the norm—these documents get challenged

**Broader hypothesis:** Other domains with established expert processes (consulting, legal, policy) have similar needs. PE is the beachhead.

## Current Scope

We follow a "skateboard → scooter → bicycle → motorcycle" MVP evolution — every stage ships a complete, functional product. Use the current stage as a scope guardrail: build for where we are, not for later stages.

- **Skateboard (complete):** Topic-centered ACP chat with workspace persistence — proved we could ship a working end-to-end Electron + ACP product.
- **Scooter (current):** Document-first workflow — open any markdown file, ACP `resource_link` prompting, and inline pending-diff rendering in the document panel. Annotation capture and diff accept/reject mutation are not complete yet.
- **Bicycle (next):** Specialized agents and managed context — AI works through steerable agents for specific tasks; context is progressively disclosed per task rather than dumped wholesale.
- **Motorcycle (future):** Full proof and methodology capture — evidence, methodology, and expert judgment become visible and verifiable.

## Architecture

**Process Structure:**
- `src/gremllm/main/` - Electron main process: app lifecycle, window/menu setup, IPC registration, file and system boundaries
- `src/gremllm/renderer/` - Renderer app: Nexus state, Replicant UI, document-first workflows, preload-driven IPC consumption
- `src/gremllm/schema/` - Shared schema and codec layer for disk, IPC, DOM capture, and ACP transport boundaries
- `src/js/acp/` - In-process ACP host bridge built on paired `TransformStream`s
- `resources/public/js/preload.js` - Intent-driven Electron bridge exposed to the renderer

**Operational Map:**
- Main process owns Electron lifecycle, native integration, document and topic persistence, and ACP connection bootstrap
- Renderer owns application state, user workflows, document rendering, excerpt capture, and pending diff presentation
- Schema/codecs own shared contracts and boundary transforms
- The JS ACP host owns transport wiring; CLJS `main.effects.acp` owns lifecycle and callbacks, while `main.effects.acp.permission` owns permission resolution

**Detailed Architecture Docs:**
- `src/gremllm/main/README.md`
- `src/gremllm/renderer/README.md`
- `src/gremllm/schema/README.md`
- `src/js/acp/README.md`

**Key Entry Points:**
- `src/gremllm/main/core.cljs`
- `src/gremllm/main/actions/acp.cljs`
- `src/gremllm/main/effects/acp.cljs`
- `src/gremllm/main/actions/document.cljs`
- `src/gremllm/main/effects/document.cljs`
- `src/gremllm/renderer/core.cljs`
- `src/gremllm/renderer/actions.cljs`
- `src/gremllm/renderer/actions/document.cljs`
- `src/gremllm/renderer/ui/document.cljs`
- `src/gremllm/renderer/ui/document/diffs.cljs`
- `src/gremllm/schema.cljs`
- `src/gremllm/schema/codec.cljs`
- `src/gremllm/schema/codec/acp.cljs`
- `src/gremllm/schema/codec/acp/permission.cljs`
- `src/js/acp/index.js`
- `resources/public/js/preload.js`

## Development

```bash
npm run dev        # Start with hot reload
npm run build      # Production build
npm run package    # Package the Electron app
npm run make       # Build distributables
npm run repl       # ClojureScript REPL
npm run test       # Compile + autorun unit tests
npm run test:ci    # CI-style test run
npm run test:all   # Compile + autorun unit and integration tests
npm run test:integration # Compile + autorun integration tests
```

## Verifying Work

Before declaring a code change done:

- **Unit tests pass** — run `npm run test` and confirm green.
- **Integration tests pass** — run `npm run test:all` (or `npm run test:integration`) for changes touching IPC, ACP, or persistence boundaries.
- **Manual app check** — for renderer/UI changes, confirm the behavior in the running app (`npm run dev`), not tests alone.

## File Conventions

**Design artifacts:**
- Specs: `docs/specs/`
- Plans: `docs/plans/`

## Engineering Rules

These are hard constraints on every code change. Obey them; don't relitigate them per task.

### Strict FCIS (Functional Core, Imperative Shell)
Keep a strict separation between pure functions and side effects.

**Functional Core (pure) — all of:**
- Business logic, data transformations, and decision making
- Actions that *return* effect descriptions (never perform them)
- State derivations and computations
- UI components (pure data structures)
- Console logging is acceptable here (but in the renderer, Dataspex already logs every action and state change — prefer it over manual `console.log`)

**Imperative Shell (effects) — isolate all of:**
- DOM manipulation, IPC calls, file I/O, HTTP requests
- State mutations (only via registered effects)
- Promise handling and async operations
- Random value generation (UUIDs, etc.)

Register effects in one obvious location per process (`main/actions.cljs` and `renderer/actions.cljs`); keep the rest of the codebase pure. IPC handlers in `main/core.cljs` are part of the imperative shell.

### Modelarity: Code Reflects the Domain
Structure code — namespaces, functions, data — to mirror how we talk about the problem (credit: Kevlin Henney). If we say "saving a topic" or "handling a form submission," the code lives in a predictable place like `topic.actions/save` or `form.actions/submit`. The solution space mirrors the problem space.

**This manifests as:**
- **Namespaces:** organized by domain concepts like `document`, `topic`, or `excerpt` (e.g., `renderer.actions.document`, `main.actions.topic`).
- **State Actions:** keywords like `:topic.actions/set-active` and `:document.actions/pick`, namespaced by the part of the system they affect.
- **IPC Channels:** named for the domain action they perform, such as `acp/prompt`, `document/pick`, or `topic/save`.

### Validate at Boundaries
Every trust boundary — IPC, disk, ACP transport, DOM capture — coerces and validates incoming data through `schema/codec` before it reaches core logic. Canonical models live in `schema.cljs`; boundary transforms in `schema/codec.cljs`. Name coercions for their boundary, and fail fast at the edge, not deep in business logic.

### Test Conservatively
A hard constraint here; see `~/.claude/CLAUDE.md` "Test-First Development" for the posture and the prune-before-done self-review. This rule pins what counts as domain logic *in this codebase*:

- **Test:** pure action / state-derivation logic, and `schema/codec` boundary transforms (IPC / disk / ACP / DOM) — where misuse fails dangerously.
- **Skip:** hiccup / UI structure, action vectors that pass args through without branching, and thin effect / shell wiring — confirm those in the running app.

### Frame Before You Build
See `~/.claude/CLAUDE.md` "Problem Framing (Hammock-Driven)" for the posture. In this codebase, "surface evidence from ground truth" means:

- **The problem:** the product principle and current scope stage it serves, and the real user need — not just the feature request.
- **The data:** canonical models in `schema.cljs` and boundary shapes in `schema/codec.cljs` — read the real shape, don't assume it.
- **The boundaries:** the trust boundaries it crosses (IPC, disk, ACP, DOM) and what malformed or edge data looks like there.
- **Prior art:** existing actions / effects / codecs to mirror.

## State Management with Nexus

Following FCIS principles, all state changes flow through Nexus:

```clojure
;; Actions describe what should happen (pure data, no side effects)
(defn pick [_state]
  [[:effects/promise
    {:promise (.pickDocument js/window.electronAPI)}]])

;; UI dispatches action vectors
{:on {:submit [[:effects/prevent-default]
               [:form.actions/submit]]}}

;; Async operations via promises
[[:effects/promise
  {:promise    (.acpNewSession js/window.electronAPI)
   :on-success [[:acp.actions/session-ready topic-id]]
   :on-error   [[:acp.actions/session-error topic-id]]}]]
```

**Conventions:**
- Domain namespacing: `document.actions/pick`, `topic.actions/set-active`, `document.actions/opened`
- Always dispatch as vectors: `[[:action-name args]]`
- Registered placeholders currently include `:event.target/value`, `:event/key-pressed`, `:event/dropped-files`, `:event/text-selection`, `:dom/element-by-id`, and `:dom.element/property`

## IPC & Data

IPC channels and document storage layout are documented in `src/gremllm/main/README.md`.

Cross-cutting facts:
- **Documents** are standalone markdown files — can live anywhere on the filesystem
- **Topics** persist `[:session :id]` and `[:session :pending-diffs]` alongside local message history
- **Canonical data models** live in `src/gremllm/schema.cljs`; boundary coercions and transport shapes live in `src/gremllm/schema/codec.cljs`

## UI Approach
- **PicoCSS + split palette** - Semantic HTML with PicoCSS defaults. A TVA/Brutalist palette defines light zones (document panel) and dark zones (nav, chat). Element aliases in `elements.cljs` handle zone scoping automatically — don't set `data-theme` manually.
- **No hardcoded colors** - Use `var(--pico-*)` properties or the six `var(--tva-*)` tokens defined in `index.html`. Never use hex/rgb literals in components.
- **Minimal custom CSS** - Custom styles live in `index.html`. Add new classes there only when PicoCSS has no semantic equivalent.
- **Diff rendering is source-driven** - Pending tracked changes are anchored against raw markdown source in `renderer.ui.document.diffs`, not the rendered DOM.

## Reference Documentation

Framework and library documentation is available in the `context/` directory:

**Core Language & Data:**
- `context/clojure.md` - Language fundamentals and idioms
- `context/malli.md` - Schema validation and data modeling
- `context/lookup.md` - Data access patterns and utilities

**State & UI:**
- `context/nexus.md` - State management core concepts
- `context/nexus_addendum.md` - Nexus gotchas and edge cases (state capture timing, etc.)
- `context/nexus_placeholder_resolve.md` - Dynamic placeholder resolution
- `/Users/paul/Projects/nexus` - Full local Nexus source repository; use sparingly to conserve tokens
- `context/replicant_guide.md` - UI framework overview and patterns
- `context/replicant_concept.md` - Conceptual model and architecture
- `context/replicant_hiccup.md` - Hiccup syntax and component structure
- `context/replicant_lifecycle.md` - Lifecycle hooks and mount/update behavior

**Debugging:**
- `context/dataspex.md` - State inspection and action logging
- Dataspex automatically logs every Nexus action and state change in the Renderer process
- Diagnostic console.log statements are unnecessary in Renderer code—use Dataspex instead
- Note: Dataspex only works in browser context (Renderer), not in Node (Main process)

**Electron Integration:**
- Canonical source: https://github.com/electron/electron/tree/main/docs — any page is `https://github.com/electron/electron/blob/main/docs/<path>`; for raw Markdown use `https://raw.githubusercontent.com/electron/electron/main/docs/<path>`
- Curated pages relevant to this codebase:
  - `tutorial/process-model.md` - Main vs. renderer split (maps to our FCIS shell boundary)
  - `tutorial/ipc.md` - IPC patterns (request/response, one-way, renderer→main)
  - `api/browser-window.md` - Window lifecycle and options
  - `api/ipc-main.md` - Main-side IPC handlers
  - `api/ipc-renderer.md` - Renderer-side IPC senders
  - `api/context-bridge.md` - Preload-to-renderer bridge (used in `resources/public/js/preload.js`)
  - `api/dialog.md` - Native file/open/save dialogs
  - `api/menu.md` - Application menu
- `context/pico_modal.md` - Modal/dialog styling details for PicoCSS

**Agent Protocols:**
- ACP docs website agent navigation:
  - `https://agentclientprotocol.com/llms.txt` - Agent-readable documentation index
  - `https://agentclientprotocol.com/llms-full.txt` - Combined agent-readable documentation
  - Individual docs pages have Markdown equivalents by appending `.md` to the route, e.g. `https://agentclientprotocol.com/protocol/tool-calls.md` for `https://agentclientprotocol.com/protocol/tool-calls`
