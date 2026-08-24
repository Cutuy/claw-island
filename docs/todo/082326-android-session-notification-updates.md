# Android per-session notification updates

**Status:** complete
**Date:** 2026-08-23

## Goal

Keep at most one lifecycle notification item for each agent session while still
alerting the user whenever a genuinely new lifecycle event is delivered.

## Design

- Derive a stable notification ID from the full provider-qualified session ID.
- Use the identical identity function for SSE and FCM notification paths.
- Attach lifecycle notifications to a common Android notification group.
- Continue deduplicating transport overlap by lifecycle event ID.
- Do not enable `onlyAlertOnce`; replacing a session notification must still
  sound/vibrate for a new start, resume, finish, or exit event.
- Fall back to the event ID only when a malformed event lacks a session ID.

## Implementation

- [x] Add a shared notification identity/group helper.
- [x] Use stable session IDs in SSE lifecycle notification posting.
- [x] Use stable session IDs in FCM lifecycle notification posting.
- [x] Keep tap intents stable per session and update them with the latest event.
- [x] Add focused JVM tests for stable IDs, source isolation, and fallback.
- [x] Build the Android APK on `100.64.0.7` and copy it back.

## Acceptance criteria

- Repeated lifecycle events for one session replace the same notification row.
- Different sessions retain independent notification rows.
- Every new non-duplicate lifecycle event remains eligible to alert.
- Receiving the same event over SSE and FCM does not alert twice.

## Verification record

- Three JVM identity tests pass on the Android build workstation.
- `testDebugUnitTest` and Firebase-configured `assembleDebug` succeed on
  `100.64.0.7`.
- APK: `claw-island-debug.apk`, SHA-256
  `4b77bbf5c0ca3268fd1bc95d3cb5b9f9fe777391c6c1d1d21484ad90deacf577`.
- SSE and FCM both post with the same stable ID and group key, while
  `onlyAlertOnce` is disabled for new lifecycle updates.
- Device validation: confirmed by the user that Android now keeps one row per
  session and updates/alerts as expected.
