# macOS menu-bar client

**Status:** primary review findings addressed; Mac-host verification pending
**Date:** 2026-08-23

## Goal

Add a native macOS 14+ menu-bar client for the Claw Island bridge. Keep SSE as
the live transport, persist the lifecycle cursor across sleep and process
restarts, and avoid an APNs dependency while the menu-bar app remains resident.

## Imported implementation

- [x] Swift Package executable and application metadata.
- [x] Native menu-bar dashboard with active, recent, and lifecycle-event panes.
- [x] Session details with full named plan checklist items.
- [x] SSE parser, cursor replay, reconnect controls, and diagnostics.
- [x] Native lifecycle notifications with deduplication and burst summaries.
- [x] Stable per-session notification request/thread identifiers, with event-ID
  fallback for malformed lifecycle events.
- [x] Configurable gateway, preview fixtures, and launch-at-login support.
- [x] Review source for repository safety and server-contract compatibility.
- [ ] Run the macOS self-test and package build on the Mac build host.
- [x] Confirm generated build products and local settings remain ignored.
- [x] Commit the macOS client separately from server and Android work.

## Server expectations

- Consume `/api/sessions/events` and persist `max_seq` for reconnect via
  `?after=<seq>`.
- Treat `delta: false` as a heartbeat and retain the last full snapshot.
- Decode `todo.items` for active sessions; recent sessions may contain counts
  with an empty item list.
- No server-side APNs work is required for the first version.

## Acceptance criteria

- Self-tests pass on macOS 14 or newer.
- The packaged app launches as a menu-bar application.
- The default gateway remains configurable and no credentials are committed.
- The source is landed in a dedicated macOS commit.

## Verification record

- Source review confirms the current snake-case wire contract, compact
  heartbeat handling, named plan items, persistent cursor, and notification
  deduplication are represented.
- Per-session notification grouping reads the current nested `session.id` and
  also tolerates a future top-level `session_id` field.
- Credential scan found no embedded keys or tokens.
- `100.64.0.3` was unreachable and the gateway has no Swift toolchain, so the
  self-test and `.app` packaging checks remain pending rather than assumed.
