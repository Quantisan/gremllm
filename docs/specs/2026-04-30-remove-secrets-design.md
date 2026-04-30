# Remove Orphaned Secrets / API-Key Code Path

**Branch:** `refactor/remove-secrets`

## Context

Gremllm migrated to using ACP (Agent Client Protocol) exclusively for AI. The `claude-agent-acp` host now runs in-process (commits `65cff1b`, `0621efa`) and handles its own authentication — `src/gremllm/main/effects/acp.cljs:29` sets only `ELECTRON_RUN_AS_NODE=1` in the child env and passes no API key.

The legacy direct-LLM secrets-handling vertical is still fully wired and active. The user-visible symptom is `render-api-key-warning` rendering in the document panel because no key is stored. The path is a closed loop: the settings UI saves API keys via `safeStorage`, the Main process persists them to `<userData>/User/secrets.edn`, and the only consumer that reads them back is `system/get-info` — which feeds the same settings UI to display "you have a key stored." No live code outside the settings UI ever consumes a stored key.

Goal: delete this entire vertical — the warning, the modal, the menu item, the IPC channels, the safeStorage wrapper, the persistence helpers, the schema entries, the codecs, and their tests. Also clean up two dead-code remnants from the same era (`schema/model->provider` and `:model`/`:reasoning?` fixture keys).

Existing users' on-disk `secrets.edn` files are intentionally **left orphaned** — no migration code, no release-notes call-out.

The native "Settings…" menu item (Cmd/Ctrl+,) is removed entirely. It can be re-added when something genuinely needs configuring.

## Approach

Single PR on `refactor/remove-secrets`, five layered commits in dependency order. Each commit removes only code whose consumers are gone in the previous commit. Every intermediate state compiles and tests pass.

### Commit 1 — Remove user-facing surface

Remove the API-key settings UI and the native "Settings…" menu item.

- DELETE `src/gremllm/renderer/ui/settings.cljs`
- EDIT `src/gremllm/renderer/ui.cljs` — drop `sensitive-state`/`settings-ui` requires, the warning render (`:69-70`), `has-any-api-key?` binding (`:21`), the chat prop (`:80`), the modal mount (`:84-86`)
- EDIT `src/gremllm/renderer/ui/chat.cljs:118-140` — drop `has-any-api-key?` arg, conditional placeholder, disabled gate
- EDIT `src/gremllm/renderer/ui/elements.cljs:22` — drop `settings-dialog` alias
- EDIT `src/gremllm/main/menu.cljs:11-13,41-43` — drop "Settings…" menu items
- EDIT `src/gremllm/main/actions.cljs:29` — drop `:menu.actions/show-settings`
- EDIT `test/gremllm/renderer/ui/chat_test.cljs:14` — drop `:has-any-api-key?` from props

### Commit 2 — Remove renderer state and actions

Remove the now-unused settings/system actions, state, and registrations.

- DELETE `src/gremllm/renderer/actions/settings.cljs`
- DELETE `src/gremllm/renderer/actions/system.cljs`
- DELETE `src/gremllm/renderer/state/sensitive.cljs`
- EDIT `src/gremllm/renderer/state/system.cljs` — drop `encryption-available?`, `get-redacted-api-key`, `get-all-redacted-api-keys`, `has-any-api-key?`. Delete file if `system-info-path` has no other users.
- EDIT `src/gremllm/renderer/state/ui.cljs:4-9` — drop `showing-settings-path`/`showing-settings?`
- EDIT `src/gremllm/renderer/actions/ui.cljs:67-75` — drop `show-settings`/`hide-settings`
- EDIT `src/gremllm/renderer/actions.cljs:146-147,207-209,212-218` — drop `settings`/`system` requires and all `:settings.actions/*`, `:system.actions/*`, `:ui.actions/show-settings`, `:ui.actions/hide-settings` registrations
- EDIT `src/gremllm/renderer/core.cljs:40,69` — drop the `:show-settings` menu case and the bootstrap `[:system.actions/request-info]`
- DELETE `test/gremllm/renderer/actions/settings_test.cljs`

### Commit 3 — Remove main process secrets and IPC

Remove the Electron `safeStorage` wrapper, the secrets IPC handlers, and the persistence helpers.

- DELETE `src/gremllm/main/actions/secrets.cljs`
- EDIT `src/gremllm/main/core.cljs` — drop `secrets` require (`:3`), `system-info` (`:15-17`), the three IPC handlers `secrets/save`/`secrets/delete`/`system/get-info` (`:86-101`), `secrets-filepath` plumbing (`:43`, `:127-129`)
- EDIT `src/gremllm/main/io.cljs:79-92` — drop `secrets-file-path`, `read-secrets-file`, `write-secrets-file`
- EDIT `resources/public/js/preload.js:66-69` — drop `getSystemInfo`, `saveSecret`, `deleteSecret`
- DELETE `test/gremllm/main/actions/secrets_test.cljs`
- EDIT `test/gremllm/main/io_test.cljs:7-47` — drop the three secrets-file tests
- EDIT `test/gremllm/renderer/state/system_test.cljs:9-57` — drop the four secrets-related deftests (delete file if empty)

### Commit 4 — Remove schema and codec entries

Drop the secrets-related schemas and codecs.

- EDIT `src/gremllm/schema.cljs:34-92` — drop `provider-storage-key-map`, `provider->api-key-keyword`, `keyword-to-provider`, `APIKeysMap`, `NestedSecrets`. Grep `supported-providers` and `provider-display-name` for non-secrets callers; remove if none.
- EDIT `src/gremllm/schema/codec.cljs:26-67` — drop `FlatSecrets`, `SystemInfo`, `secrets-from-ipc`, `system-info-from-ipc`, `system-info-to-ipc`
- EDIT `test/gremllm/schema_test.cljs:13-35` — drop `provider->api-key-keyword` and `keyword-to-provider` tests

### Commit 5 — Cleanup dead remnants and docs

Drop legacy LLM-era leftovers and update documentation.

- EDIT `src/gremllm/schema.cljs:46-53` — drop `model->provider`
- EDIT `test/gremllm/schema_test.cljs:37-53` — drop `model->provider` test
- EDIT `test/gremllm/main/effects/workspace_test.cljs:34,45,57,77,126,127,144,159` — drop `:model`/`:reasoning?` keys from fixtures
- EDIT `test/gremllm/renderer/actions/workspace_test.cljs:55` — drop `:model` key
- EDIT `README.md:41` — revise "requires API keys" wording
- EDIT `CLAUDE.md:44,125` and `AGENTS.md:44,125` — remove "secrets" from main-process ownership and `main.actions.secrets` namespace example
- EDIT `src/gremllm/main/README.md:6,15,36,80,90` — strip secrets-storage paragraph, IPC channel listings, and `secrets.edn` data layout
- DELETE `context/electron_safestorage.md`

## Critical files

- `src/gremllm/renderer/ui.cljs` — warning and modal mount
- `src/gremllm/renderer/actions.cljs` — central registration hub
- `src/gremllm/renderer/core.cljs` — bootstrap dispatch and menu route
- `src/gremllm/main/core.cljs` — IPC registration and secrets-filepath plumbing
- `src/gremllm/schema.cljs` and `src/gremllm/schema/codec.cljs` — boundary definitions
- `resources/public/js/preload.js` — IPC bridge

## Verification

Per commit:

```bash
npm run test:ci
```

After **Commit 1**, smoke test in dev:

- App boots without errors in main and renderer consoles
- Native "Settings…" menu item is gone (Cmd+, opens nothing)
- Document panel loads; ACP topic creates and prompts
- No "API key required" warning anywhere

After **Commit 3**, repeat dev smoke test:

- No `electronAPI.getSystemInfo is not a function` errors on boot
- ACP session starts and a prompt round-trips successfully

After **Commit 5**, full pass:

```bash
npm run test:all
npm run build
grep -rn -E "api-key|saveSecret|deleteSecret|getSystemInfo|render-api-key-warning|model->provider|safeStorage" src/ test/ resources/ context/
grep -rn -E ":model|:reasoning\?" test/
```

Expected: only hits inside `docs/specs/` and `docs/plans/` (historical, immutable).

## Out of scope

- On-disk `<userData>/User/secrets.edn` for existing users — left orphaned; no migration.
- A replacement settings home — out of scope; re-add the menu item when there is something to put in it.
