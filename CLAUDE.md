# CLAUDE.md (Gremllm Project)

Gremllm-specific guidance for Claude Code.

## Overview

Gremllm is an Idea Development Environment for verified knowledge work. It helps knowledge workers produce artifacts that carry their own proof—stakeholders can see the methodology, evidence, and expert judgment that produced the deliverable. **The artifact is portable; the proof lives in the platform.**

Built with Electron and ClojureScript. The current app is document-first: each workspace centers on a `document.md`, and topics are ACP-backed conversations in service of that document. Key tech: Replicant (reactive UI), Nexus (state management), Dataspex (state inspection), Shadow-CLJS (build tool), PicoCSS (styling), and `markdown-it` (document/chat rendering).

## Design Principles

These principles guide every implementation decision:

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

## Architecture

**Process Structure:**
- `src/gremllm/main/` - Electron main process: app lifecycle, window/menu setup, IPC registration, file and system boundaries
- `src/gremllm/renderer/` - Renderer app: Nexus state, Replicant UI, document-first workflows, preload-driven IPC consumption
- `src/gremllm/schema/` - Shared schema and codec layer for disk, IPC, DOM capture, and ACP transport boundaries
- `src/js/acp/` - In-process ACP host bridge built on paired `TransformStream`s
- `resources/public/js/preload.js` - Intent-driven Electron bridge exposed to the renderer

**Operational Map:**
- Main process owns Electron lifecycle, native integration, workspace and topic persistence, secrets, and ACP connection bootstrap
- Renderer owns application state, user workflows, document rendering, excerpt capture, and pending diff presentation
- Schema/codecs own shared contracts and boundary transforms
- The JS ACP host owns transport wiring; CLJS `main.effects.acp` owns lifecycle, callbacks, and permission resolution

**Detailed Architecture Docs:**
- `src/gremllm/main/README.md`
- `src/gremllm/renderer/README.md`
- `src/gremllm/schema/README.md`
- `src/js/acp/README.md`

**Key Entry Points:**
- `src/gremllm/main/core.cljs`
- `src/gremllm/main/actions/acp.cljs`
- `src/gremllm/main/effects/acp.cljs`
- `src/gremllm/main/effects/workspace.cljs`
- `src/gremllm/renderer/core.cljs`
- `src/gremllm/renderer/actions.cljs`
- `src/gremllm/renderer/ui/document.cljs`
- `src/gremllm/renderer/ui/document/diffs.cljs`
- `src/gremllm/schema.cljs`
- `src/gremllm/schema/codec.cljs`
- `src/gremllm/schema/codec/acp_permission.cljs`
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

## File Conventions

**Design artifacts:**
- Specs: `docs/specs/`
- Plans: `docs/plans/`

## Design Philosophy

### Pragmatic Evolution: From Skateboard to Motorcycle
We follow the "skateboard → scooter → bicycle → motorcycle" MVP evolution. Every stage delivers a complete, functional product:

- **Skateboard (complete):** Topic-centered ACP chat with workspace persistence—proved we could ship a working end-to-end Electron + ACP product.
- **Scooter (current):** Document-first workspace with `document.md`, ACP `resource_link` prompting, and inline pending-diff rendering in the document panel. Annotation capture and diff accept/reject mutation are not complete yet.
- **Bicycle (next):** Specialized agents and managed context—AI works through steerable agents for specific tasks; context is progressively disclosed per task rather than dumped wholesale.
- **Motorcycle (future):** Full proof and methodology capture—evidence, methodology, and expert judgment become visible and verifiable.

### Strict FCIS (Functional Core, Imperative Shell)
We maintain a strict separation between pure functions and side effects:

**Functional Core (Pure):**
- All business logic, data transformations, and decision making
- Actions that return effect descriptions (not perform them)
- State derivations and computations
- UI components (pure data structures)
- Console logging for debugging is acceptable

**Imperative Shell (Effects):**
- ALL side effects are isolated in effect handlers
- DOM manipulation, IPC calls, file I/O, HTTP requests
- State mutations (only via registered effects)
- Promise handling and async operations
- Random value generation (UUIDs, etc.)

Effects are registered in a single, obvious location per process (`main/actions.cljs` and `renderer/actions.cljs`). The rest of the codebase remains pure. This isn't just a preference—it's a strict architectural requirement. IPC handlers in `main/core.cljs` are also part of the imperative shell.

### Modelarity: Code Reflects the Domain
We practice a form of domain-driven design where the structure of our code—our namespaces, functions, and data—mirrors the way we think and talk about the problem (credit: Kevlin Henney). If we discuss "saving a topic" or "handling a form submission," the corresponding code should be found in a predictable location like `topic.actions/save` or `form.actions/submit`.

This principle ensures that the solution space (the code) directly corresponds to the problem space (the domain concepts).

**How this manifests:**
- **Namespaces:** Organized by domain concepts like `document`, `topic`, `workspace`, or `settings` (e.g., `renderer.actions.document`, `main.actions.secrets`).
- **State Actions:** Keywords like `:topic.actions/set-active`, `:document.actions/create`, and `:settings.actions/save-key` are namespaced by the part of the system they affect.
- **IPC Channels:** Named for the domain action they perform, such as `acp/prompt`, `document/create`, or `topic/save`.

By aligning our code with our mental model, we reduce cognitive load, make the system easier to navigate, and ensure that as the application grows, its complexity remains manageable. The code becomes self-documenting.

### Vision: An Idea Development Environment for Verified Knowledge Work

Gremllm is an **Idea Development Environment**—a structured workspace where knowledge workers produce artifacts that carry their own proof. The principles above define the working model: the document stays central, expert judgment stays visible, and humans direct the work while AI proposes the writing. As work progresses, Gremllm captures process, evidence, and judgment alongside the artifact so stakeholders can examine not just the deliverable, but how it was produced. **The artifact is portable; the proof lives in the platform.**

## State Management with Nexus

Following FCIS principles, all state changes flow through Nexus:

```clojure
;; Actions describe what should happen
(defn create [_state]
  [[:effects/promise
    {:promise    (.createDocument js/window.electronAPI)
     :on-success [[:document.actions/create-success]]
     :on-error   [[:document.actions/create-error]]}]])

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
- Domain namespacing: `document.actions/create`, `topic.actions/set-active`, `settings.actions/save-key`
- Always dispatch as vectors: `[[:action-name args]]`
- Registered placeholders currently include `:event.target/value`, `:event/key-pressed`, `:event/dropped-files`, `:dom/element-by-id`, and `:dom.element/property`

## IPC & Data

**IPC Channels:**
- `topic/save` - Persist a topic EDN file
- `topic/delete` - Delete a topic file after confirmation
- `document/create` - Create `<workspace>/document.md`
- `secrets/save` - Encrypt and store a secret in the user data secrets file
- `secrets/delete` - Delete a stored secret
- `acp/new-session` - Start a new ACP session
- `acp/prompt` - Send a user prompt to ACP
- `acp/resume-session` - Resume a topic-bound ACP session
- `acp:session-update` - Stream ACP session updates to renderer
- `workspace/pick-folder` - Folder picker dialog
- `workspace/reload` - Request refreshed workspace data
- `workspace:opened` - Broadcast refreshed workspace payload (`onWorkspaceOpened`)
- `system/get-info` - System capabilities
- `menu:command` - Broadcast app menu commands into the renderer (`onMenuCommand`)

`preload.js` exposes promise-style wrappers for ACP commands and intent-driven listeners like `onWorkspaceOpened`, `onAcpSessionUpdate`, and `onMenuCommand`, so the renderer does not manipulate raw IPC strings directly outside the preload boundary.

**Data Storage:**
```
<userData>/User/
└── secrets.edn              # Encrypted API keys via Electron safeStorage

<workspace-folder>/          # User-selected folder (anywhere)
├── document.md              # Primary workspace document (created on demand)
└── topics/
    └── *.edn                # Topic/session files
```

- **Workspaces:** Portable folders, like git repos - can live anywhere
- **Document:** `document.md` is the primary artifact in the current scooter implementation
- **Topics:** Individual EDN files in `topics/` subdirectory (includes `[:session :id]`, `[:session :pending-diffs]`, and local message history)
- **Schemas:** See `schema.cljs` for data structures; transport/IPC codecs live in `schema/codec.cljs`
- **File I/O:** See `main/io.cljs` for paths and operations

## Entry Points

When exploring unfamiliar code, start here and in the Key Namespaces table before running broad searches.

- `src/gremllm/main/core.cljs` - Main process start
- `src/gremllm/main/actions/acp.cljs` - ACP prompt block construction from structured user messages and staged excerpts
- `src/gremllm/main/effects/acp.cljs` - ACP connection lifecycle, permission resolution wiring, and native file callbacks
- `src/gremllm/renderer/core.cljs` - Renderer start
- `src/gremllm/renderer/ui.cljs` - Main UI components
- `src/gremllm/renderer/ui/document.cljs` - Document panel rendering
- `src/gremllm/renderer/ui/document/diffs.cljs` - Pending diff anchoring and composition
- `src/gremllm/*/actions.cljs` - Action/effect registrations
- `src/gremllm/schema.cljs` - Data models and validation
- `src/gremllm/schema/codec.cljs` - IPC/JS/ACP codecs and adapters
- `src/gremllm/schema/codec/acp_permission.cljs` - Pure ACP permission policy
- `src/js/acp/index.js` - In-process ACP bridge built on paired `TransformStream`s
- `resources/public/js/preload.js` - Intent-driven Electron bridge exposed to the renderer
- `test/gremllm/schema_test.cljs` - Schema validation tests
- `test/gremllm/renderer/actions/` - Renderer action tests (excerpt, message, etc.)

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
- `context/electron_browser_window.md` - BrowserWindow behavior and lifecycle
- `context/electron_ipc.md` - Inter-process communication
- `context/electron_dialog.md` - Native dialogs and file pickers
- `context/electron_safestorage.md` - Secure credential storage
- `context/electron_menu.md` - Application menu system
- `context/pico_modal.md` - Modal/dialog styling details for PicoCSS

**Agent Protocols:**
- `context/acp_client.md` - Agent Client Protocol client library documentation
