# Gremllm Electron UI Testing Skill Design

**Date:** 2026-04-14
**Status:** Approved for spec drafting
**Scope:** Repo-local Codex skill design only. No implementation in this document.

## Summary

Create a repo-local Codex skill for `gremllm` that helps Codex validate the real Electron app through small, composable building blocks. The skill is specific to this repository and hardcodes repo facts that were discovered during the document excerpt locator spike, including Electron app targeting, workspace fixture conventions, local launch commands, and stable DOM anchors.

The skill is not a generic Electron automation skill and not a monolithic scenario runner. It is a project-specific toolkit for automated real-app validation in local macOS development.

## Problem

Useful UI validation work in this repo currently requires Codex to rediscover a fragile set of operational details:

- How to launch or attach to the local Electron app reliably
- What macOS process/app name to target for UI control
- How to open a disposable workspace fixture
- How to attach to the renderer through the Chromium DevTools Protocol
- How to inspect live DOM and console output without rebuilding ad hoc glue each time
- How to capture structured evidence for findings docs or regression notes

These mechanics are valuable across many UI test and spike situations, but they are currently embedded in session-specific experimentation instead of reusable repo knowledge.

## Goals

- Make Codex effective at validating the real `gremllm` Electron app in local development
- Encode repo-specific operational facts directly into one local skill
- Provide small, composable building blocks rather than one rigid scenario runner
- Support repeatable workflows for:
  - launching or attaching to the app
  - preparing and opening disposable workspace fixtures
  - driving read-only UI interactions
  - inspecting live DOM and console output via DevTools
  - capturing evidence artifacts for docs and reports
- Prefer safe, disposable, read-only investigation by default

## Non-Goals

- Generic Electron automation for unrelated repositories
- CI or headless automation in v1
- A full end-to-end test framework
- Write-heavy mutation flows as the default mode
- Replacing existing unit or integration tests

## Users and Invocation Model

The primary user is Codex working inside this repository.

The skill should be invoked when a task requires validating behavior in the real running Electron app rather than only through unit tests or static code inspection. Typical prompts may include:

- “Launch the app and inspect the rendered document DOM”
- “Run a manual UI spike against this fixture and capture evidence”
- “Open a workspace fixture and verify this selection flow”
- “Drive the live app to reproduce this renderer behavior”

The skill uses an operator workflow. Codex composes small helper scripts and procedures case by case instead of delegating to a single orchestration command.

## Skill Location and Shape

Create one repo-local skill:

`.agents/skills/gremllm-electron-ui-testing/`

Recommended contents:

```text
.agents/skills/gremllm-electron-ui-testing/
├── SKILL.md
├── scripts/
│   ├── launch_app.sh
│   ├── prepare_fixture.sh
│   ├── focus_app.applescript
│   ├── open_workspace.applescript
│   ├── cdp_eval.js
│   └── cdp_capture.js
└── references/
    └── repo-facts.md
```

`SKILL.md` should describe the workflow, decision rules, safety posture, and example usage. The brittle mechanics should live in helper scripts.

## Core Design Principle

This skill is a set of small but composable building blocks for automated real app validation.

That means:

- Each helper script should do one clear job
- Codex should be able to combine helpers differently for different UI investigations
- The skill should not assume every task follows the same scenario
- The skill should bias toward reusable primitives instead of task-specific orchestration

## Repo-Specific Facts To Encode

The skill should encode known `gremllm` facts directly instead of forcing Codex to rediscover them:

- Local app process name for UI automation: `Electron`
- Local repo root is the working directory for launch/build commands
- Preferred local validation launch path is: build first, then launch Electron directly from the repo root
- Local app is inspectable through Chromium DevTools remote debugging when launched with the correct flag
- Default CDP port is `9222`
- The primary renderer target is the page with title `Gremllm`
- Disposable workspace fixtures should live under `/tmp/gremllm-*`
- The document view can be anchored via `.document-panel article`
- The local build and test commands come from `package.json`

These facts should live in `references/repo-facts.md` and be reflected in helper script defaults.

## Default Workflow

The skill should teach the following workflow:

### 1. Prepare Fixture

Create or refresh a disposable workspace from a repo fixture file or folder.

Rules:

- Never mutate canonical fixtures in `resources/`
- Default fixture outputs should be created under `/tmp/gremllm-*`
- The helper should support copying a single markdown file into `document.md`

### 2. Launch Or Attach

Either attach to an already running local Electron session or launch a new one.

Rules:

- Prefer attach over restart if a valid debugging target already exists
- Use repo-local launch/build commands
- Default to `npm run build` followed by direct Electron launch with remote debugging on port `9222`
- Support that debugging port as a deterministic default so CDP scripts can connect without discovery guesswork

### 3. Focus And Open Workspace

Bring the Electron app to the foreground and open a workspace path.

Rules:

- Use repo-specific AppleScript helpers
- Hardcode the correct app/process target for this repo
- Fail clearly if macOS permissions are missing

### 4. Inspect And Drive

Once the app is open, prefer CDP-driven inspection and DOM interaction.

Capabilities:

- Evaluate JavaScript in the renderer
- Query DOM structure
- Intercept console output
- Drive safe read-only interactions such as clicks, selection, and scrolling
- Capture text and structured state needed for validation

### 5. Capture Evidence

Normalize session outputs into evidence that Codex can reuse in docs or reports.

Examples:

- Structured console payloads
- DOM excerpts
- Selected text
- Renderer observations
- Screenshots if later added

## Script Responsibilities

### `scripts/prepare_fixture.sh`

Responsibilities:

- Create disposable workspace directories under `/tmp`
- Copy fixture content from repo-controlled sources
- Print the created workspace path for downstream use

### `scripts/launch_app.sh`

Responsibilities:

- Launch the local app through the preferred local validation path
- Include remote debugging configuration on port `9222`
- Reuse existing sessions when possible or clearly report that a new launch occurred

### `scripts/focus_app.applescript`

Responsibilities:

- Activate the correct macOS app window for this repo
- Use the known app/process name directly

### `scripts/open_workspace.applescript`

Responsibilities:

- Open the workspace picker flow
- Enter a specific workspace path
- Confirm the open action

### `scripts/cdp_eval.js`

Responsibilities:

- Connect to the configured DevTools endpoint
- Evaluate arbitrary expressions in the renderer target
- Return normalized results and surface evaluation failures clearly

### `scripts/cdp_capture.js`

Responsibilities:

- Provide higher-level capture helpers on top of raw evaluation
- Support console interception and normalized JSON output for evidence collection

## Safety and Defaults

The skill must default to read-only investigation.

Default rules:

- Use disposable fixtures, not user workspaces
- Do not mutate workspace content unless explicitly asked
- Prefer attach over restart
- Prefer CDP after the app is open
- Report permission failures instead of improvising around them
- Stop when the task requires state-changing flows the user did not request

## Failure Handling

The skill should include explicit failure branches for:

- Electron app not running and launch fails
- Debugging endpoint missing or stale
- Renderer target not found
- macOS accessibility or AppleScript permission failures
- Workspace picker flow not completing
- Fixture creation failure
- DOM anchors not found

For each failure, the skill should instruct Codex to:

- report the exact failed step
- include the command or target that failed
- avoid claiming validation happened when the app was not actually driven

## Example V1 Use Cases

### Example 1: Manual UI Spike

“Prepare a disposable fixture, launch the app, open the fixture workspace, select text in the document panel, and capture console evidence.”

### Example 2: Renderer Inspection

“Attach to the running app and inspect the live DOM under `.document-panel article`.”

### Example 3: Evidence Capture

“Run the app against a known fixture and collect structured logs for a findings doc.”

## Worked Example Requirement

`SKILL.md` should include one concrete worked example based on the document excerpt locator spike:

- prepare a disposable workspace
- launch or attach to the app
- open the workspace
- attach via CDP
- drive a selection interaction
- capture structured console evidence

This example should demonstrate the building-block model rather than a giant end-to-end script.

## Why One Skill Instead Of Several

V1 should be one skill because:

- the repo currently has no `.agents/skills/` library to organize around
- these mechanics are tightly related in practice
- one skill is enough to establish conventions for future repo-local skills

If the library grows later, helpers can be split into narrower skills after usage patterns are clear.

## Validation Strategy For The Skill

The skill implementation should be validated by running at least one real local session that:

- creates a disposable fixture
- opens the live Electron app
- attaches through CDP
- captures structured evidence from the renderer

Validation succeeds when Codex can complete that flow without rediscovering repo-specific app control details from scratch.

## Implementation Guidance

The eventual implementation should favor:

- short shell and Node scripts over long prompt instructions for fragile mechanics
- explicit stdout outputs that chain well between tools
- deterministic defaults over excess configurability
- concise repo facts in `references/repo-facts.md`

The implementation should avoid:

- a single all-in-one “run every test scenario” script
- generic abstractions that hide repo-specific facts
- extra documentation files beyond the skill, scripts, and one compact reference file

## Acceptance Criteria

The design is satisfied when the repo-local skill can help Codex reliably:

1. prepare a disposable workspace fixture
2. launch or attach to the local Electron app
3. target the correct app window for this repo
4. open a workspace fixture
5. inspect or drive the renderer through CDP
6. capture structured evidence suitable for findings docs or validation reports

## Out Of Scope For V1

The following may be added later but should not shape the first implementation:

- packaged-app support
- CI or headless support
- generalized screenshot pipelines
- mutation-heavy editing workflows
- reusable abstractions for unrelated Electron repos
