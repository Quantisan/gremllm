# CLAUDE.md (Gremllm Project)

Gremllm-specific guidance for Claude Code.

## Overview

Gremllm is an Idea Development Environment for verified knowledge work. It helps knowledge workers produce artifacts that carry their own proof—stakeholders can see the methodology, evidence, and expert judgment that produced the deliverable. **The artifact is portable; the proof lives in the platform.**

Built with Electron and ClojureScript. All user-AI interactions flow through Agent Client Protocol (ACP). Key tech: Replicant (reactive UI), Nexus (state management), Dataspex (state inspection), Shadow-CLJS (build tool), PicoCSS (styling).

## Design Principles

These principles guide every implementation decision:

1. **Document-first, not chat-first** - The artifact is the center of gravity, not the conversation history
2. **Expert judgment and taste are elevated, not hidden** - Where human expertise made the difference is visible and valued
3. **AI assists through specialized agents you can steer** - Not one generic assistant, but specialized agents for specific tasks
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
- `src/gremllm/main/` - Electron main process (IPC, file ops, system integration)
- `src/gremllm/renderer/` - UI and app logic (browser context)
- `resources/public/` - Web assets (index.html, compiled JS, preload script)

**Key Namespaces Quick Reference:**

| Domain | Main Process | Renderer Process |
|--------|--------------|------------------|
| Core | `main.core` - entry, window mgmt | `renderer.core` - entry, bootstrap |
| Actions | `main.actions.*` - effects, IPC | `renderer.actions.*` - UI, messages, settings, topic, workspace |
| State | - | `renderer.state.*` - ACP, form, messages, UI, system, topic |
| UI | - | `renderer.ui.*` - chat, settings, topics |
| Effects | `main.effects.*` - ACP, file I/O | (handled in actions) |
| Schema | `schema` - data models, validation | (shared) |

**ACP Integration (current implementation):**
- One ACP session per topic; the topic stores the `acp-session-id`
- Session resume uses ACP resume plus locally persisted topic messages (hybrid history)
- Streaming session updates flow main → renderer via IPC events
- **Note:** Current implementation uses a single generic ACP session per topic. Product direction: specialized agents for different tasks (research, analysis, synthesis, etc.). This is a known gap to be addressed.

## Development

```bash
npm run dev        # Start with hot reload
npm run build      # Production build
npm run test       # Run tests
npm run repl       # ClojureScript REPL
```

## Design Philosophy

### Pragmatic Evolution: From Skateboard to Motorcycle
We follow the "skateboard → scooter → bicycle → motorcycle" MVP evolution. Every stage delivers a complete, functional product:

- **Skateboard (complete):** Functional end-to-end AI interaction—proved we could ship a working product with ACP integration and topic-based organization.
- **Scooter (current):** Document-first interface with basic proof capture—the artifact becomes the center of gravity; minimal raw data (e.g., user annotations) captured alongside the work.
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

Effects are registered in a single, obvious location per process (`main/actions.cljs` and `renderer/actions.cljs`). The rest of the codebase remains pure. This isn't just a preference—it's a strict architectural requirement.

### Modelarity: Code Reflects the Domain
We practice a form of domain-driven design where the structure of our code—our namespaces, functions, and data—mirrors the way we think and talk about the problem (credit: Kevlin Henney). If we discuss "saving a topic" or "handling a form submission," the corresponding code should be found in a predictable location like `topic.actions/save` or `form.actions/submit`.

This principle ensures that the solution space (the code) directly corresponds to the problem space (the domain concepts).

**How this manifests:**
- **Namespaces:** Organized by domain concepts like `messages`, `topics`, or `ui` (e.g., `renderer.actions.topics`).
- **State Actions:** Keywords like `:topic.actions/set-active` are namespaced by the part of the system they affect.
- **IPC Channels:** Named for the domain action they perform, such as `acp/prompt` or `topic/save`.

By aligning our code with our mental model, we reduce cognitive load, make the system easier to navigate, and ensure that as the application grows, its complexity remains manageable. The code becomes self-documenting.

### Vision: An Idea Development Environment for Verified Knowledge Work

Gremllm is an **Idea Development Environment**—a structured workspace where knowledge workers produce artifacts that carry their own proof. The six pillars:

1. **The artifact is the center of gravity** - Document-first, not chat-first. The deliverable is what matters.
2. **Expert judgment is elevated** - Taste, experience, and intuition are first-class, not hidden. Where the human made the difference is visible and valued.
3. **AI works through specialized agents** - Not one generic assistant, but steerable agents for specific tasks (research, analysis, synthesis, verification).
4. **Context is managed, not dumped** - Information is progressively disclosed for each task, not pasted wholesale into a prompt.
5. **Process is captured as you work** - Evidence, methodology, and reasoning are captured alongside the artifact itself.
6. **Deliverables carry proof** - Stakeholders can verify the work by examining the methodology, evidence, and expert judgment that produced it.

**The core insight:** The artifact is portable (exported, shared, presented). The proof lives in the platform (methodology, evidence, reasoning, expert judgment).

## State Management with Nexus

Following FCIS principles, all state changes flow through Nexus:

```clojure
;; Actions describe what should happen
(defn update-input [state value]
  [[:ui.effects/save [:form :user-input] value]])

;; UI dispatches action vectors
{:on {:submit [[:effects/prevent-default]
               [:form.actions/submit]]}}

;; Async operations via promises
[[:effects/promise
  {:promise    (.acpNewSession js/window.electronAPI)
    :on-success [[:acp.actions/session-ready topic-id]]
    :on-error   [[:acp.actions/session-error]]}]]
```

**Conventions:**
- Domain namespacing: `form.actions/submit`, `ui.effects/save`
- Always dispatch as vectors: `[[:action-name args]]`
- Dynamic placeholders: `:event.target/value`, `:event.target/checked`

## IPC & Data

**IPC Channels:**
- `acp/new-session` - Start a new ACP session
- `acp/prompt` - Send a user prompt to ACP
- `acp/resume-session` - Resume a topic-bound ACP session
- `acp:session-update` - Stream ACP session updates to renderer
- `topic/save` - Persist topic to disk
- `topic/delete` - Remove topic file from disk
- `workspace/pick-folder` - Folder picker dialog
- `workspace/reload` - Request refreshed workspace data
- `workspace:opened` - Broadcast refreshed workspace payload (`onWorkspaceOpened`)
- `system/get-info` - System capabilities
- Menu commands via `onMenuCommand`

Explicit renderer listeners like `onWorkspaceOpened` and `onAcpSessionUpdate` wrap domain events so the preload boundary exposes intent-driven APIs instead of raw channel strings.

**Data Storage:**
```
<userData>/User/             # System data (Electron userData)

<workspace-folder>/          # User-selected folder (anywhere)
└── topics/                  # Topic files (.edn)
```

- **Workspaces:** Portable folders, like git repos - can live anywhere
- **Topics:** Individual EDN files in `topics/` subdirectory (includes `acp-session-id` and local message history)
- **Schemas:** See `schema.cljs` for data structures
- **File I/O:** See `main/io.cljs` for paths and operations

## Entry Points

- `src/gremllm/main/core.cljs` - Main process start
- `src/gremllm/renderer/core.cljs` - Renderer start
- `src/gremllm/renderer/ui.cljs` - Main UI components
- `src/gremllm/*/actions.cljs` - Action/effect registrations
- `src/gremllm/schema.cljs` - Data models and validation

## UI Approach
- **PicoCSS only** - Use semantic HTML and PicoCSS defaults. No custom styling unless essential for functionality (e.g., chat bubbles)
- **No polish** - MVP means functional, not fancy. Resist the urge to beautify
- **Minimal custom CSS** - The few custom styles in index.html are enough. Don't add more

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
- `context/replicant_guide.md` - UI framework overview and patterns
- `context/replicant_concept.md` - Conceptual model and architecture
- `context/replicant_hiccup.md` - Hiccup syntax and component structure

**Debugging:**
- `context/dataspex.md` - State inspection and action logging
- Dataspex automatically logs every Nexus action and state change in the Renderer process
- Diagnostic console.log statements are unnecessary in Renderer code—use Dataspex instead
- Note: Dataspex only works in browser context (Renderer), not in Node (Main process)

**Electron Integration:**
- `context/electron_ipc.md` - Inter-process communication
- `context/electron_dialog.md` - Native dialogs and file pickers
- `context/electron_safestorage.md` - Secure credential storage
- `context/electron_menu.md` - Application menu system

**Agent Protocols:**
- `context/acp_client.md` - Agent Client Protocol client library documentation
