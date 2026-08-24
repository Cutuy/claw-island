# Android durable events and FCM fallback

**Status:** implementation, Firebase provisioning, and device registration complete; live-delivery verification pending
**Date:** 2026-08-23

## Goal

Deliver session lifecycle notifications reliably to Android when possible without depending exclusively on a continuously open SSE socket. Keep SSE as the authoritative live-state transport and use Firebase Cloud Messaging only as a compact fallback for registered devices whose SSE connection is absent.

macOS/APNs is deferred. A resident menubar app will use SSE with a persisted replay cursor and reconnect after sleep/wake.

## Delivery model

1. The bridge detects and durably records a lifecycle event.
2. Active SSE clients receive the full event and acknowledge progress implicitly through their device-bound cursor.
3. Registered Android devices with an active SSE connection are not sent FCM, avoiding duplicate notifications.
4. Registered Android devices without active SSE receive a compact FCM notification containing only event/session identifiers and short display text.
5. On app/service recovery, Android reconnects with its persisted sequence cursor and refreshes the authoritative session snapshot.

FCM is best-effort wake/alert delivery, not the event ledger.

## Server

- [x] Give `EventStore.reconcile()` an emitted-event return value so delivery adapters consume the same durable events.
- [x] Add an atomically persisted Android device registry with device ID, FCM token, and update timestamp.
- [x] Add registration/unregistration endpoints with strict schema validation and optional pairing-token enforcement.
- [x] Bind SSE connections to `device_id` and track active connection counts per device.
- [x] Add an FCM HTTP v1 sender using service-account/ADC credentials from the environment; never store credentials in the repository.
- [x] Send minimal lifecycle notification payloads only to registered devices without active SSE.
- [x] Remove invalid/unregistered FCM tokens based on provider responses and isolate push failures from polling/SSE.
- [x] Add tests for device persistence, active-SSE suppression, delivery fan-out, invalid tokens, and missing configuration.

## Android

- [x] Persist a stable installation/device ID, the latest lifecycle sequence, dedupe IDs, monitoring-enabled state, and server URL.
- [x] Always identify the device on SSE connections and replay from the persisted cursor after process recreation.
- [x] Add a boot receiver that restores monitoring only when the user previously enabled it.
- [x] Add Firebase Messaging integration without committing `google-services.json` or secrets.
- [x] Register/refresh the FCM token with the bridge and unregister it when monitoring is explicitly disabled.
- [x] Handle data-only FCM messages on the existing Session Events channel in foreground or background.
- [x] Deduplicate SSE and FCM notifications by event ID.
- [x] Continue functioning normally when Firebase is unconfigured or Google Play services are unavailable.

## Configuration and security

- Firebase Android identifiers are supplied at build time and are not secrets.
- FCM service-account credentials remain outside the repository and are referenced through an environment path or Application Default Credentials.
- Device registration may require `CLAW_ISLAND_PUSH_PAIRING_TOKEN`; when configured, Android supplies the matching build-time value over the existing Tailscale path.
- Push payloads must not contain plan steps, prompts, paths, tokens, or detailed activity text.
- No service or gateway configuration is changed without explicit authorization.

## Data usage

- FCM is emitted only on lifecycle transitions and only while SSE is absent.
- Payload contains event ID/sequence/type, session ID/source/repo, and a short title.
- Existing two-second compact SSE heartbeats remain unchanged.
- Token registration occurs only on initial setup, token rotation, server change, or monitoring re-enable.

## Acceptance criteria

- A short SSE disconnect in the same process replays missed events without duplicates.
- Android process recreation retains its cursor and does not skip events that occurred while it was absent.
- A registered offline Android device receives an FCM lifecycle notification when Firebase is configured.
- An actively connected device receives the lifecycle notification through SSE but no duplicate FCM.
- Monitoring resumes after reboot only if the user had enabled it.
- Builds and tests remain green without Firebase credentials, with push reported as unconfigured rather than crashing.

## Verification record

- Server: 11 tests pass; TypeScript check and production build pass.
- Android: `assembleDebug` succeeds on `100.64.0.7` without Firebase configuration.
- Firebase-configured APK: `claw-island-debug.apk`, SHA-256 `702fa00dda221f83dfbcb7b6272348a391578fc9ac73cdae0d17e7a043ae0811`.
- Firebase Admin credential validated, restricted to mode `0600`, configured through the user systemd service, and `/healthz` reports push configured.
- Firebase-configured Android installation registered one device and opened an SSE stream with its stable `device_id`.
- Pending: exercise a real offline push without an active SSE connection.
