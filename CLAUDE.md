# CLAUDE.md

Guidance for Claude Code when working with the Gremllm codebase.

## Overview

Gremllm is a cognitive workspace desktop app built with Electron and ClojureScript. It's a topic-based AI chat interface designed for organizing conversations with context inheritance. Key tech: Replicant (reactive UI), Nexus (state management), Shadow-CLJS (build tool).

## Design Philosophy

### MVP Approach: The Skateboard
We follow the "skateboard → scooter → bicycle → motorcycle → car" MVP evolution. Currently in skateboard phase: a basic but fully functional chat interface that works end-to-end. This foundation will evolve into topic branching and context inheritance features. The goal is to have something usable at every stage, not building a car one component at a time.

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

### Minimal Complexity, Maximum Clarity
We resist adding abstractions until they prove their worth. Every line of code should have a clear purpose. We prefer explicit over clever, simple over sophisticated. The codebase should be approachable for someone familiar with Clojure basics.

### Topic-Centric Vision
While currently a linear chat, the architecture anticipates branching conversations. Topics will form a tree where context flows down branches. Each conversation branch inherits context from its parent, enabling focused exploration without losing the broader discussion context.

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

## Architecture

**Process Structure:**
- `src/gremllm/main/` - Electron main process (IPC, file ops, system integration)
- `src/gremllm/renderer/` - UI and app logic (browser context)
- `resources/public/` - Web assets (index.html, compiled JS, preload script)

**Key Namespaces Quick Reference:**

| Domain | Main Process | Renderer Process |
|--------|--------------|------------------|
| Core | `main.core` - entry, window mgmt | `renderer.core` - entry, bootstrap |
| Actions | `main.actions.*` - effects, IPC | `renderer.actions.*` - UI, messages, settings |
| State | - | `renderer.state.*` - form, messages, UI, system |
| UI | - | `renderer.ui`, `renderer.ui.settings` |
| Effects | `main.effects.*` - LLM, file I/O | (handled in actions) |

## Development

```bash
npm run dev        # Start with hot reload
npm run build      # Production build
npm run test       # Run tests
npm run repl       # ClojureScript REPL
```

## State Management (Nexus)

**Core Pattern:** Actions return effect vectors, effects handle side effects.

```clojure
;; Action (pure function)
(defn update-input [state value]
  [[:ui.effects/save [:form :user-input] value]])

;; UI event dispatch
{:on {:submit [[:effects/prevent-default]
               [:form.actions/submit]]}}

;; Async operations
[[:effects/promise
  {:promise    (js/window.electronAPI.sendMessage messages)
   :on-success [:llm.actions/response-received]
   :on-error   [:llm.actions/response-error]}]]
```

**Key Patterns:**
- Namespaced actions/effects by domain (`form.actions/`, `ui.effects/`)
- Dispatch as vectors: `[[:action-name args]]`
- Placeholders: `:event.target/value`, `:env/anthropic-api-key`
- `:env/anthropic-api-key` checks env vars first, then saved secrets

## IPC & Data

**IPC Channels:**
- `chat/send-message` - LLM API calls
- `topic/save` - Topic persistence
- `secrets/*` - Secure storage ops
- `system/get-info` - System capabilities
- `menu:settings` - Settings modal

**Data Storage:**
- Topics: EDN files in userData directory
- Secrets: Encrypted via Electron's safeStorage
- API Keys: Environment var or secure storage

## Entry Points

- `src/gremllm/main/core.cljs` - Main process start
- `src/gremllm/renderer/core.cljs` - Renderer start
- `src/gremllm/renderer/ui.cljs` - Main UI components
- `src/gremllm/*/actions.cljs` - Action/effect registrations

## Key Principles

- **FCIS Architecture**: Actions (pure) → Effects (side effects)
- **Domain Organization**: Namespaced by feature (form, messages, topic)
- **Promise-based Async**: Consistent async patterns
- **MVP Focus**: Skateboard phase - working chat as foundation

## API Key Management

- Primary: `ANTHROPIC_API_KEY` environment variable
- Secondary: Secure storage via settings UI (when encryption available)
- Visual: Redacted display in UI
- Fallback: Graceful degradation without encryption