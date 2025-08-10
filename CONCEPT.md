# Simple persistence concept

Physical layout (single level) userData/
└── workspaces/
└── /          ; kebab-slug of workspace title (unique)
    ├── topic-.edn         ; one immutable file per topic, nothing else
    └── … more topic files …

## Key simplifications

 1 No nested topics/ directory – workspace root holds the topic files directly.
 2 No workspace.edn manifest – we derive everything by scanning filenames. • Workspace title = directory name (slug → “My Research” ↔ my-research).
   • Last-opened, UI prefs, etc. stay in Nexus state or OS-level prefs; we don’t persist them here.
 3 Validation & lookup stay trivial (regex #"topic-\d+.edn").
 4 Attachments later: put them beside the topic that references them topic-.assets/… ; keeps locality without global asset folder.
 5 Secrets remain out-of-band (Electron safeStorage); topics always plaintext EDN.

Result • Only two filesystem concepts: “workspace folder” and “topic file”.
• Users can zip / git / sync a workspace by copying one folder.
• Implementation work you already sketched still applies, but workspace-topics-dir now just returns the workspace folder itself.

## Trade-offs

 • Easiest to reason about and back up.
 • Zero extra metadata files to corrupt or merge.
   – We lose explicit workspace metadata (could be re-introduced later with a single optional README or metadata file without complicating current flow).

## Incremental Plan: Multi-Topics and Multi-Workspaces

### Guiding Principles
- FCIS: Pure transforms in actions; all IO in main/effects; IPC as the boundary.
- Modelarity: New "workspace" domain gets its own namespaces; "topic" stays separate.
- Skateboard → Scooter: Ship value each step; keep defaults/back-compat until UI lands.

### Data & IPC
- Filesystem
  - userData/workspaces/<workspace-slug>/topic-*.edn
  - Default workspace: "default"
  - Topic filename pattern: topic-\d+.edn
- IPC (new/extended)
  - topic/list (accepts workspaceSlug; returns [{id filename filepath createdAtMs?}])
  - topic/load (accepts {topicId? workspaceSlug}; nil topicId = latest)
  - topic/save (accepts {topicData workspaceSlug}; returns {id filepath})
  - workspace/list (returns [{slug title}])
  - workspace/create (accepts {title}; returns {slug title})

### PRs (small, shippable steps)

1) Workspace FS primitives (backend only; no UI)
- Add workspaces root and workspace dir helpers; topics-dir-path points to "default".
- Tests cover new helpers; all existing tests remain green.

2) Topic listing in default workspace (backend)
- Implement topic/list; derive id from filename; prefer filename timestamp for createdAt.
- Expose electronAPI.listTopics; tests for listing.

3) Renderer bootstrap via list + load(id)
- Bootstrap: list topics, pick newest, load by id, else create + save.
- Update topic/load to accept optional topicId; preload exposes updated signature.
- Tests: bootstrap and load-by-id flow.

4) Topics UI (single workspace)
- Left panel shows topics; click to switch (loads by id).
- Action to normalize/set topic list; store minimal metadata.
- Tests for list normalization and UI render.

5) Save semantics return id + filepath
- Ensure topic/save returns {id filepath}; renderer updates state with filepath.
- Extend save/load round-trip tests.

6) Workspaces backend (list/create)
- Add workspace list/create effects and pure slug/title helpers.
- Expose electronAPI.listWorkspaces/createWorkspace; tests cover both.

7) Workspace state + switching (renderer)
- Add workspace state (active slug, list); default to "default".
- UI: simple workspace selector (top bar); switching reboots topics flow in that workspace.
- Pass workspaceSlug through topic list/load/save IPC.
- Tests for workspace bootstrap/switch and topic IPC parameterization.

8) One-time migration (legacy → default)
- Move legacy topic-*.edn into workspaces/default on first run; no-op if already migrated.
- Tests for migration behavior.

Outcome
- Each PR maintains current behavior until its UI is introduced.
- By PR 7, users can switch workspaces and topics; storage remains simple, per CONCEPT.
