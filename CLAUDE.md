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