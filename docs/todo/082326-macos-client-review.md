# macOS client review

**Status:** review complete; implementation follow-ups pending
**Date:** 2026-08-23

## Summary

The native SwiftUI menu-bar client is structurally sound and matches the
current Claw Island session contract. The highest-value follow-ups are fixing
elapsed-time drift, retaining the compact SSE data model, and making delivered
notification replacement explicitly per-session.

## Findings

### 1. Fix elapsed-time drift

**Priority:** high

`DashboardView.swift` and `SessionDetailView.swift` add wall-clock time since
`session.updatedAt` to `elapsedSeconds`. This uses the wrong baseline:
`elapsedSeconds` is calculated at the snapshot's `now_ts`, while `updatedAt`
represents session activity. A quiet session can therefore appear much older
than it is.

- [ ] Pass `payload.nowTs` into active-session cards and detail views.
- [ ] Calculate live elapsed as `elapsedSeconds + max(0, now - payload.nowTs)`.
- [ ] Add a test where `updated_at` is old but `now_ts` is current.

Relevant code:

- `macos/Sources/ClawIslandMac/DashboardView.swift` (`liveElapsed`)
- `macos/Sources/ClawIslandMac/SessionDetailView.swift` (`liveElapsed`)

### 2. Preserve the compact SSE data model

**Priority:** high

`AppModel.applySSEData` currently calls `/api/sessions` after approximately
every two compact heartbeats. This causes a full REST snapshot roughly every
four seconds and defeats the bridge's compact-heartbeat design.

The SSE endpoint already emits a full snapshot whenever the content hash
changes. Compact `delta: false` messages should normally update connection
liveness only.

- [ ] Remove the four-second REST refresh from normal heartbeat handling.
- [ ] If desired, retain a slow safety reconciliation interval of one to five
  minutes.
- [ ] Verify that a Codex plan update arrives through SSE without manual
  reconnect or REST polling.

Relevant code:

- `macos/Sources/ClawIslandMac/AppModel.swift` (`applySSEData`)

### 3. Explicitly replace delivered notifications

**Priority:** high

The notification manager reuses a stable `UNNotificationRequest` identifier.
That reliably replaces a pending request, but already delivered Notification
Center entries should be removed explicitly before posting the replacement.

- [ ] Call `removeDeliveredNotifications(withIdentifiers: [id])` before adding
  the updated request.
- [ ] Preserve the stable per-session request and thread identifiers.
- [ ] Confirm a start followed by finish leaves one delivered row and that the
  finish event still alerts.

Relevant code:

- `macos/Sources/ClawIslandMac/NotificationManager.swift` (`post`)

### 4. Keep per-session updates during large replays

**Priority:** medium

When more than five events arrive together, `NotificationManager.deliver`
posts one summary and returns. Existing session rows are not updated, which
breaks the per-session invariant for replay batches.

Recommended behavior: coalesce the batch by session, post the newest event for
each session, and optionally add a summary when several distinct sessions were
updated.

- [ ] Replace the early summary return with per-session coalescing.
- [ ] Avoid alert storms when reconnecting after a long absence.
- [ ] Test multiple events for one session and events from multiple sessions.

Relevant code:

- `macos/Sources/ClawIslandMac/NotificationManager.swift` (`deliver`)

### 5. Move byte-level SSE parsing off the main actor

**Priority:** medium

`AppModel` is `@MainActor`, and `consumeSSE` iterates the URL stream one byte at
a time. It is acceptable at current sizes but can make the menu-bar UI less
responsive as full snapshots or named plans grow.

- [ ] Move transport and SSE parsing into a dedicated actor/background task.
- [ ] Publish decoded snapshots and events back to `MainActor` in batches.
- [ ] Preserve cancellation and the current reconnect/watchdog discipline.

Relevant code:

- `macos/Sources/ClawIslandMac/AppModel.swift` (`consumeSSE`)

## Additional polish

- [ ] Replace broad `NSAllowsArbitraryLoads` with the narrowest practical ATS
  policy for the configured Tailscale endpoint.
- [ ] Display notification authorization status in Settings and provide an
  **Open System Settings** action when permission is denied.
- [ ] Route notification clicks to the corresponding session detail view.
- [ ] Add behavioral tests for cursor reset, reconnect replay, event
  deduplication, and stable per-session notification keys.

## Verification limitation

The source and server-contract review was completed, but the native build and
package scripts were not rerun because the Mac host at `100.64.0.3` was
unreachable and the Linux gateway has no Swift toolchain. Run these when the
Mac is available:

```bash
cd macos
./scripts/self-test.sh
./scripts/package-app.sh
```
