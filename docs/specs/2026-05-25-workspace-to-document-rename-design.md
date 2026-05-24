# Workspace-to-Document Domain Rename

## Context

The `feat/open-any-markdown-file` branch replaced the folder-centric "workspace" model with a file-centric "document" model. Users now open any `.md` file directly; gremllm stores bookkeeping in a hash-keyed directory under `userData`, never co-located with the file.

The code still uses "workspace" naming throughout: the main process effects namespace, renderer state keys, action keywords, schema types, and UI functions. This spec aligns naming with the new domain model.

## Design

### 1. Main Process: Decompose `effects/workspace.cljs`

Split into two files along the document/topic domain boundary:

**`effects/document.cljs`** — document lifecycle I/O:
- `pick-dialog` — native file picker for `.md` files
- `load-and-sync` — reads document content, loads topics, assembles sync payload, pushes to renderer
- `record-source-path` — writes `meta.edn` via `write-meta-if-missing!`
- `write-meta-if-missing!` (private) — writes `meta.edn` if absent

Effect keywords unchanged: `:document.effects/pick-dialog`, `:document.effects/load-and-sync`, `:document.effects/record-source-path` (these were already renamed to `document.effects` in the branch).

**`effects/topic.cljs`** — topic persistence:
- `save-topic` — writes topic `.edn` to disk
- `delete-topic-with-confirmation` — native confirmation dialog + file delete
- `enumerate` — lists topic files with metadata
- `load-topics` — reads and parses all topic files

These are called directly from IPC handlers in `core.cljs` (not via Nexus effects). `load-and-sync` in `effects/document.cljs` will require `effects/topic` for `load-topics` — a one-way dependency.

Delete `effects/workspace.cljs` after migration.

### 2. Renderer: Merge `[:workspace]` into `[:document]`

The renderer state key `[:workspace]` (holding `{:name "..." :loaded? true}`) merges into `[:document]`, which currently holds only `{:content "..."}`.

After merge, `[:document]` becomes:
```clojure
{:name "Investment Memo"
 :loaded? true
 :content "# Investment Memo\n\n..."}
```

**File changes:**

- `state/workspace.cljs` → merge paths into `state/document.cljs`
  - `workspace-path` → remove (paths defined directly under `[:document]`)
  - `get-workspace` → `get-document-meta` or inline
  - `loaded?` accessor stays, path updates to `[:document :loaded?]`

- `actions/workspace.cljs` → absorb into `actions/document.cljs`

- Delete `state/workspace.cljs` and `actions/workspace.cljs` after migration.

**Action keyword renames:**

| Old | New |
|-----|-----|
| `:workspace.actions/opened` | `:document.actions/opened` |
| `:workspace.actions/set` | `:document.actions/set-meta` |
| `:workspace.actions/mark-loaded` | `:document.actions/mark-loaded` |
| `:workspace.actions/restore-with-topics` | `:document.actions/restore-with-topics` |
| `:workspace.actions/initialize-empty` | `:document.actions/initialize-empty` |
| `:workspace.actions/load-error` | `:document.actions/load-error` |

### 3. Schema Renames

| Old | New |
|-----|-----|
| `WorkspaceTopics` | `DocumentTopics` |
| `valid-workspace-topics?` | `valid-document-topics?` |
| `create-workspace-meta` | `create-document-meta` |
| `WorkspaceSyncData` | `DocumentSyncData` |
| `workspace-sync-from-ipc` | `document-sync-from-ipc` |
| `workspace-sync-for-ipc` | `document-sync-for-ipc` |

The `[:workspace [:map [:name :string]]]` key inside `DocumentSyncData` renames to `[:document-meta [:map [:name :string]]]` to avoid collision with the `[:document]` content key in the same payload.

### 4. UI Renames

- `ui.cljs`: `render-workspace` → `render-document`
- `topics.cljs`: `render-workspace-header` → `render-document-header`; prop key `:workspace` → `:document-meta`

### 5. Test Updates

- `test/gremllm/renderer/actions/workspace_test.cljs` → `document_test.cljs` (merge or replace)
- `test/gremllm/main/effects/workspace_test.cljs` → split into `document_test.cljs` and `topic_test.cljs`
- Update all action keyword references and prop keys in `topics_test.cljs`

## Verification

1. `npm run test` — all unit tests pass after renames
2. `npm run dev` — app starts, opening a `.md` file loads and syncs correctly
3. `grep -r "workspace" src/` — no remaining references except comments explaining the rename history (if any)
4. Verify topic save/delete still works through IPC handlers
