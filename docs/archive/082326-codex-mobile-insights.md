# Codex mobile insights with bounded data usage

Date: 2026-08-23

Goal: surface a tasteful set of additional Codex execution context in Android while keeping mobile SSE traffic approximately flat or lower.

## Baseline and contract

- [x] Record the production REST/full-SSE payload baseline: approximately 18 KB with two active and twenty recent sessions.
- [x] Select high-signal active-only fields and define optional, backward-compatible wire fields.
- [x] Add payload-size regression coverage and verify steady heartbeat size in production.

## Server

- [x] Derive active Codex Git branch, current phase, last tool/action, and reasoning effort when available.
- [x] Avoid transmitting commands, diffs, reasoning content, or other verbose/sensitive raw records.
- [x] Compact recent-session serialization and omit default/redundant fields from the wire payload.
- [x] Ensure full snapshots are capped to one per ten seconds while unchanged ticks use small delta heartbeats; lifecycle events bypass the cap.

## Android

- [x] Extend serialization models with optional/defaulted Codex insight fields.
- [x] Present branch, phase, and last action as compact secondary metadata on active Codex cards.
- [x] Fit the insight metadata into one compact secondary line.
- [x] Preserve OpenCode presentation and notification compatibility.

## Verification

- [x] Run four TypeScript tests, typecheck, build, API/SSE smoke tests, and compare final byte counts with baseline.
- [x] Restart the bridge and verify production mixed-provider payloads.
- [x] Build/install/inspect the live-data Android UI only on `100.64.0.7`.
- [x] Copy the verified APK back with SHA-256 `87758c82f5ab1db049186e8f64e8db841e9259a079a02482433ebfd90326cadd`.
- [x] Review the final diff and archive this plan when complete.
