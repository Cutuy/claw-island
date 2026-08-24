# TypeScript server migration

Date: 2026-08-23

Goal: replace the Python bridge with a maintainable TypeScript service while preserving the production REST/SSE and Android contracts.

## Contract and structure

- [x] Capture representative OpenCode, Codex, lifecycle, heartbeat, and legacy endpoint fixtures.
- [x] Define runtime-validated provider-neutral contracts and inferred TypeScript types.
- [x] Split source adapters, aggregation, lifecycle reconciliation, event persistence, and HTTP transport into focused modules.

## Source parity

- [x] Port OpenCode read-only SQLite and process discovery behavior with regression coverage.
- [x] Port Codex read-only state/history discovery with active-turn semantics and fixtures.
- [x] Preserve stable `<source>:<source_id>` identities, deterministic merging, and graceful per-source failure.

## SSE and lifecycle parity

- [x] Preserve snapshot/delta heartbeat behavior, persistent monotonic sequence, replay cursor, and bounded event ring.
- [x] Preserve provider-neutral started/resumed/finished semantics; retain `session_exited` in the public event type for compatibility.
- [x] Ensure each client disconnect and server shutdown promptly releases timers, listeners, sockets, and database handles.

## HTTP compatibility

- [x] Preserve `/healthz`, `/api/status`, `POST /api/status`, `/api/events`, `/api/sessions`, `/api/sessions/events`, and `/api/sessions/events/latest`.
- [x] Add schema/unit tests and isolated-port integration smoke tests against the TypeScript process.
- [x] Confirm the existing Android contract decodes the unchanged TypeScript SSE payload shape.

## Deployment

- [x] Add reproducible package scripts, TypeScript config, lockfile, and production start command.
- [x] Update the systemd unit for the TypeScript entrypoint; README is updated.
- [x] Run tests, typecheck, dependency audit, and production build.
- [x] Restart `claw-island-bridge.service`; verify REST/SSE, Android connection, restart under 10 seconds, and journal cleanliness.
- [x] Retire the Python implementation to `server-python-legacy/` only after production parity is proven.
- [x] Review the final diff without disturbing unrelated user work.
- [x] Move this plan to `docs/archive/` when every checkbox is complete.
