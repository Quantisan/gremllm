# CLAUDE.md

Guidance for Claude Code when working with the Gremllm codebase.

## Overview

Gremllm is a cognitive workspace desktop app built with Electron and ClojureScript. It's a topic-based AI chat interface designed for organizing conversations with context inheritance. Key tech: Replicant (reactive UI), Nexus (state management), Shadow-CLJS (build tool).

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

## LLM Interaction Guidelines

Follow these directives. They are rules, not suggestions.

### 1. Be Direct
- **No Pleasantries:** Omit praise like "good" or "great."
- **Answer First:** Deliver the solution before the explanation.

### 2. Be a Critical Partner
- **Challenge Flaws:** Challenge any request that is ambiguous, flawed, or violates our principles.
- **Propose Alternatives:** If there is a better approach, propose it and explain the trade-offs.
- **Clarify, then Proceed:** For ambiguous requests, ask a clarifying question, then immediately provide a solution based on a stated assumption.

### 3. Master Our Principles
- **Strict Adherence:** All solutions must align with this document's principles (FCIS, Modelarity).
- **Justify with Principles:** Cite our principles to justify your logic. For example:
    - *"To maintain **Strict FCIS**, this effect should be..."*
    - *"Per our **File Management Philosophy**, this logic belongs in..."*

## Design Philosophy

### Pragmatic Evolution: From Skateboard to Scooter
We follow the "skateboard → scooter → bicycle → motorcycle → car" MVP evolution. The **Skateboard** is complete: a functional, end-to-end chat interface. We are now building the **Scooter**, which introduces our core domain concept of organizing conversations into **topics**. Every stage delivers a more capable, yet complete, product.

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

### Minimal Complexity, Maximum Clarity
We resist adding abstractions until they prove their worth. Every line of code should have a clear purpose. We prefer explicit over clever, simple over sophisticated. The codebase should be approachable for someone familiar with Clojure basics.

### Vision: An IDE for Thought

Gremllm is not a chat app; it is an **Idea Development Environment (IDE)**—a structured workspace for complex cognitive tasks like strategic planning, analysis, and creative exploration. Our entire architecture is modeled directly on the familiar and powerful metaphor of a code repository.

-   **Workspace as Repository:** A Workspace is the root of a project, equivalent to a git repository's top-level folder. It contains all related thinking on a single macro-subject. Switching workspaces is like switching project directories.

-   **Topic as Source File:** A Topic is an individual file within that repository. It is a container for a specific line of inquiry, a single analysis, or a focused creative thread. It inherits context from its parent but allows for the isolated "development" of an idea.

This structure creates a **topic tree** where context flows from the workspace down through parent topics to children. This hierarchy supports both broad, high-level context and deep, specific exploration without cross-contamination. Every feature we build must serve this model of structured, hierarchical, and context-aware thinking.

## Development Approach

### Test-First Development
1. **Write tests first** - Define the behavior before implementation
2. **Build to pass tests** - Implement the minimal code to make tests green
3. **Validate it works** - Manual testing in `npm run dev` to ensure real-world behavior
4. **Refactor excessively** - Once working, refactor until the code is clean and obvious
5. **Apply Boy Scout Rule** - Leave code better than you found it, every time

### Continuous Architecture Evolution
- **Weekly refactoring sessions** - Dedicated time for larger architectural improvements
- **Early attention to code quality** - Don't let technical debt accumulate
- **Least-surprise principle** - Code should do what it looks like it does
- **Conscious architecture** - Every decision should improve long-term maintainability

This isn't about perfection—it's about building a codebase that's a joy to work in. Clean code is faster to understand, easier to modify, and less likely to harbor bugs.

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
- `topic/save`, `topic/list`, `topic/load-latest` - Topic operations
- `workspace/load-folder` - Load all topics
- `secrets/*` - Secure storage ops
- `system/get-info` - System capabilities
- `menu:command` - Menu commands

**Data Storage:**
```
<userData>/
└── User/
    ├── secrets.edn
    └── workspaces/
        └── <workspace-id>/
            └── topics/
                └── topic-<topic-id>.edn
```
- Topics: EDN files validated by schema
- Secrets: Encrypted via Electron's safeStorage

## Entry Points

- `src/gremllm/main/core.cljs` - Main process start
- `src/gremllm/renderer/core.cljs` - Renderer start
- `src/gremllm/renderer/ui.cljs` - Main UI components
- `src/gremllm/*/actions.cljs` - Action/effect registrations
- `src/gremllm/schema.cljs` - Data models and validation

## Code Style & Conventions

### File Management Philosophy
- **ALWAYS prefer editing existing files over creating new ones**
- New files only when introducing a new domain or feature area
- Before creating a file, ask: "Can this logic live in an existing namespace?"
- File creation is a design decision, not a convenience

### Clojure Style
- Threading macros (`->`, `->>`) for clarity in transformation pipelines
- Destructuring to make data shapes explicit at function boundaries
- Small, focused functions that do one thing well
- Descriptive names that make code self-documenting
- Let bindings for intermediate values that clarify intent

### Working with the Codebase
1. Study existing patterns before implementing
2. Check dependencies (`deps.edn`) before assuming libraries exist
3. Follow established namespace conventions
4. Run tests (`npm run test`) throughout development
5. Manual testing with `npm run dev` before considering complete

## API Key Management

- Primary: `ANTHROPIC_API_KEY` environment variable
- Secondary: Secure storage via settings UI (when encryption available)
- Visual: Redacted display in UI
- Fallback: Graceful degradation without encryption
