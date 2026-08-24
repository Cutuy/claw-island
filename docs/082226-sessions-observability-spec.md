# Claw Island v2 — OpenCode Sessions Observability + Persistent Live Notifications

**Document ID:** `082226-sessions-observability-spec.md`
**Date:** 2026-08-22
**Status:** Spec (to be implemented by opencode, model opencode-go/glm-5.3)
**Target:** vivo (OriginOS 5+/Android 16) + Pixel/AOSP fallback
**Server host:** cutuy-claw (gateway), port 18818, systemd user unit `claw-island-bridge.service`

---

## 1. Problem

The system routinely runs **multiple long opencode sessions in parallel** (robinhood-engine, pyxen, jira-dashboard, claw-island...) with zero observability. The current Claw Island bridge only exposes a **single aggregated status** (first opencode proc found). Jason wants:

1. A serious app showing **active + recent opencode sessions**.
2. Per active session: **what it's working on** (title + last activity text), **elapsed time**, **todo doc** (todo list with statuses), **token usage** (in/out/reasoning/cache/cost), model, repo.
3. **Real-time updates via SSE** (server push every ~2s).
4. **Persistent live notifications in the Android notification center** — an ongoing notification that stays and updates its content in real time (not just the atomic island capsule).

---

## 2. Data Sources (server side, already verified)

### 2.1 Global opencode SQLite DB — `~/.local/share/opencode/opencode.db`
Source of truth for ALL sessions (project-local `.opencode-data/opencode.db` files are legacy/test-only — ignore them). Newer schema:

- **`session`**: `id`, `project_id`, `parent_id`, `slug`, `directory` (repo absolute path), `path`, `title`, `version`, `metadata`, `cost REAL`, `tokens_input`, `tokens_output`, `tokens_reasoning`, `tokens_cache_read`, `tokens_cache_write`, `agent`, `model` (JSON string like `{"id":"glm-5.3","providerID":"opencode-go","variant":"default"}`), `time_created` (ms epoch), `time_updated` (ms epoch), `time_archived`.
- **`todo`**: `session_id`, `content`, `status` ('pending'|'in_progress'|'completed'), `priority`, `position`. PK (session_id, position). This is the **todo doc**.
- **`message`**: `id`, `session_id`, `time_created`, `data` (JSON: role, modelID, path.cwd, tokens, cost...).
- **`part`**: `id`, `message_id`, `session_id`, `time_created`, `data` (JSON: `{"type":"text","text":"..."}`, `{"type":"reasoning",...}`, `{"type":"tool",...}`, `{"type":"step-start"}`, `{"type":"step-finish","tokens":{...}}`...).

NOTE: `session` has NO message_count column in this schema — count via `message` table.

### 2.2 Live processes
- `ps -eo pid,etimes,cmd` filtered for `opencode` (exclude grep/python/server.py as the current code does).
- For each pid, read `/proc/<pid>/cwd` (resolve symlink) → gives the repo directory the opencode process is running in → join with `session.directory` to identify the ACTIVE session(s) and compute exact elapsed time.

### 2.3 Existing server (server/server.py, FastAPI, port 18818)
- `GET /api/status` + `POST /api/status` (manual override, 30s TTL) + `GET /api/events` (SSE, 2s tick) — single aggregated status. **Keep these endpoints working** (backward compat; the capsule path may still use them). Add new endpoints alongside.

---

## 3. Server API Design (implement in `server/server.py`)

### 3.1 `GET /api/sessions` — full observability payload

```json
{
  "now_ts": 1787459000000,
  "active": [ {Session} ],
  "recent": [ {Session} ]   // last 20 by time_updated desc, excluding active ones
}
```

`Session` object:
```json
{
  "id": "ses_fd34f131cffeWOTL0DTY6SlmcW",
  "title": "Event-driven task unification proposal",
  "repo": "robinhood-engine",            // basename of session.directory
  "directory": "/home/cutuy/.openclaw/workspace/robinhood-engine",
  "agent": "build",
  "model": "glm-5.3",                    // parsed from session.model JSON
  "is_active": true,
  "elapsed_seconds": 402,                // active only: from ps etimes
  "formatted_time": "06:42",             // HH:MM:SS or MM:SS (reuse format_duration)
  "message_count": 23,                   // COUNT(*) from message
  "tokens_input": 55929,
  "tokens_output": 296,
  "tokens_reasoning": 52,
  "tokens_cache_read": 30848,
  "tokens_cache_write": 0,
  "cost": 0.0919,
  "created_at": 1787452587000,
  "updated_at": 1787452816000,
  "last_activity": "Read everything: event.rs, engine.rs...",  // latest text part, truncated ~240 chars
  "todo": {
    "total": 8,
    "completed": 3,
    "in_progress": 1,
    "pending": 4,
    "items": [
      {"content": "N0: add chrono-tz dep to Cargo.toml", "status": "completed", "priority": "high"},
      {"content": "N1: broker.rs — Quote struct...", "status": "in_progress", "priority": "high"}
    ]
  }
}
```

Rules:
- **Active detection**: for each live opencode pid, read `/proc/<pid>/cwd`; the session whose `directory` matches that cwd is ACTIVE. If multiple procs map to the same session, use the earliest proc start for elapsed. If a pid's cwd doesn't match any session row, fall back to matching the session whose `time_updated` is most recent for that directory prefix (and still mark active). Keep `proc_count` per active session if cheap, else skip.
- **last_activity**: query `part` table for the session, newest `time_created` first, find first part with `data.type == "text"` and non-empty text; strip whitespace/newlines, truncate to ~240 chars. Fallback: session title.
- **todo**: from `todo` table, ordered by `position`.
- **recent**: `SELECT ... FROM session ORDER BY time_updated DESC LIMIT 30` minus active ids, then cap to 20. Include the same fields (elapsed_seconds 0).
- **DB access**: open read-only (`sqlite3` URI `file:...?mode=ro`) to avoid lock contention with live opencode writers; WAL mode is on, so reads are cheap. Cache the connection per request or use short-lived connections; handle `sqlite3.OperationalError: database is locked` gracefully (try/except → return cached last payload).
- Performance: 2s polling of a handful of sessions is trivial. Use one query for sessions, one for todos (IN clause), one for latest text parts (per active session or windowed query).

### 3.2 `GET /api/sessions/events` — SSE stream
Same shape as `/api/sessions`, streamed every 2 seconds (mirror the existing `/api/events` generator pattern: `data: {json}\n\n`, Cache-Control no-cache, X-Accel-Buffering no). This is the endpoint the Android app will consume.

### 3.3 Misc
- Keep `/api/status`, `/api/events`, `/healthz` untouched.
- Bump app version string in FastAPI title to "Claw Island Status Bridge" v2.0.0.
- The manual `POST /api/status` override should still work for sim testing; it only affects the legacy status endpoint.

---

## 4. Android Design

Package: `com.cutuy.clawisland`. Kotlin 2.1, Compose, kotlinx.serialization, OkHttp (already deps).

### 4.1 New models — `models/SessionModels.kt`
```kotlin
@Serializable data class TodoItem(
    @SerialName("content") val content: String = "",
    @SerialName("status") val status: String = "pending",  // pending | in_progress | completed
    @SerialName("priority") val priority: String = "medium"
)
@Serializable data class TodoStats(val total: Int=0, val completed: Int=0, val inProgress: Int=0, val pending: Int=0, val items: List<TodoItem> = emptyList())
@Serializable data class SessionInfo(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "Unknown",
    @SerialName("repo") val repo: String = "",
    @SerialName("directory") val directory: String = "",
    @SerialName("agent") val agent: String = "build",
    @SerialName("model") val model: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int = 0,
    @SerialName("formatted_time") val formattedTime: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("tokens_input") val tokensInput: Long = 0,
    @SerialName("tokens_output") val tokensOutput: Long = 0,
    @SerialName("tokens_reasoning") val tokensReasoning: Long = 0,
    @SerialName("tokens_cache_read") val tokensCacheRead: Long = 0,
    @SerialName("tokens_cache_write") val tokensCacheWrite: Long = 0,
    @SerialName("cost") val cost: Double = 0.0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("last_activity") val lastActivity: String = "",
    @SerialName("todo") val todo: TodoStats = TodoStats()
)
@Serializable data class SessionsPayload(
    @SerialName("now_ts") val nowTs: Long = 0,
    @SerialName("active") val active: List<SessionInfo> = emptyList(),
    @SerialName("recent") val recent: List<SessionInfo> = emptyList()
) {
    val anyActive: Boolean get() = active.isNotEmpty()
}
```
Keep existing `AgentStatus` model for legacy /api/events (or remove its usage if fully switched — prefer keeping the file, it's used by sim).

### 4.2 Shared live store — `ClawIslandStore` (object, in `service/` or root)
```kotlin
object ClawIslandStore {
    val payload = MutableStateFlow(SessionsPayload())
    val connected = MutableStateFlow(false)
}
```
Service writes into it on each SSE event; MainActivity collects it with `collectAsState()`. No AndroidViewModel ceremony needed.

### 4.3 Service changes — `service/ClawIslandService.kt`
- Switch SSE endpoint from `/api/events` → `/api/sessions/events`.
- On each event: decode `SessionsPayload`, `ClawIslandStore.payload.value = payload`, then call `updateNotification(payload)`.
- Notification: **persistent live notification in the notification center** — this is the core ask.
  - Keep the existing vivo vs pixel builder dispatch (VivoSuperXHelper.buildCapsuleNotification / PixelLiveUpdateHelper.buildLiveUpdateNotification) but change their input to derive from `SessionsPayload`. If the helpers' signature stays `AgentStatus`, add new methods `buildSessionsNotification(context, payload, isUpdate)` OR adapt — opencode's choice, keep both paths working.
  - `setOnlyAlertOnce(true)` + `setOngoing(true)` + `setCategory(CATEGORY_SERVICE)` + `setShowWhen(true)` with advancing `setWhen(System.currentTimeMillis())` so the notification center entry visibly lives.
  - Content mapping:
    - 0 active: title "All agents idle", text "Standing by", subtext "N sessions in last 24h".
    - 1 active: title `🔨 <repo> · <title>` (or `🧠`/`⚡` by... keep 🔨 for coding), text = lastActivity (truncated ~120 chars), subtext = `repo · model · ⏱ elapsed · in/out tokens`, plus **ProgressStyle** when todo.total>0: `setProgress(total, completed, false)` so the notification center shows a real progress bar; when no todo, indeterminate (`setProgress(0,0,true)`).
    - N active: title `<N> agents active`, text = first active's lastActivity, subtext = `repo1 · repo2 · ...` truncated, big text lists each active session one line: `🔨 repo — title (elapsed)`.
    - BigTextStyle for expanded view: include todo items with ✓/◌/● markers (completed/in_progress/pending), token usage line (`in 55.9k · out 296 · $0.09`).
  - **Throttle**: notification updates at most every ~10s on vivo (existing rule), but the in-app StateFlow updates on every SSE tick (2s). Keep a simple `lastNotifyAt` throttle; UI is never throttled.
  - The 30s heartbeat can stay but becomes a no-op if SSE is delivering (keep it as fallback liveness).
- Keep ACTION_SIMULATE working for testing (simulate a fake SessionsPayload with 1-2 fake active sessions).

### 4.4 UI — `MainActivity.kt` (Compose)
Redesign `PixelIslandDashboard` → **Sessions Dashboard**:
- Header: "Claw Island" + subtitle "OpenCode Sessions Monitor" + LIVE/IDLE pill (driven by `ClawIslandStore.connected` + payload.anyActive).
- **Active Sessions section** (if any): one card per active session:
  - Row: repo badge (chip) + model chip + elapsed time (ticking — compute from payload.nowTs + elapsed_seconds; format client-side every second with a `LaunchedEffect` ticker).
  - Title (bold), lastActivity (2 lines, ellipsis).
  - Todo progress: `LinearProgressIndicator(progress = completed/total)` + `✓ 3/8 · ● 1 in progress`.
  - Token line: `in 55.9k · out 296 · cache 30.8k · $0.09` (format k/M, cost 2-4 decimals).
- **Recent Sessions section**: compact rows (title, repo chip, model, time-ago from updatedAt), maybe clickable → expands detail (or just static; keep simple).
- Keep: Server URL field, start/stop service button, sim test buttons (rework sim payloads to SessionsPayload shape).
- Dark theme, current palette is fine. Keep the hero preview card but drive it from payload (or drop it — opencode's call, prefer dropping to reduce clutter; keep if trivial).

### 4.5 Vivo/Pixel helpers
- `VivoSuperXHelper` / `PixelLiveUpdateHelper`: add overloads/adapters that accept `SessionsPayload` and produce the same notification style as before (capsule / live update), but with sessions-derived content. The atomic island capsule should still show for the top active session. Do NOT break the existing channel creation logic.
- `StandardNotificationHelper`: same adaptation.

---

## 5. Build & Verify Runbook (follow exactly)

Build machine: `cutuy@100.64.0.7` (Android SDK 36, Gradle 9.6.1). The gateway (this machine) has NO Android SDK — do NOT try to build locally.

1. Edit source files under `android/app/src/main/java/com/cutuy/clawisland/`.
2. Sync **specific files** to workstation (⚠️ NEVER rsync two source dirs into a bare target dir — basename collisions cause Kotlin `Redeclaration` errors; rsync file-by-file or to exact paths):
   ```bash
   rsync -avz android/app/src/main/java/com/cutuy/clawisland/ cutuy@100.64.0.7:/home/cutuy/claw-island/android/app/src/main/java/com/cutuy/clawisland/
   rsync -avz android/app/src/main/AndroidManifest.xml cutuy@100.64.0.7:/home/cutuy/claw-island/android/app/src/main/AndroidManifest.xml
   ```
3. Build:
   ```bash
   ssh cutuy@100.64.0.7 "cd /home/cutuy/claw-island/android && ./gradlew assembleDebug"
   ```
4. Copy APK back:
   ```bash
   scp cutuy@100.64.0.7:/home/cutuy/claw-island/android/app/build/outputs/apk/debug/app-debug.apk ./claw-island-debug.apk
   cp claw-island-debug.apk /home/cutuy/.openclaw/workspace/claw-island/public/downloads/   # only if that dir exists
   ```
5. Server changes: edit `server/server.py`, then restart the systemd user unit:
   ```bash
   systemctl --user restart claw-island-bridge.service
   ```
   ⚠️ Only after all edits are done. Verify: `curl -s http://127.0.0.1:18818/api/sessions | python3 -m json.tool | head -60` and `curl -sN --max-time 5 http://127.0.0.1:18818/api/sessions/events | head -5`.
6. If gradle build fails, fix and retry until BUILD SUCCESSFUL. Do not skip the build step — the APK must be rebuilt.
7. Do NOT git commit unless the task says so (Jason commits).

---

## 6. Acceptance Criteria

- [ ] `GET /api/sessions` returns active + recent sessions with all fields above; correct active detection against live opencode procs (verify by running the endpoint while an opencode session runs — there is one running now in robinhood-engine, "Event-driven task unification proposal", glm-5.3).
- [ ] `GET /api/sessions/events` streams the payload every ~2s.
- [ ] Legacy `/api/status` + `/api/events` still work.
- [ ] Android app builds (BUILD SUCCESSFUL on 100.64.0.7), APK copied back to `claw-island/claw-island-debug.apk`.
- [ ] Notification center shows a persistent ongoing notification that updates content in real time (title/text/progress) while a session is active, and settles to "idle" when none are.
- [ ] App UI shows active + recent sessions with todo progress, tokens, elapsed, last activity.

## 7. Out of Scope
- OpenClaw subagent session tracking (legacy /api/status still covers the single "Subagent" line).
- Multi-device / auth.
- App store packaging.
