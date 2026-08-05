# Design: Concept Homes and a Single IPC Channel

**Date:** 2026-08-06
**Status:** Proposed
**Related:**
- Engineering rules: `CLAUDE.md` (this design amends three of them)
- Worked example source: PRs #265, #267, #268, #270, #271 (excerpt-anchored sessions)

## Problem

Expressing one domain idea costs about ten armored code sites. Analysis of all 224
merged PRs (Jul 2025 – Jun 2026) shows the median feature touches 3 architectural
layers and 6 files; half of all PRs touch a registry file; feature size grew from
~110 lines (Jan) to ~910 (May) while feature count fell. The anchored-sessions
feature is the concrete case: one sentence — "a chat session is anchored to a
document excerpt" — spread across 17 source files and 13 test files over 3.5 weeks.

The armor itself (schemas at boundaries, tests, registries) is deliberate and stays:
it keeps AI agents in check and gives them a feedback loop. The cost equation is

    cost per idea = sites per idea × armor per site

Armor per site is fixed by ruling. This design reduces sites per idea from ~10 to
~3–4 by reorganizing code around concepts, and makes the Electron process boundary
cost one map entry instead of four files.

Two findings ground the design:

1. **Nexus permits decentralized registration.** The registry is a plain map;
   `nexus.core` works without global state. Concept namespaces can export their own
   action/effect maps. Only our conventions force central restatement.
2. **No Electron library helps.** Typed-IPC libraries (electron-trpc, tipc) sell
   TypeScript inference CLJS cannot consume; state-sync libraries (zubridge,
   electron-redux) want to own the store Nexus already owns. The pattern their
   serious implementations share — and VS Code uses internally — is one transport
   channel carrying `{name, payload}`, dispatched by name against a closed table,
   validated at the edge. We already run this pattern for the ACP subprocess
   boundary (JSON-RPC over stdio).

## Design

### 1. Concept homes

A concept lives in one directory that owns its schema fragment, actions, state
selectors, and UI. The anchor concept, consolidated:

```
src/gremllm/anchor/
  model.cljs      — AnchorContext schema, DOM codec, ACP prompt-section rendering
                    (pure; main requires it the same way it requires schema.cljs)
  flow.cljs       — capture/dismiss/start-anchored-session actions, anchored-topic
                    selectors, exported registry fragment {:nexus/actions {...}}
  ui.cljs         — gutter render+sync, highlight sync, anchor pills
test/gremllm/anchor_test.cljs — one file, domain logic only
```

What remains outside the home are genuine seams — places the concept meets another
concept (chat composer attaches the anchor, document renderer paints it, main's
prompt body includes it). Seams are one call each and would exist under any
organization.

**Registries become merges.** `renderer/actions.cljs` and `main/actions.cljs` keep
their role as the system inventory, but list concepts instead of restating every
action:

```clojure
(def nexus
  (merge-with merge
    anchor/registry
    topic/registry
    excerpt/registry
    ...))
```

One line per concept. Reading the file still shows everything the system does.
(Equivalent form: each concept exposes a `register!` fn and the registry file calls
one per concept — same property, keeps the current `nexus.registry` style. The
implementation plan picks one.)

**Schemas compose.** Each concept owns its schema fragment; `schema.cljs` composes
fragments into the boundary models (e.g. `PersistedTopic` embeds the anchor
fragment) and remains the place boundary validation looks.

### 2. Single-channel IPC

Replace per-feature preload methods and IPC channels with one chokepoint:

- **Preload** exposes one `invoke`-style bridge, not one method per feature.
- **Main** registers one `ipcMain.handle` that dispatches `{action, payload}`
  against a closed map — the same dispatch-by-name shape as the Nexus registries.
- **Payloads** are validated through `schema/codec` at the boundary, exactly as ACP
  payloads are in `schema/codec/acp.cljs`.

A closed dispatch table plus edge validation satisfies Electron's security guidance
(the anti-pattern is exposing *raw* `ipcRenderer` passthrough; a validated,
enumerated dispatch is the recommended shape). New cross-process capability then
costs one map entry and one schema, not preload + channel + wrapper + handler.

### 3. Rule amendments (CLAUDE.md)

1. **File size.** "Files stay small (30–90 lines)" becomes: a file spans one
   boundary; concept files may run 100–400 lines when that is the boundary. Small
   scope still forces API thinking — the scope is the concept, not the line count.
2. **Centralized registration.** The registry file lists one merge line per
   concept. Definitions live in the concept's home.
3. **Test mirroring.** One test file per concept, covering domain logic and
   boundary transforms — not one test file per source file. This restores the
   "test conservatively" rule the mirror convention had silently overridden.

### Migration posture

No big-bang rewrite. New concepts get homes from day one. Existing code migrates
only when a change touches it, concept by concept. Anchor is the designated first
worked example: the next change to anchoring behavior performs the consolidation
above as its first step.

## Verification

- `npm run test` and `npm run test:all` stay green through each migration step.
- The anchor consolidation is verified in the running app (`npm run dev`): gutter
  bars, highlights, composer pill, and ACP prompt anchor section behave unchanged.
- Success metric, measured over the next several feature PRs: median files touched
  per feature PR drops from 6 toward 3, and test-to-code churn inside feature PRs
  drops below 0.5.

## Out of scope

- New stack, new libraries, or a separate experimentation vehicle — rejected in
  favor of reshaping in place.
- Migrating all existing concepts on a schedule.
- Changes to ACP transport or the FCIS principle itself.
