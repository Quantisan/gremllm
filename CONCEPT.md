# Simple persistence concept

Physical layout (single level) userData/
└── workspaces/
└── /          ; kebab-slug of workspace title (unique)
    ├── topic-.edn         ; one immutable file per topic, nothing else
    └── … more topic files …

## Intent & Context
- Workspace as repository; Topic as file. Plain EDN, user-owned, easy to back up and diff.
- Context flows from a workspace down to its topics; no hidden metadata files.
- Aligns with our Vision: structured, hierarchical, and context-aware thinking.

## Key simplifications

 1 No nested topics/ directory – workspace root holds the topic files directly.
 2 No workspace.edn manifest – we derive everything by scanning filenames. • Workspace title = directory name (slug → “My Research” ↔ my-research).
   • Last-opened, UI prefs, etc. stay in Nexus state or OS-level prefs; we don’t persist them here.
 3 Validation & lookup stay trivial (regex #"topic-\d+.edn").
 4 Attachments later: put them beside the topic that references them topic-.assets/… ; keeps locality without global asset folder.
 5 Secrets remain out-of-band (Electron safeStorage); topics always plaintext EDN.

Result • Only two filesystem concepts: “workspace folder” and “topic file”.
• Users can zip / git / sync a workspace by copying one folder.

## Trade-offs

 • Easiest to reason about and back up.
 • Zero extra metadata files to corrupt or merge.
   – We lose explicit workspace metadata (could be re-introduced later with a single optional README or metadata file without complicating current flow).

## Scope & Non-Goals
Scope
- Multiple topics per workspace; multiple workspaces with a default ("default").
- Topics are immutable EDN files; workspaces are folders.

Non-goals (now)
- Nested topics or workspace manifests.
- Persisting UI preferences inside workspaces.
- Implementing attachments, sync, or versioning.

## See also
- PLAN.md — Sprint plan with milestones and sequencing to deliver multi-topics and multi-workspaces.
