# Material 3 dense sessions dashboard

Date: 2026-08-23

Goal: redesign the Android dashboard with a cohesive Material 3 visual system and substantially better session information density without sacrificing scanability.

## Audit and design

- [x] Audit the current Compose hierarchy, spacing, typography, color roles, controls, and active/recent session presentation.
- [x] Define a compact information hierarchy for connection state, provider counts, active work, progress, tokens, and recent sessions.
- [x] Preserve accessibility, readable touch targets, dynamic color behavior, and narrow-screen support.

## Implementation

- [x] Replace oversized cards and duplicated labels with compact Material 3 components and semantic color roles.
- [x] Add a concise overview header with connection/provider/session metrics.
- [x] Improve active session rows with source identity, elapsed time, model, activity, progress, and token data in fewer vertical pixels.
- [x] Improve recent session rows with compact metadata and clearer provider/repository distinction.
- [x] Consolidate server controls and simulation/debug actions into lower-priority surfaces.
- [x] Keep foreground-service, SSE, notification, and simulation behavior unchanged.

## Verification

- [x] Run all Android SDK, Gradle, emulator, screenshot, and device work only on `100.64.0.7`.
- [x] Build successfully on `100.64.0.7` and inspect idle and two-session rendered states there.
- [x] Correct the discovered system-bar overlap and rebuild until clean.
- [x] Copy the verified APK back to `claw-island-debug.apk` and record SHA-256 `52d6ec2fd302f7721a4d1cdcd98c4206f32a03c542f7a5a1fbdc72e2e94b1143`.
- [x] Review the final diff without disturbing unrelated work.
- [x] Move this plan to `docs/archive/` when every checkbox is complete.
