# Main Process Architecture

## Ownership

The main process owns Electron application lifecycle, native window and menu
setup, IPC registration, workspace and topic persistence, secrets storage, and
the CLJS side of ACP connection management.

## Runtime Boundary

Two IPC patterns coexist here:

- synchronous handlers in `core.cljs` return values directly for
  request-response operations such as topic save, topic delete, document
  create, secrets operations, and system info
- asynchronous handlers in `core.cljs` dispatch Nexus actions for workflows
  whose results return later over IPC events, such as workspace loading and ACP
  session operations

This split is operationally important. If a change must return an immediate
value to `ipcRenderer.invoke`, trace the synchronous pipeline. If it should
trigger later renderer updates, trace the Nexus-dispatched path and the matching
IPC event.

Note the mechanism split: `acp/*` handlers use `ipcMain.on` (fire-and-forget;
no return value, the renderer never awaits the call), while all other handlers —
including the Nexus-dispatching ones — use `ipcMain.handle`. Renderer state for
ACP arrives exclusively through the `acp:session-update` event stream.

## Domain Map

- `core.cljs`: app bootstrap, IPC handler registration, app lifecycle hooks
- `actions.cljs`: Nexus action and effect registration for main-process
  behavior
- `actions/`: pure planning helpers for ACP prompt content, documents, topics,
  secrets, and workspace actions
- `effects/`: imperative file I/O, dialogs, IPC replies, ACP runtime
  integration, attachment storage
- `window.cljs`: BrowserWindow sizing, preload wiring, and close handling
- `menu.cljs`: native menu template and dispatch into renderer-facing commands
- `io.cljs`: path helpers and file utilities
- `state.cljs`: workspace-directory lookup used by IPC handlers

## Hot Paths

### Workspace Open Or Reload

Start in `core.cljs` at `workspace/reload` or `workspace/pick-folder`, then
follow `main.actions.workspace` and `main.effects.workspace/load-and-sync`,
which reads `document.md`, loads `topics/*.edn`, and pushes `workspace:opened`
to the renderer.

### Topic Save Or Delete

Start in `core.cljs` at `topic/save` or `topic/delete`, then follow
`main.actions.topic` for the pure save or delete plan and
`main.effects.workspace` for the disk effect.

### Document Create

Start in `core.cljs` at `document/create`, then follow
`main.actions.document/create-plan` and
`main.effects.workspace/create-document`.

### ACP Session Operations

Start in `core.cljs` at `acp/new-session`, `acp/resume-session`, or
`acp/prompt`. Session runtime behavior lives in `main.effects.acp`, while
prompt content construction lives in `main.actions.acp`. The JS transport bridge
is at `src/js/acp/index.js`; permission policy is at
`src/gremllm/schema/codec/acp_permission.cljs`. See those READMEs for details
on each side of the seam.

## IPC Channels

All handlers are registered in `core.cljs`. The preload bridge (`resources/public/js/preload.js`) exposes promise-style wrappers and intent-driven listeners so the renderer never touches raw IPC strings.

**Topic:** `topic/save`, `topic/delete`
**Document:** `document/create`
**Secrets:** `secrets/save`, `secrets/delete`
**ACP:** `acp/new-session`, `acp/resume-session`, `acp/prompt`, `acp:session-update` (event, main → renderer)
**Workspace:** `workspace/pick-folder`, `workspace/reload`, `workspace:opened` (event, main → renderer)
**System:** `system/get-info`
**Menu:** `menu:command` (event, main → renderer)

## Data Storage

```
<userData>/User/
└── secrets.edn              # Encrypted API keys via Electron safeStorage

<workspace-folder>/          # User-selected folder; portable like a git repo
├── document.md              # Primary workspace artifact (created on demand)
└── topics/
    └── *.edn                # One file per topic; includes session id and pending diffs
```

See `src/gremllm/main/io.cljs` for path helpers and `src/gremllm/schema/codec.cljs` for disk codecs.

## Entry Points

- `src/gremllm/main/core.cljs`
- `src/gremllm/main/actions.cljs`
- `src/gremllm/main/effects/workspace.cljs`
- `src/gremllm/main/effects/acp.cljs`
- `src/gremllm/main/window.cljs`
- `src/gremllm/main/menu.cljs`
