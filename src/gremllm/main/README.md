# Main Process Architecture

## Ownership

The main process owns Electron application lifecycle, native window and menu
setup, IPC registration, document and topic persistence, and the CLJS side of
ACP connection management.

## Runtime Boundary

Two IPC patterns coexist here:

- synchronous handlers in `core.cljs` return values directly for
  request-response operations such as topic save and topic delete
- asynchronous handlers in `core.cljs` dispatch Nexus actions for workflows
  whose results return later over IPC events, such as document open/reload and
  ACP session operations

This split is operationally important. If a change must return an immediate
value to `ipcRenderer.invoke`, trace the synchronous pipeline. If it should
trigger later renderer updates, trace the Nexus-dispatched path and the matching
IPC event.

Note the mechanism split: `acp/*` handlers use `ipcMain.on` (fire-and-forget;
no return value, the renderer never awaits the call), while all other handlers —
including the Nexus-dispatching ones — use `ipcMain.handle`. Renderer state for
ACP arrives through the `acp:session-update` and `acp:permission-pending`
event streams.

## Domain Map

- `core.cljs`: app bootstrap, IPC handler registration, app lifecycle hooks
- `actions.cljs`: Nexus action and effect registration for main-process
  behavior
- `actions/`: pure planning helpers for ACP prompt content, topics, and
  document open/pick/reload actions
- `effects/`: imperative file I/O, dialogs, IPC replies, ACP runtime
  integration, attachment storage; `effects/acp/permission.cljs` owns the
  SDK `resolvePermission` seam and deferred-approval state
- `effects/ipc.cljs`: IPC boundary effects
- `window.cljs`: BrowserWindow sizing, preload wiring, and close handling
- `menu.cljs`: native menu template and dispatch into renderer-facing commands
- `io.cljs`: path helpers, file utilities, and per-document storage paths
- `state.cljs`: active document path, userData dir, and derived per-document
  storage/topics lookups used by IPC handlers

## Hot Paths

### Document Open Or Reload

Start in `core.cljs` at `document/pick` (which opens the file picker) or
`document/reload`, then follow `main.actions.document` and
`main.effects.workspace/load-and-sync`. That effect reads the chosen `.md` file
at its real path, loads `topics/*.edn` from the document's per-document storage
dir under `userData`, writes `meta.edn` if absent, and pushes `document:opened`
to the renderer.

### Topic Save Or Delete

Start in `core.cljs` at `topic/save` or `topic/delete`. The topics directory is
derived from the active document via `state/get-document-paths`; then follow
`main.actions.topic` for the pure save or delete plan and
`main.effects.workspace` for the disk effect.

### ACP Session Operations

Start in `core.cljs` at `acp/new-session`, `acp/resume-session`, or
`acp/prompt`. Session runtime behavior lives in `main.effects.acp`, while
prompt content construction lives in `main.actions.acp`. The JS transport bridge
is at `src/js/acp/index.js`; pure permission policy is at
`src/gremllm/schema/codec/acp/permission.cljs` and runtime permission state
lives in `main.effects.acp.permission`. See those READMEs for details on each
side of the seam.

### Deferred Permission Flow

Start in `core.cljs` at `acp/resolve-permission`, which dispatches to
`effects.acp.permission/record-decision!` to unblock the SDK callback.

## IPC Channels

All handlers are registered in `core.cljs`. The preload bridge (`resources/public/js/preload.js`) exposes promise-style wrappers and intent-driven listeners so the renderer never touches raw IPC strings.

**Topic:** `topic/save`, `topic/delete`
**Document:** `document/create`
**ACP:** `acp/new-session`, `acp/resume-session`, `acp/prompt`, `acp/resolve-permission`, `acp:session-update` (event, main → renderer), `acp:permission-pending` (event, main → renderer)
**Workspace:** `workspace/pick-folder`, `workspace/reload`, `workspace:opened` (event, main → renderer)
**Menu:** `menu:command` (event, main → renderer)

## Data Storage

The document and its bookkeeping live apart. The `.md` file stays wherever the
user keeps it; gremllm never creates or moves it. Topics and meta live under
`userData`, keyed by a hash of the document's absolute path.

```
<any-folder>/                # Wherever the user keeps the file
└── my-document.md           # Opened in place; gremllm only reads/writes content

<userData>/User/documents/<sha256(absolute-doc-path)>/   # Per-document storage
├── meta.edn                 # Document display name (written if absent)
└── topics/
    └── *.edn                # One file per topic; includes session id and pending diffs
```

See `src/gremllm/main/io.cljs` for path helpers (including `document-storage-dir`
and `path->document-hash`) and `src/gremllm/schema/codec.cljs` for disk codecs.

## Entry Points

- `src/gremllm/main/core.cljs`
- `src/gremllm/main/actions.cljs`
- `src/gremllm/main/effects/workspace.cljs`
- `src/gremllm/main/effects/acp.cljs`
- `src/gremllm/main/effects/acp/permission.cljs`
- `src/gremllm/main/window.cljs`
- `src/gremllm/main/menu.cljs`
