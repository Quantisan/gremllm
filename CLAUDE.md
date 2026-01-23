# CLAUDE.md (Gremllm Project)

Gremllm-specific guidance for Claude Code.

## Overview

Gremllm is a cognitive workspace desktop app built with Electron and ClojureScript. It's a topic-based AI chat interface designed for organizing conversations with context inheritance. Key tech: Replicant (reactive UI), Nexus (state management), Dataspex (state inspection), Shadow-CLJS (build tool), PicoCSS (styling).

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
| State | - | `renderer.state.*` - form, messages, UI, system, topic |
| UI | - | `renderer.ui.*` - chat, settings, topics |
| Effects | `main.effects.*` - LLM, file I/O | (handled in actions) |
| Schema | `schema` - data models, validation | (shared) |

## Development

```bash
npm run dev        # Start with hot reload
npm run build      # Production build
npm run test       # Run tests
npm run repl       # ClojureScript REPL
```

## Design Philosophy

### Pragmatic Evolution: From Skateboard to Scooter
We follow the "skateboard → scooter → bicycle → motorcycle → car" MVP evolution. The **Skateboard** is complete: a functional, end-to-end chat interface. We are now building the **Scooter**, refining our core organizational model and document-centric interface. Every stage delivers a more capable, yet complete, product.

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
- **State Actions:** Keywords like `:llm.actions/response-received` are namespaced by the part of the system they affect.
- **IPC Channels:** Named for the domain action they perform, such as `chat/send-message` or `topic/save`.

By aligning our code with our mental model, we reduce cognitive load, make the system easier to navigate, and ensure that as the application grows, its complexity remains manageable. The code becomes self-documenting.

### Vision: An IDE for Thought

Gremllm is an **Idea Development Environment**—a structured workspace for complex cognitive tasks like strategic planning, analysis, and creative exploration. Unlike flat chat interfaces, Gremllm organizes thinking hierarchically with context inheritance.

**UI Design (under exploration):** We're pursuing a Document-First Interface—reimagining what a writing tool becomes when users orchestrate agentic flows rather than composing word-by-word. The document is the center of gravity; AI participates as collaborator, not just responder.

This design direction may evolve. The invariants are:
- Structured organization (not flat history)
- Context flows between related work
- User controls narrative; AI assists execution

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
  {:promise    (js/window.electronAPI.sendMessage messages)
   :on-success [[:llm.actions/response-received]
   :on-error   [[:llm.actions/response-error]}]]
```

**Conventions:**
- Domain namespacing: `form.actions/submit`, `ui.effects/save`
- Always dispatch as vectors: `[[:action-name args]]`
- Dynamic placeholders: `:event.target/value`, `:env/anthropic-api-key`

## IPC & Data

**IPC Channels:**
- `chat/send-message` - LLM API calls
- `topic/save` - Persist topic to disk
- `topic/delete` - Remove topic file from disk
- `workspace/pick-folder` - Folder picker dialog
- `workspace/reload` - Request refreshed workspace data
- `workspace:opened` - Broadcast refreshed workspace payload (`onWorkspaceOpened`)
- `system/get-info` - System capabilities
- `secrets/save`, `secrets/delete` - Secure storage
- Menu commands via `onMenuCommand`

Explicit renderer listeners like `onWorkspaceOpened` wrap domain events so the preload boundary exposes intent-driven APIs instead of raw channel strings.

**Data Storage:**
```
<userData>/User/             # System data (Electron userData)
├── secrets.edn              # Encrypted API keys

<workspace-folder>/          # User-selected folder (anywhere)
└── topics/                  # Topic files (.edn)
```

- **Workspaces:** Portable folders, like git repos - can live anywhere
- **Topics:** Individual EDN files in `topics/` subdirectory
- **Secrets:** Encrypted via Electron's safeStorage
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

## API Key Management

Gremllm supports multiple LLM providers: Anthropic (Claude), OpenAI (GPT), and Google (Gemini).

**Production (End Users):**
- Settings UI with secure storage (encrypted via Electron's safeStorage)
- Per-provider configuration - users can configure any combination of providers
- Redacted display shows which providers are configured
- Graceful degradation when encryption unavailable (session-only storage)

**Development:**
- Environment variables override secure storage: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`
- Useful for testing without configuring through UI

## Reference Documentation

Framework and library documentation is available in the `context/` directory:

**Core Language & Data:**
- `context/clojure.md` - Language fundamentals and idioms
- `context/malli.md` - Schema validation and data modeling
- `context/lookup.md` - Data access patterns and utilities

**State & UI:**
- `context/nexus.md` - State management core concepts
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
