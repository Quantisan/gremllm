# ACP Slice 2 Completion - Chat UI Integration

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Route user input through ACP and display streaming responses in the chat UI with live accumulation.

**Architecture:** Form submit triggers ACP prompt instead of LLM effect. Chunks stream into flat state, UI renders growing assistant message. Loading indicator shows between submit and first chunk. Hardcoded single session, deferred topic integration.

**Tech Stack:** ClojureScript, Nexus (state), Replicant (UI), Electron IPC

---

## Design Context

This plan completes Slice 2 (Phase 3.3) from `docs/acp-poc-learnings.md`. The following design decisions were made during brainstorming:

### Scope Decisions

1. **Chat integration over minimal display** - Wire ACP responses into the existing chat UI alongside regular LLM messages, not just a debug panel.

2. **ACP replaces LLM flow** - User input goes through ACP instead of the current LLM effect. This makes ACP the new message backend.

3. **Defer session management** - Hardcode a single test session for now. Topic-based sessions (one ACP session per topic) comes in Slice 3.

4. **Only handle `:agent-message-chunk`** - Ignore `:agent-thought-chunk` and `:available-commands-update` events for now.

### Streaming UX Decisions

1. **Accumulating text display** - Show a single assistant message that grows as chunks arrive (like ChatGPT's streaming effect).

2. **Loading indicator hides on first chunk** - No explicit "done" tracking. Loading shows between submit and first chunk arriving. If chunks stop, do nothing. New user input implicitly means the previous response is complete.

3. **Defer state schema refactoring** - Use a simple flat structure now. Once we see it working, we can refactor the app state schema with concrete understanding.

### Data Flow

```
User types message → Form submit action
    ↓
ACP prompt effect (hardcoded session ID)
    ↓
Show loading indicator
    ↓
Chunks stream in via IPC → Accumulate in state
    ↓
First chunk hides loading, shows growing assistant message
    ↓
User types next message → Previous response implicitly complete
```

---

## State Shape

```clojure
{:acp {:session-id  "..."     ;; from acpNewSession
       :chunks      []        ;; accumulated text strings
       :loading?    false}}   ;; true between submit and first chunk
```

---

### Task 1: Add ACP State Accessors

**Files:**
- Create: `src/gremllm/renderer/state/acp.cljs`

**Step 1: Create state accessor module**

```clojure
(ns gremllm.renderer.state.acp)

(def acp-path [:acp])

(def session-id-path (conj acp-path :session-id))
(def chunks-path (conj acp-path :chunks))
(def loading-path (conj acp-path :loading?))

(defn get-session-id [state]
  (get-in state session-id-path))

(defn get-chunks [state]
  (get-in state chunks-path []))

(defn loading? [state]
  (get-in state loading-path false))
```

**Step 2: Verify file created**

Run: `cat src/gremllm/renderer/state/acp.cljs`

**Step 3: Commit**

```bash
git add src/gremllm/renderer/state/acp.cljs
git commit -m "feat(acp): add renderer state accessors for ACP"
```

---

### Task 2: Update Chunk Handler to Use Flat State

**Files:**
- Modify: `src/gremllm/renderer/actions.cljs:184-192`

**Step 1: Update the session-update action**

Replace the existing handler (lines 184-192) with:

```clojure
(nxr/register-action! :acp.events/session-update
  (fn [state {:keys [update]}]
    (if (= (:session-update update) :agent-message-chunk)
      (let [chunks (get-in state [:acp :chunks] [])
            text (get-in update [:content :text])]
        [[:effects/save [:acp :chunks] (conj chunks text)]
         [:effects/save [:acp :loading?] false]])
      [])))
```

Key changes:
- Removed `session-id` from destructuring (not using per-session state)
- Extract `:text` from content (was storing full content map)
- Set `:loading?` false on first chunk
- Removed console.log

**Step 2: Verify change compiles**

Run: `npm run dev` (watch mode should recompile)

**Step 3: Commit**

```bash
git add src/gremllm/renderer/actions.cljs
git commit -m "feat(acp): simplify chunk handler to flat state structure"
```

---

### Task 3: Initialize ACP Session on Workspace Load

**Files:**
- Modify: `src/gremllm/renderer/actions/workspace.cljs`

**Step 1: Add require for state accessor**

Add to ns requires:

```clojure
[gremllm.renderer.state.acp :as acp-state]
```

**Step 2: Add ACP init to mark-loaded**

Replace `mark-loaded` function:

```clojure
(defn mark-loaded
  "Mark the workspace as successfully loaded and initialize ACP session."
  [state]
  (let [workspace (workspace-state/get-workspace state)
        workspace-name (:name workspace)]
    [[:effects/save workspace-state/loaded-path true]
     [:acp.actions/init-session workspace-name]]))
```

**Step 3: Commit**

```bash
git add src/gremllm/renderer/actions/workspace.cljs
git commit -m "feat(acp): trigger session init on workspace load"
```

---

### Task 4: Add ACP Session Init and Prompt Actions

**Files:**
- Create: `src/gremllm/renderer/actions/acp.cljs`
- Modify: `src/gremllm/renderer/actions.cljs` (add require and registrations)

**Step 1: Create ACP actions module**

```clojure
(ns gremllm.renderer.actions.acp
  (:require [gremllm.renderer.state.acp :as acp-state]))

(defn init-session
  "Initialize ACP session. Called on workspace load."
  [_state workspace-name]
  ;; Use workspace name as cwd hint - main process resolves to full path
  [[:effects/promise
    {:promise    (.acpNewSession js/window.electronAPI workspace-name)
     :on-success [[:acp.actions/session-ready]]
     :on-error   [[:acp.actions/session-error]]}]])

(defn session-ready
  "ACP session initialized successfully."
  [_state session-id]
  [[:effects/save acp-state/session-id-path session-id]])

(defn session-error
  "ACP session failed to initialize."
  [_state error]
  (js/console.error "[ACP] Session init failed:" error)
  [])

(defn send-prompt
  "Send user message to ACP agent."
  [state text]
  (let [session-id (acp-state/get-session-id state)]
    (if session-id
      [[:effects/save acp-state/loading-path true]
       [:effects/save acp-state/chunks-path []]
       [:effects/promise
        {:promise (.acpPrompt js/window.electronAPI session-id text)}]]
      (do
        (js/console.error "[ACP] No session - cannot send prompt")
        []))))
```

**Step 2: Register actions in actions.cljs**

Add to requires:

```clojure
[gremllm.renderer.actions.acp :as acp]
```

Add registrations after settings section (around line 182):

```clojure
;; ACP Actions
(nxr/register-action! :acp.actions/init-session acp/init-session)
(nxr/register-action! :acp.actions/session-ready acp/session-ready)
(nxr/register-action! :acp.actions/session-error acp/session-error)
(nxr/register-action! :acp.actions/send-prompt acp/send-prompt)
```

**Step 3: Commit**

```bash
git add src/gremllm/renderer/actions/acp.cljs src/gremllm/renderer/actions.cljs
git commit -m "feat(acp): add session init and prompt actions"
```

---

### Task 5: Update Main Process to Use Workspace Dir

**Files:**
- Modify: `src/gremllm/main/core.cljs:121-124`

**Step 1: Update acp/new-session handler**

The current handler receives `cwd` from renderer. Change it to use main's workspace-dir state:

```clojure
(.on ipcMain "acp/new-session"
     (fn [event ipc-correlation-id _workspace-hint]
       (let [cwd (or (state/get-workspace-dir @store)
                     (io/user-data-path))]  ;; fallback to userData
         (nxr/dispatch store {:ipc-event event :ipc-correlation-id ipc-correlation-id}
                       [[:acp.actions/new-session cwd]]))))
```

Add require for state and io if not present.

**Step 2: Commit**

```bash
git add src/gremllm/main/core.cljs
git commit -m "feat(acp): use workspace-dir from main state for session cwd"
```

---

### Task 6: Wire Form Submit to ACP

**Files:**
- Modify: `src/gremllm/renderer/actions/ui.cljs:22-41`

**Step 1: Update submit-messages to use ACP**

Replace the function:

```clojure
(defn submit-messages [state]
  (let [text (form-state/get-user-input state)]
    (when-not (empty? text)
      (let [new-user-message {:id   (.now js/Date)
                              :type :user
                              :text text}]
        [[:messages.actions/add-to-chat new-user-message]
         [:form.actions/clear-input]
         [:ui.actions/focus-chat-input]
         [:ui.actions/scroll-chat-to-bottom]
         [:acp.actions/send-prompt text]]))))
```

Key changes:
- Removed model/reasoning logic (ACP handles this)
- Removed assistant-id and loading tracking (ACP handles via chunks)
- Removed LLM effect call
- Added ACP prompt action
- Removed attachment handling (defer for now)

**Step 2: Commit**

```bash
git add src/gremllm/renderer/actions/ui.cljs
git commit -m "feat(acp): route form submit through ACP instead of LLM"
```

---

### Task 7: Update Chat UI to Render ACP Chunks

**Files:**
- Modify: `src/gremllm/renderer/ui.cljs:36-39`
- Modify: `src/gremllm/renderer/ui/chat.cljs:48-57`

**Step 1: Add ACP state to ui.cljs**

Add require:

```clojure
[gremllm.renderer.state.acp :as acp-state]
```

Update the render-chat-area call (around line 37):

```clojure
(chat-ui/render-chat-area (topic-state/get-messages state)
                          (acp-state/get-chunks state)
                          (acp-state/loading? state)
                          (loading-state/get-assistant-errors state))
```

**Step 2: Update render-chat-area in chat.cljs**

Replace function:

```clojure
(defn render-chat-area [messages acp-chunks acp-loading? errors]
  [e/chat-area {}
   (for [message messages]
     (render-message message))

   ;; Show streaming ACP response
   (when (seq acp-chunks)
     (render-assistant-message {:text (apply str acp-chunks)}))

   ;; Show loading indicator while waiting for first chunk
   (when acp-loading?
     (render-loading-indicator))

   ;; Show any errors
   (render-error-message errors)])
```

Key changes:
- New signature with `acp-chunks` and `acp-loading?`
- Render accumulated chunks as single assistant message
- Loading shows only during ACP loading (not old `loading` map)

**Step 3: Commit**

```bash
git add src/gremllm/renderer/ui.cljs src/gremllm/renderer/ui/chat.cljs
git commit -m "feat(acp): display streaming ACP chunks in chat UI"
```

---

## Verification

**Manual test:**

1. Run `npm run dev`
2. Open the app, select or create a workspace
3. Check DevTools console for `[ACP] Session init` (should see session ID)
4. Type a message and submit
5. Should see:
   - User message appears immediately
   - Loading indicator shows briefly
   - Assistant message grows as chunks stream in
   - Loading disappears on first chunk

**Console test (if UI not ready):**

```javascript
// In DevTools console after workspace loads
const state = window.__NEXUS_STORE__.deref()
state.acp.sessionId // should have value

// After sending a message
state.acp.chunks // should have accumulated text
```

---

## Files Modified Summary

| File | Action |
|------|--------|
| `src/gremllm/renderer/state/acp.cljs` | Create |
| `src/gremllm/renderer/actions/acp.cljs` | Create |
| `src/gremllm/renderer/actions.cljs` | Modify (chunk handler + registrations) |
| `src/gremllm/renderer/actions/workspace.cljs` | Modify (trigger init) |
| `src/gremllm/renderer/actions/ui.cljs` | Modify (submit → ACP) |
| `src/gremllm/renderer/ui.cljs` | Modify (pass ACP state) |
| `src/gremllm/renderer/ui/chat.cljs` | Modify (render chunks) |
| `src/gremllm/main/core.cljs` | Modify (use workspace-dir) |
