# Claw Island v2.1 — Session Lifecycle Event Notifications

**Date:** 2026-08-22
**Status:** Spec — implement via opencode (opencode-go/glm-5.3), resume existing session `ses_fd342f394ffeqTMmQGuw2jK5bK`
**Context:** v2.0 shipped (sessions dashboard + persistent live notification). Jason now wants: **whenever an opencode session is started / resumed / finished / exited, the Android app shows a notification** — a distinct, one-shot event notification (not the persistent ongoing one).

---

## 1. Event taxonomy (server-side detection)

The bridge polls every 2s (existing tick). It must diff state between ticks and emit events:

| Event | Detection |
|---|---|
| `session_started` | A session row with `time_created` newer than the previous tick (new session in DB) OR a live opencode proc whose cmdline has NO `--session` flag (fresh `opencode run`) whose session row is new |
| `session_resumed` | A live opencode proc whose cmdline **contains `--session <id>`** (resumed via `opencode run --session`) AND that session wasn't active in the previous tick |
| `session_finished` | A session that WAS active in the previous tick is no longer active AND its latest `part` data has a `{"type":"finish","data":{"reason":"stop",...}}` (clean completion) |
| `session_exited` | Same transition (active→inactive) but the latest finish part reason is `canceled` / `error` / anything else, or no finish part exists at all (crashed/killed) |

Notes:
- A session can go active→inactive without a process exit being observable directly (proc cwd/cmdline join) — use the DB finish part as the source of truth for finished-vs-exited.
- Multiple sessions may emit events in one tick (e.g., 2 procs start simultaneously) — emit one event per session.
- Don't emit duplicate `session_started` for the same session id twice (keep a per-run emitted set keyed by session id + event type; a session that is started then later resumed emits start then resume, never two starts).
- `session_finished`/`session_exited` events carry the session's total elapsed (from proc etimes or `time_updated - time_created`).

## 2. Server API changes (`server/server.py`)

### 2.1 Event payload shape
```json
{
  "id": "evt_<uuid>",
  "seq": 42,
  "type": "session_started",          // session_started | session_resumed | session_finished | session_exited
  "ts": 1787460000000,
  "session": {
    "id": "ses_...",
    "repo": "robinhood-engine",
    "title": "Feature B N3-N9",
    "model": "glm-5.3",
    "elapsed_seconds": 1873           // for finished/exited; 0 otherwise
  }
}
```

### 2.2 SSE stream (`GET /api/sessions/events`)
- Add `events: [...]` to each tick payload (same JSON envelope as today: `now_ts`, `active[]`, `recent[]`, plus new `events[]`).
- **Per-connection cursor**: each SSE connection gets its own cursor. On connect, client may pass `?after=<seq>`; if absent, cursor starts at current seq and only events occurring *during the connection* are delivered (no backfill spam). `seq` is a monotonically increasing server counter (in-memory is fine; persists across restarts via a JSON file or DB table — in-memory acceptable, note the limitation).
- Event emission is idempotent per (session_id, type) within a rolling window (avoid dupes when ticks overlap).

### 2.3 REST
- `GET /api/sessions/events/latest?limit=20` — returns the most recent lifecycle events (for debugging / future UI). Simple in-memory ring buffer.

## 3. Android changes

### 3.1 Models (`SessionModels.kt`)
Add:
```kotlin
@Serializable data class SessionEvent(
    @SerialName("id") val id: String = "",
    @SerialName("seq") val seq: Long = 0,
    @SerialName("type") val type: String = "session_started",
    @SerialName("ts") val ts: Long = 0,
    @SerialName("session") val session: SessionInfo? = null   // reuse SessionInfo (elapsed_seconds populated for finished/exited)
)
```
And add `@SerialName("events") val events: List<SessionEvent> = emptyList()` to `SessionsPayload`.

### 3.2 New notification channel + event notifications
- New channel: `"session_events"` — name "Session Events", importance HIGH, distinct from the existing status channel (user can mute separately).
- In `ClawIslandService`, after decoding each SSE payload: for each event in `payload.events`, post a **one-shot notification** (NOT the ongoing one):
  - `setAutoCancel(true)`, `setOnlyAlertOnce(true)`, no ongoing flag, `setWhen(evt.ts)`.
  - Content mapping:
    - `session_started` → title `🆕 Session started · <repo>`, text `<title> (<model>)`
    - `session_resumed` → title `▶️ Session resumed · <repo>`, text `<title> (<model>)`
    - `session_finished` → title `✅ Session finished · <repo>`, text `<title> · ⏱ <elapsed>`
    - `session_exited` → title `⚠️ Session exited · <repo>`, text `<title> · ⏱ <elapsed>` (note: "exited" covers canceled/crash)
  - Use the standard `NotificationCompat.Builder` style already used in the project (or `StandardNotificationHelper`-style builder). Must work on Pixel (Jason's device) AND vivo (island device) — no SuperX extras needed for these event notifications (plain notification center pings), but don't break the vivo path.
  - Dedupe by event id: keep a small `HashSet`/LRU of recent event ids (last ~50) so reconnects/redeliveries never double-notify.
  - Throttle: if >5 events arrive in one tick, collapse into a single summary notification ("5 sessions finished · ...") to avoid notification spam.

### 3.3 ClawIslandStore
- Add `val events = MutableStateFlow<List<SessionEvent>>(emptyList())` (ring buffer, keep last 20) — service appends on each tick; MainActivity may render a small "Recent events" section (optional, cheap — do it if trivial; the core ask is notifications).

### 3.4 Service SSE connect
- `connectToSse`: pass `?after=<lastSeq>` if the service persisted the last seen seq (keep it in memory per service lifetime; optional SharedPreferences persistence — in-memory is fine).

## 4. Build & verify (same runbook as before)
1. Edit server + android sources.
2. `systemctl --user restart claw-island-bridge.service`; verify:
   - `curl -sN http://127.0.0.1:18818/api/sessions/events | head` shows `events: []` in ticks
   - Start an opencode session on the gateway (e.g., `cd /home/cutuy/.openclaw/workspace/claw-island && ./opencode.sh run --model opencode-go/glm-5.3 "hello"` in background) → observe a `session_started` event in the SSE stream; let it finish → observe `session_finished`.
   - `curl http://127.0.0.1:18818/api/sessions/events/latest`
3. Android: rsync to `cutuy@100.64.0.7`, `./gradlew assembleDebug` → BUILD SUCCESSFUL, scp `app-debug.apk` back to `./claw-island-debug.apk`.
4. (Emulator `testpixel` still exists on 100.64.0.7 — optional: verify event notification appears via `adb shell dumpsys notification`.)
5. Do NOT git commit.

## 5. Out of scope
- Event persistence across server restarts (in-memory ring buffer OK).
- Push notifications when the app is force-stopped (foreground service model unchanged).

---

## 6. v2.2 — Session card staleness indicator (Jason 2026-08-22 22:20)

**Requirement:** each session card in the app should show "last updated X seconds ago" when the session hasn't been updated for a while. Use Material 3 design UI.

Implementation notes:
- **Active session cards** (`ActiveSessionCard` in MainActivity.kt): compute staleness as `nowMs - session.updatedAt` (client clock, same 1s ticker that drives elapsed). If stale (> 30s), show a small line like `🕐 last updated 42s ago` (seconds granularity: `Xs ago` for < 60s, then `Xm ago`, `Xh ago`) in a muted/warning tone (e.g., `tertiary` or a soft amber derived from M3 color roles — no hardcoded colors outside the M3 palette). Elapsed timer keeps ticking regardless.
- **Recent session rows** (`RecentSessionRow`): already show time-ago; upgrade to seconds precision (`12s ago` for < 60s) and keep M3 styling.
- **Material 3 only**: the project already uses material3 1.3.1 (verified). Use standard M3 components and tokens (Card/ElevatedCard, LinearProgressIndicator, AssistChip or Surface chips, onSurfaceVariant/tertiary). No new design system, no custom XML drawables for this.
- No server changes needed (updatedAt already in the payload).
- Rebuild APK on 100.64.0.7 per the standard runbook, verify on the `testpixel` emulator if available.
