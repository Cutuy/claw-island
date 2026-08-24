# Claw Island session tracking architecture

Date: 2026-08-23

Goal: make Claw Island a reliable, extensible live-session observability bridge for OpenCode and Codex, with lifecycle changes delivered to Android over SSE.

## Repository ownership

- [x] Confirm `claw-island` is a dedicated Git repository and preserve its existing history and uncommitted work.
- [x] Remove the `claw-island` gitlink from the parent workspace repository index and ignore `/claw-island/` there.
- [x] Verify the child repository remains intact and the parent no longer reports child changes.

## Existing implementation audit

- [x] Inventory server, API/SSE contracts, OpenCode discovery, Android state flow, notification paths, and existing documentation.
- [x] Identify correctness bugs, lifecycle gaps, unsafe assumptions, performance issues, and extension barriers.
- [x] Add focused tests or fixtures that reproduce the important server-side failures before refactoring.

## Extensible server architecture

- [x] Define a provider-neutral session and lifecycle event contract with stable session identity and explicit source (`opencode` or `codex`).
- [x] Separate source adapters and provider-neutral aggregation from HTTP/SSE transport.
- [x] Preserve compatible existing REST/SSE endpoints where practical and document intentional contract changes.
- [x] Make SSE robust: initial snapshot, lifecycle/state changes, keepalive, disconnect cleanup, bounded resource usage, and reconnect-friendly event sequence cursors.
- [x] Handle missing/corrupt/locked local data sources without taking down the bridge.

## OpenCode tracking cleanup

- [x] Correctly discover current and recent OpenCode sessions from local process/database state, including rejecting shell commands that merely mention OpenCode.
- [x] Reconcile process state with persisted metadata without conflating multiple sessions in one repository.
- [x] Preserve status, lifecycle transitions, todos, token usage, and recent-session behavior through the normalized contract.

## Codex tracking

- [x] Discover the authoritative local Codex session/process data available on this machine.
- [x] Implement a Codex source adapter with current/recent sessions, status, metadata, and lifecycle changes where supported by the source data.
- [x] Merge Codex and OpenCode sessions deterministically without source-specific logic leaking into transport or Android presentation.
- [x] Add fixtures/tests for Codex discovery and mixed-provider reconciliation.

## Android integration

- [x] Update Android models/UI/service/notifications for the provider-neutral payload and mixed OpenCode/Codex sessions.
- [x] Preserve reconnect behavior, foreground-service requirements, and vendor notification fallbacks.
- [x] Perform all Android builds, emulator/device work, and SDK-dependent checks only on `100.64.0.7`.
- [x] Copy back the verified APK only after a successful remote build.

## Verification and handoff

- [x] Run server unit/integration tests and static checks locally.
- [x] Smoke-test legacy and provider-neutral REST/SSE endpoints on an isolated port, including cursor-bearing lifecycle payloads.
- [x] Build and verify Android on `100.64.0.7`.
- [x] Update README/API/architecture/runbook documentation to match the implementation.
- [x] Review the final diff without disturbing unrelated user changes.
- [x] With explicit approval, restart `claw-island-bridge.service` and verify the production port serves mixed OpenCode/Codex data.
- [x] When every checkbox is complete, move this file to `docs/archive/`.
