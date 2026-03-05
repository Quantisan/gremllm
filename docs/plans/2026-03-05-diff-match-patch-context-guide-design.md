# Diff-Match-Patch Context Guide Design

Date: 2026-03-05
Status: Approved

## Objective

Produce `context/diff-match-patch.md` as a self-contained, generic reference guide for `@sanity/diff-match-patch` pinned to upstream `v3.2.0`, with focused depth on immediate needs:

1. Long-document anchoring workflows
2. Sequential diff application and overlap behavior

The guide must remain neutral (no implementation recommendation), JS/TS-only, and under 1000 lines.

## Constraints

1. Generic-only reference material:
- No project-specific observations
- No local spike/proposal references
- No Gremllm-specific policy in the guide body

2. Source of truth:
- Upstream repository/tag `v3.2.0`
- Upstream README, CHANGELOG, exported types/source, and tests
- Optional usage examples from upstream Sanity repos only as neutral reference patterns

3. Scope and format:
- Self-contained enough for practical usage
- Use progressive disclosure for non-immediate details via precise pointers
- JS/TS examples only
- Keep under 1000 lines total

## Chosen Approach

Layered hybrid structure:

1. Quick API map for broad reference
2. Two deep-dive sections for immediate needs (anchoring and sequential behavior)
3. Appendices/pointers for less immediate concerns

This preserves general usability while ensuring depth where needed now.

## Planned Guide Outline

1. Purpose and Scope
2. Version Snapshot (`v3.2.0`)
3. API Map and Quick Lookup
4. Core Data Model (`Diff`, `Patch`, result shapes)
5. Deep Dive A: Long-Document Anchoring (neutral options)
6. Deep Dive B: Sequential/Overlapping Diffs (neutral options)
7. Unicode and Index Semantics
8. Serialization and Replay
9. Match Tuning (`threshold`, `distance`)
10. Gotchas and Verification Checklist
11. Progressive-Disclosure References (upstream links only)

## Content Policy

1. Accuracy priority:
- Every behavior claim should be traceable to upstream source/tests/changelog.

2. Neutral options framing:
- For anchoring and overlap sections, present multiple valid options and trade-offs.
- Avoid prescriptive language and avoid a “recommended strategy”.

3. Example policy:
- Examples should compile conceptually against `v3.2.0` exports.
- Prefer minimal snippets that expose behavior and edge cases.

## Quality Bar

The guide is complete when all are true:

1. `context/diff-match-patch.md` is under 1000 lines.
2. It contains no local project-specific observations.
3. Examples are JS/TS and match actual `v3.2.0` exports.
4. Anchoring and sequential overlap have neutral deep dives with options/trade-offs.
5. Progressive-disclosure pointers are upstream links only.
6. The document is readable standalone as context material.

## Delivery Workflow

1. Write `context/diff-match-patch.md` from upstream sources.
2. Verify size and spot-check API correctness against `dist/index.d.ts`/`src/index.ts`.
3. Verify neutrality and remove any project-local references.
4. Final pass for clarity and progressive-disclosure link quality.
