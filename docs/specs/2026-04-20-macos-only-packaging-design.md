# Design: macOS-Only Packaging Cleanup

**Date:** 2026-04-20
**Status:** Approved for phased implementation

## Goal

Reduce Gremllm's distribution surface to macOS only for the current MVP. Packaging should stay on Electron Forge, but only emit macOS artifacts needed for internal testing.

## Scope

### Phase 1: Cleanup

- Remove Windows and Linux makers from Forge config.
- Remove Windows and Linux packaging dependencies from `package.json`.
- Keep `npm run make` as the single packaging entrypoint.
- Limit the current packaging output to a macOS DMG only.

### Phase 2: macOS hardening

- Review and flesh out macOS-specific Forge settings such as bundle metadata, icons, signing, notarization, and any tester-facing artifact naming.
- This phase is intentionally deferred until Phase 1 is complete and verified.

## Decisions

- Stay on Electron Forge. This is already the repo's packaging tool and remains the current official Electron default.
- Use a true macOS-only config, not a partial cleanup.
- Keep the config minimal: one maker, `@electron-forge/maker-dmg`.
- Do not add ZIP, PKG, Windows, or Linux artifacts.

## Non-Goals

- No migration to `electron-builder`.
- No Windows installer support.
- No Linux packaging support.
- No signing/notarization work in this phase.
- No change to the app's runtime behavior.

## Verification

Phase 1 is complete when:

- `forge.config.js` contains only the DMG maker.
- `package.json` no longer declares Squirrel, deb, or rpm maker dependencies.
- `npm run make` still maps to the Forge make flow for the repo.
