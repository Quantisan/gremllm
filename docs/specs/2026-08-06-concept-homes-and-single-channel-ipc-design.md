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

### What counts as a concept

A concept is a unit of functionality defined from the user's perspective (after
Jackson, *The Essence of Software*): it has a purpose a user would recognize, it
owns its own state and actions, and its name means the same thing everywhere it
appears. Homes are granted per concept, not per feature. Tests before granting
one:

1. **Purpose:** "X exists so that Y" fits in one sentence a user would recognize.
2. **State:** it owns state paths and a schema fragment; shared lifetime is the
   tell — ephemeral UI mechanics and persisted domain data rarely share a concept.
3. **Stable meaning:** one name never resolves to two types.
4. **Independence:** explaining it doesn't reach into another concept's internals.
   Where concepts touch is a seam, not shared ownership.

Applied to "anchor," these tests split it in two. The excerpt ("a user-curated
piece of the document, saved as AI context") and the anchor ("a session is
anchored to a document excerpt") are separate concepts with separate lifetimes.
The selection-popover geometry currently named `AnchorContext` is neither — it is
capture mechanics inside excerpt, and loses the name (see Migration posture).

### 1. Concept homes

A concept lives in one directory that owns its schema fragment, actions, state
selectors, UI, and effect handlers. Each home draws from five file roles, each
answering one question:

- **`model.cljs` — what is it?** Schemas and pure domain logic, in the user's
  vocabulary. Loadable by either process build; never mentions a host
  environment (DOM, Node, Electron).
- **`flow.cljs` — what happens?** Actions, state transitions, and the exported
  registry fragment.
- **`ui.cljs` — how does it look?** Replicant components.
- **`codec.cljs` — how does it cross a boundary?** Interface wire shapes and
  their translations, per the existing "coercion named for its boundary" rule.
  An interface's schemas live here with its codec, not in `model.cljs`:
  `CapturedSelection`'s fields (`anchor-node`, `focus-offset`, `range-count`)
  are the browser Selection API's vocabulary, not the domain's — the pair is
  one unit, the contract of the "browser selection → excerpt" boundary.

- **`effects.cljs` — what does it do to the world?** Imperative-shell effect
  handlers: file I/O, Electron APIs, DOM operations, async wrappers. FCIS is
  unchanged — flows still return effect descriptions, and registration still
  funnels through the process registry file; only where the handler lives
  moves. Effects are host-specific by nature, so the file is required only by
  the process that runs them — the same rule that keeps `excerpt/codec.cljs`
  out of main's Node build. A concept with effects in both processes splits by
  process (`topic/effects/main.cljs`, `topic/effects/renderer.cljs`), as codec
  splits by interface.

Not every concept needs all five — a concept with no boundary translations has
no `codec.cljs`, a pure one no `effects.cljs`; absence is information. A concept that accumulates several
interfaces splits by interface, as `schema/codec/acp.cljs` already does (e.g.
`excerpt/codec/dom.cljs`).

The anchored-sessions feature, consolidated — two homes, per the definition
above:

```
src/gremllm/excerpt/
  model.cljs      — DocumentExcerpt schema, popover geometry math (pure, shared)
  codec.cljs      — CapturedSelection + PopoverGeometry schemas and from-dom
                    codecs (browser-interface adapter; renderer-only by nature)
  flow.cljs       — capture/dismiss/attach actions, exported registry fragment
                    {:nexus/actions {...}}
  ui.cljs         — selection popover, composer excerpt pills
src/gremllm/anchor/
  model.cljs      — Topic :anchor schema fragment, anchored-topic selectors
  flow.cljs       — start-anchored-session actions, exported registry fragment
  ui.cljs         — gutter render+sync, highlight sync, composer anchor pill
test/gremllm/excerpt_test.cljs, test/gremllm/anchor_test.cljs
                  — one file per concept, domain logic only
```

What remains outside the homes are genuine seams — places one concept meets
another (anchor references an excerpt, chat composer attaches it, document
renderer paints it, main's prompt body includes it). Seams are one call each and
would exist under any organization.

ACP prompt-section rendering stays at its seam in `main/actions/acp.cljs`. It
knows the transport's presentation (`"Anchor:"` headers, blockquote indentation,
content blocks), so it is the ACP boundary's codec consuming `anchor/model` —
not anchor domain logic. "Code that mentions anchor" is not "anchor domain
logic."

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
Fragments are per-process: one shared `registry` var cannot serve two processes
whose action and effect sets differ, so a spanning concept exports one fragment
per process — document's dialog flows and file I/O merge into main's registry,
its renderer actions into the renderer's.
(Equivalent form: each concept exposes a `register!` fn and the registry file calls
one per concept — same property, keeps the current `nexus.registry` style. The
implementation plan picks one.)

The merge must reject duplicate names. A plain `merge-with merge` silently
last-writer-wins when two concepts register the same action keyword — the
opposite of stable meaning and of the fail-fast posture the IPC table gets for
free. Use a merge that throws on key collision; it runs once at load, so a
collision fails at boot, not at dispatch. (The `register!` form needs the same
check.)

**Schemas compose.** Each concept owns its schema fragment; `schema.cljs` composes
fragments into the boundary models (e.g. `PersistedTopic` embeds the anchor
fragment) and remains the place boundary validation looks.

### 2. Single-channel IPC

This applies to the renderer→main request direction, where each new capability
today costs preload method + channel + wrapper + handler. Replace those
per-feature sites with one chokepoint behind named methods:

- **Preload** keeps one intent-named method per capability (`saveTopic`,
  `pickDocument`), but each is a one-liner over a single transport channel:
  `saveTopic: (topic) => request("topic/save", topic)`. Named methods are
  Electron's recommended `contextBridge` shape, keep call sites greppable, and
  keep preload.js a readable inventory of what the renderer can ask main to do.
  The per-feature channel constants and promise-correlation machinery go away.
- **Main** registers one `ipcMain.handle` that first verifies the sender —
  `event.senderFrame` is the app window's main frame — then dispatches
  `{action, payload}` against a closed map, the same dispatch-by-name shape as
  the Nexus registries.
- **Each table entry** declares its request and response schemas alongside its
  handler. The single handler validates both directions through `schema/codec`
  at this chokepoint, exactly as ACP payloads are in `schema/codec/acp.cljs`.
  The renderer does not re-validate responses: main is trusted same-app code,
  and contract drift is already caught at the source. Entries are exported by
  the owning concept's home (the handler is main-process code from its
  `effects` file) and merged into the closed table — the same
  one-line-per-concept shape as the Nexus registries, with the same
  throw-on-collision merge.

**Execution contract.** A table entry's handler is
`(fn [context payload]) -> value | Promise`; `ipcMain.handle` returns whatever
the entry returns. Direct shell calls (`topic/save`) return their value.
Flows that dispatch into Nexus (the ACP channels) wrap the dispatch in a
Promise, carry its `resolve`/`reject` in `dispatch-data` where the
correlation id travels today, and return that Promise — the existing
`:ipc.effects/reply` and `reply-error` effects call `resolve`/`reject`
instead of sending on a correlation-named channel. Fire-and-forget entries
(`acp/resolve-permission`) return nil. The correlation machinery is not
ported to the new channel; `handle` correlates natively, so it is deleted.

The security lives in main's closed table and sender check, not in the method
names — the names are cheap ergonomics on top. A closed, validated dispatch
satisfies Electron's security guidance (the anti-pattern is a generic
`ipcRenderer` passthrough; an enumerated dispatch behind named methods is the
recommended shape). New cross-process capability then costs one preload
one-liner, one table entry, and its schemas — the channel constant, promise
wrapper, and per-feature handler disappear. The preload list and the main
table are two enumerations kept in sync; drift fails fast, since the closed
table rejects unknown actions.

The main→renderer push direction stays as-is: every push already funnels through
the single `:ipc.effects/send-to-renderer` effect, and the busy channels
(`acp:session-update`, `menu:command`) already dispatch `{name, payload}` by name
internally. Collapsing its few channels into one would buy symmetry, not a cost
reduction.

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

The two parts migrate differently.

**Single-channel IPC migrates in one bounded change.** The renderer→main request
surface is eight preload methods, and the conversion is mechanical. Gradual
migration would be worse than either design alone: the single-chokepoint
security claim is false while any legacy channel coexists, and the
promise-correlation machinery in `preload.js` can only be deleted when the last
channel using it migrates.

**Concept homes migrate gradually** — the surface is large and the moves are
judgment calls. No big-bang rewrite: new concepts get homes from day one, and
existing code migrates only when a change touches it, concept by concept.

Anchor is the designated first
worked example: the next change to anchoring behavior performs the consolidation
above as its first step. Step zero of that consolidation is a rename: the popover
geometry (`AnchorContext`, stored at `[:excerpt :anchor]`) loses the word
"anchor" — e.g. `PopoverGeometry` at `[:excerpt :popover-geometry]` — so the name
means one thing before any code moves.

The consolidation also fixes an existing boundary flaw it would otherwise
inherit: today `schema/codec.cljs` houses the live-DOM capture codecs
(`captured-selection-from-dom` and friends) alongside disk and IPC codecs, in a
file seven main-process namespaces require — renderer-only code in main's Node
build. Moving them to `excerpt/codec.cljs` ends that.

## Verification

- `npm run test` and `npm run test:all` stay green through each migration step.
- The anchor consolidation is verified in the running app (`npm run dev`): gutter
  bars, highlights, composer pill, and ACP prompt anchor section behave unchanged.
- Expected observations, reviewed over the next several feature PRs: median files
  touched per feature PR drifts from 6 toward 3–4, and test-to-code churn inside
  feature PRs falls. These are trailing signals that the design worked, not
  targets for any single PR — treating them as targets invites larger files and
  skipped tests. Never hand them to an agent as an instruction.

## Out of scope

- New stack, new libraries, or a separate experimentation vehicle — rejected in
  favor of reshaping in place.
- Migrating all existing concepts on a schedule.
- Changes to ACP transport or the FCIS principle itself.
