# Session plan checklists

**Status:** complete
**Date:** 2026-08-23

## Goal

Expose each active Codex or OpenCode session's structured working plan, including full human-readable step names and statuses, so Android and future macOS clients can show meaningful overall progress rather than only the latest activity.

## Existing behavior

- OpenCode persists structured todos in its SQLite `todo` table; the bridge already maps them into `todo.items`.
- Codex currently emits `todo: emptyTodo()` even though its rollout JSONL records contain `update_plan` calls.
- Android shows aggregate progress on session cards and already has a basic named-item list in session details.
- Recent sessions intentionally omit todo item text.

## Contract and mobile-data budget

- Reuse the existing `todo` contract: `total`, `completed`, `in_progress`, `pending`, and `items[]` with `content`, `status`, and `priority`.
- Send named items only for active sessions. Recent sessions retain counts only.
- Cap transmitted items at 12 per active session and normalize each step to at most 160 Unicode code points after whitespace cleanup.
- Preserve counts for the complete source plan even if transmitted item text is capped.
- Do not add plan data to `delta: false` heartbeats; unchanged heartbeats remain compact.
- Plan changes participate in the existing snapshot hash and ten-second coalescing policy.

## Implementation

- [x] Add shared plan normalization limits/helpers and apply them to OpenCode todo output.
- [x] Add a cached Codex rollout-plan reader keyed by rollout path and appended file size.
- [x] Parse the latest complete `tools.update_plan(...)` payload defensively; tolerate absent, malformed, or changed internal formats.
- [x] Normalize Codex statuses to `completed`, `in_progress`, or `pending`, using neutral priority.
- [x] Add focused tests for extraction, latest-plan replacement, malformed input, truncation, and cache refresh.
- [x] Improve Android session-detail checklist styling: checked completed items, highlighted active item, muted pending items, wrapped full names.
- [x] Verify TypeScript tests/typecheck/build and measure full-snapshot versus heartbeat payload size.
- [x] Build Android on the configured remote SDK workstation and copy the verified APK back.

## Acceptance criteria

- Active OpenCode and Codex sessions expose named checklist items under the same wire contract.
- Codex's latest plan replaces earlier plans and retains full useful step names within the mobile bound.
- Android displays a readable status-marked checklist when items exist and safely shows counts if item text is absent.
- A second SSE client does not alter plan state or increase source scanning beyond cached file checks.
- Steady `delta: false` messages remain approximately their current size.

## Verification

- Live Codex rollout: latest five-step plan extracted with full names and correct `1 completed / 1 in progress / 3 pending` state.
- Server: 8 Vitest tests pass; `npm run check` and `npm run build` pass.
- Data measurement with one active five-step Codex plan: full snapshot 10,993 bytes; plan-name overhead 528 bytes; `delta: false` heartbeat 70 bytes.
- Android: remote `assembleDebug` succeeds on `100.64.0.7`.
- APK: `claw-island-debug.apk`, SHA-256 `e31521033aa9955fd5cf400db873ad9530593c816c2af84bdba771ad74c346c2`.
- Follow-up (2026-08-23): Codex began serializing plan item property names
  as JSON (`"step"` / `"status"`). The parser now accepts quoted and unquoted
  keys. A live update was observed through `/api/sessions/events` as
  `5 completed / 1 in progress / 0 pending`.
