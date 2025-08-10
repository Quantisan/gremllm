# Sprint Plan: Multi-Topics and Multi-Workspaces

Guiding principles
- User-owned, plain EDN files; easy to back up, diff, and sync.
- FCIS separation; minimal surface area for side effects.
- Modelarity: Workspace and Topic are first-class domain concepts.
- Skateboard → Scooter: small, shippable steps; maintain back-compat.

Outcomes (end of sprint)
- Users can create/switch workspaces and switch between multiple topics.
- All data persists as topic-*.edn inside a workspace folder.
- No surprise metadata; migration preserves existing user files.

Scope
- Multiple topics per workspace; multiple workspaces; default workspace ("default") for continuity.
- Attachments will live next to their topic (topic-<id>.assets/…) in a future iteration.

Non-goals (now)
- Nested topics, workspace manifests, or complex metadata.
- Sync/versioning features; UI preference persistence inside workspaces.
- Attachments implementation.

Milestones (PRs)
1) Workspace filesystem primitives (backend only)
   - Outcome: Storage rooted at workspaces/default; no behavior/UI change.

2) Topic listing in the default workspace (backend)
   - Outcome: Can enumerate topics; no UI change.

3) Deterministic bootstrap (list → load newest or create)
   - Outcome: Startup behavior is predictable; still no UI change.

4) Topics UI (single workspace)
   - Outcome: Left panel lists topics; users can switch topics.

5) Save semantics return identifiers
   - Outcome: Save returns id + path; state tracks both for reliable round-trips.

6) Workspaces backend (list/create)
   - Outcome: Workspaces can be listed/created; UI still on default.

7) Workspace state + switching (renderer)
   - Outcome: Simple selector to switch workspaces; topic UI scoped per workspace.

8) One-time migration (legacy → default)
   - Outcome: Existing topic files are moved into workspaces/default on first run; no user action required.

Risks & mitigations
- Slug collisions: ensure uniqueness by directory existence check; surface error to UI.
- Large directories: keep listing simple and pageless for now; revisit if needed.
- Migration safety: copy-then-move strategy; abort on conflict.

Success criteria
- Cold start in a fresh profile creates a default workspace and a first topic automatically.
- Switching topics/workspaces is <200ms on typical machines.
- No data loss during migration (verified by tests).
