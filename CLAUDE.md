# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Gremllm is a cognitive workspace desktop application built with Electron and ClojureScript. It's designed as a topic-based AI chat interface that allows users to organize conversations into branching topics with context inheritance. The project uses Replicant for reactive UI and Nexus for state management.

## Architecture

### Core Technologies
- **Electron**: Desktop application framework
- **ClojureScript**: Primary language for both main and renderer processes
- **Shadow-CLJS**: ClojureScript build tool and compiler
- **Replicant** (v2025.06.21): Reactive UI library (alternative to React)
- **Nexus** (v2025.07.1): State management and action system

### Process Structure
- **Main Process** (`src/gremllm/main/`): Electron main process, handles IPC, file operations, and system integration
- **Renderer Process** (`src/gremllm/renderer/`): UI and application logic running in browser context
- **Resources** (`resources/public/`): Web assets including index.html, compiled JS, and preload script
- **Context Documentation** (`context/`): Product and development documentation

### Key Namespaces

#### Main Process
- `gremllm.main.core`: Main process entry point, window management, menu setup, IPC handlers
- `gremllm.main.actions`: Central effect registrations and API key management
- `gremllm.main.actions.ipc`: IPC reply mechanisms and promise handling
- `gremllm.main.actions.secrets`: Secure storage using Electron's safeStorage API
- `gremllm.main.actions.topic`: Topic operations and data preparation
- `gremllm.main.effects.llm`: LLM provider HTTP operations (Anthropic)
- `gremllm.main.effects.topic`: Topic file I/O operations
- `gremllm.main.menu`: Electron menu configuration
- `gremllm.main.io`: File system operations
- `gremllm.main.utils`: Utility functions

#### Renderer Process
- `gremllm.renderer.core`: Renderer entry point, app initialization, menu command handling
- `gremllm.renderer.actions`: Central action/effect registrations
- `gremllm.renderer.actions.ui`: Form state, input handling, focus management, scrolling
- `gremllm.renderer.actions.messages`: Message management, LLM response handling, error display
- `gremllm.renderer.actions.topic`: Topic operations and persistence
- `gremllm.renderer.ui`: Replicant UI components (chat area, input form)
- `gremllm.renderer.state.form`: Form input state management
- `gremllm.renderer.state.loading`: Loading indicators and error states
- `gremllm.renderer.state.messages`: Message history management

## Development Commands

### Build and Development
```bash
# Start development with hot reload
npm run dev

# Build for production
npm run build

# Run tests
npm run test

# Watch tests
npm run test:watch

# Clean build artifacts
npm run clean

# Package application
npm run package

# Create distributable
npm run make
```

### REPL Development
```bash
# Start ClojureScript REPL connected to renderer
npm run repl
```

## State Management with Nexus

The application uses Nexus for state management with actions and effects:

### Action Pattern
Actions are pure functions that take state and return a vector of effects. Actions are organized by domain with namespaced naming:
```clojure
(defn update-input [state value]
  [[:ui.effects/save [:form :user-input] value]])
```

### Effect Registration
Effects are registered within their respective action namespaces and handle side effects:
```clojure
(nxr/register-effect! :ui.effects/save
  (fn [_ store path value]
    (swap! store assoc-in path value)))
```

### Promise-based Effects
New promise handling patterns for asynchronous operations:
```clojure
;; Main process: promise->reply for IPC responses
(nxr/register-effect! :promise->reply
  (fn [_ store promise channel event]
    (.then promise
      (fn [value] (send-reply! channel event (ok value)))
      (fn [error] (send-reply! channel event (err error))))))

;; Renderer: promise->actions for chaining actions
(nxr/register-effect! :promise->actions
  (fn [deps store promise success-actions failure-actions]
    (.then promise
      (fn [result] (apply nxr/dispatch! store (success-actions result)))
      (fn [error] (apply nxr/dispatch! store (failure-actions error))))))
```

### Dispatch Patterns

#### UI Event Handling (Replicant)
UI components use an `:on` map with event names as keys, dispatching action vectors:
```clojure
;; Form submission with multiple actions
{:on {:submit [[:effects/prevent-default]
               [:form.actions/submit]]}}

;; Input updates with placeholder values
{:on {:input [[:form.actions/update-input [:event.target/value]]]}}
```

#### Action Chaining
Actions can dispatch multiple effects by returning a vector:
```clojure
(defn submit-messages [state]
  (let [text (form-state/get-user-input state)]
    [[:msg.actions/add {:id (random-uuid) :type :user :text text}]
     [:form.effects/clear-input]
     [:loading.effects/set-loading? assistant-id true]
     [:effects/scroll-to-bottom "chat-messages-container"]
     [:llm.effects/send-llm-messages assistant-id]]))
```

#### Promise-based Async Dispatch
Use the generic `:effects/promise` for async operations:
```clojure
[[:effects/promise
  {:promise    (js/window.electronAPI.sendMessage messages)
   :on-success [:llm.actions/response-received assistant-id]
   :on-error   [:llm.actions/response-error assistant-id]}]]
```

#### IPC Dispatch (Main Process)
Main process handlers include metadata for proper response routing:
```clojure
(nxr/dispatch store {:ipc-event event
                     :request-id request-id
                     :channel "chat/send-message"}
              [[:chat.effects/send-message messages [:env/anthropic-api-key]]])
```

#### Key Patterns:
- All actions are namespaced by domain (e.g., `form.actions/`, `msg.actions/`)
- Effects are similarly namespaced (e.g., `form.effects/`, `loading.effects/`)
- Actions are always dispatched as vectors: `[[:action-name args]]`
- Placeholders like `:event.target/value` and `:env/anthropic-api-key` for dynamic values
- Consistent dispatch interface across main and renderer processes

## Data Persistence

- **Topic Data**: Saved as EDN files to user data directory via IPC
- **Messages**: Stored in topic state structure
- **Configuration**: Uses Electron's userData path
- **Secrets**: Encrypted using Electron's safeStorage API, stored in `userData/User/secrets.edn` (infrastructure ready but not yet used for API keys)

## IPC Communication

Communication between main and renderer processes uses a consistent promise-based pattern:
- `chat/send-message`: Send messages to LLM API with Anthropic integration
- `topic/save`: Save topic data to file system (triggered from menu or renderer)
- `secrets/*`: Secure storage operations (save, load, delete, list-keys, check-availability)
- All IPC handlers use Nexus dispatch for consistency with FCIS architecture
- Promise results are handled via `promise->reply` effect in main process

## UI Architecture with Replicant

Replicant provides reactive UI updates based on state changes:
- Components are pure functions returning hiccup-style data
- State changes trigger automatic re-renders
- Event handling through dispatch system
- Recent improvements include:
  - Input field focus management after form submission
  - Specific IDs for robust element targeting
  - Better error message display for LLM failures

## Testing

Tests are organized by namespace:
- `test/gremllm/main/`: Main process tests
- `test/gremllm/renderer/`: Renderer process tests
- Run with `npm run test` or `npm run test:watch`

## Development Philosophy

This is an MVP focused on core concepts rather than production polish. The codebase prioritizes:
- Minimal complexity while maintaining clear architecture
- Clear demonstration of key features (topic-based chat with context inheritance)
- Rapid iteration over robustness
- Functional programming patterns throughout
- Currently in "Skateboard" phase: basic working chat interface as foundation for future topic branching features

## Architectural Principles

- **Separation of Concerns**: Maintain clear boundaries between components
- **Functional Core, Imperative Shell (FCIS)**:
  - Actions are pure functions returning effect descriptions (renderer) or data transformations (main)
  - Effects handle all side effects (DOM manipulation, IPC, async operations, file I/O)
  - Main process separates actions (in `actions/` subdirs) from effects (in `effects/` subdirs)
  - Consistent use of Nexus dispatching throughout both processes
  - IPC handlers use Nexus dispatch rather than direct function calls
- **Domain-driven Organization**:
  - Actions and effects are namespaced by domain (form, messages, topic, ui)
  - State modules are focused on specific concerns
- **Promise-based Async Handling**:
  - Standardized promise effect patterns for consistent async flow
  - Clear success/failure action chaining

## Key Files for Understanding

### Configuration
- `shadow-cljs.edn`: Build configuration with three targets (main, renderer, test)
- `deps.edn`: Clojure dependencies and development setup
- `package.json`: Node dependencies and script commands

### Core Application
- `src/gremllm/main/core.cljs`: Main process entry point and window management
- `src/gremllm/renderer/core.cljs`: Renderer entry point and initialization
- `src/gremllm/renderer/ui.cljs`: All Replicant UI components

### Action System
- `src/gremllm/main/actions.cljs`: Main process effect registrations
- `src/gremllm/renderer/actions.cljs`: Renderer effect registrations
- `src/gremllm/renderer/actions/ui.cljs`: UI state management and form actions
- `src/gremllm/renderer/actions/messages.cljs`: Message handling and chat logic
- `src/gremllm/renderer/actions/topic.cljs`: Topic management and persistence

### State Management
- `src/gremllm/renderer/state/form.cljs`: Form input state
- `src/gremllm/renderer/state/loading.cljs`: Loading and error states
- `src/gremllm/renderer/state/messages.cljs`: Message history

### Documentation
- `PLAN.md`: Detailed development roadmap and current sprint goals
- `CONTEXT.md`: Product vision and design philosophy
- `README.md`: Project setup and development instructions
