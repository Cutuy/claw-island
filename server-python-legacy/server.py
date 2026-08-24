import os
import re
import sys
import json
import time
import uuid
import hashlib
import asyncio
import sqlite3
import threading
import subprocess
from contextlib import asynccontextmanager
from typing import Dict, Any, Optional, List, Tuple
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
from session_sources import CodexSource, merge_snapshots

@asynccontextmanager
async def lifespan(_app: FastAPI):
    """Own the background reconciler so startup/shutdown cannot leak tasks."""
    async def ticker():
        while True:
            try:
                await asyncio.to_thread(tick_sessions)
            except asyncio.CancelledError:
                raise
            except Exception:
                # Source adapters degrade in their payload; an unexpected tick
                # failure must not kill future reconciliation attempts.
                pass
            await asyncio.sleep(2.0)

    with _tick_lock:
        _save_events_state()
    task = asyncio.create_task(ticker(), name="session-reconciler")
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

app = FastAPI(title="Claw Island Status Bridge", version="3.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

WORKSPACE_ROOT = os.path.expanduser("/home/cutuy/.openclaw/workspace")
CURRENT_ACTION_FILE = os.path.join(WORKSPACE_ROOT, "network-dashboard/current_action.md")
SUBAGENT_MONITOR_FILE = os.path.join(WORKSPACE_ROOT, "shared/subagent-monitor.json")
OPENCODE_DB = os.path.expanduser("~/.local/share/opencode/opencode.db")
CODEX_SOURCE = CodexSource()

# Process tracking cache: pid -> first_seen_timestamp
proc_start_times: Dict[int, float] = {}
custom_status: Optional[Dict[str, Any]] = None

# Sessions observability
SESSIONS_FETCH_LIMIT = 100
SESSIONS_RECENT_LIMIT = 20
ACTIVE_RECENCY_MS = 90_000  # session updated within this window counts as active
WORKSPACE_PATH_RE = re.compile(r"/home/cutuy/\.openclaw/workspace/[^\s\"'`\\|;&<>]+")
PROC_SESSION_RE = re.compile(r"(?:--session|-s)[= ]+(ses_[A-Za-z0-9]+)")
_sessions_payload_cache: Optional[Dict[str, Any]] = None

# Session lifecycle events (v2.1)
SESSION_STARTED = "session_started"
SESSION_RESUMED = "session_resumed"
SESSION_FINISHED = "session_finished"
SESSION_EXITED = "session_exited"
EVENT_RING_SIZE = 100
EVENTS_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".events-state.json")
EVENTS_PERSIST_KEEP = 50                # events kept in the on-disk state file
STARTED_FRESH_WINDOW_MS = 10 * 60_000   # new row older than this is not a "start"
FINISH_RECENT_WINDOW_MS = 10 * 60_000   # finish part older than this is from a prior run
FINISH_FAST_WINDOW_MS = 30_000          # fast-path: finish written within this window

_tick_lock = threading.Lock()
_last_tick_at = 0.0
_tick_payload_cache: Optional[Dict[str, Any]] = None
_tick_hash: Optional[str] = None

_event_seq = 0
_event_ring: List[Dict[str, Any]] = []
_codex_prev_active: Optional[Dict[str, Dict[str, Any]]] = None

def _load_events_state():
    """Restore seq + event ring from disk so a restart does not reset the
    sequence (clients persist their cursor across our restarts)."""
    global _event_seq, _event_ring
    try:
        with open(EVENTS_STATE_FILE, "r", encoding="utf-8") as f:
            state = json.load(f)
        seq = int(state.get("seq", 0))
        ring = [
            e for e in state.get("events", [])
            if isinstance(e, dict) and isinstance(e.get("seq"), int)
        ]
        ring = ring[-EVENT_RING_SIZE:]
        _event_seq = max(seq, max((e["seq"] for e in ring), default=0))
        _event_ring = ring
    except (FileNotFoundError, json.JSONDecodeError, ValueError, TypeError, OSError):
        pass

def _save_events_state():
    """Atomically persist seq + the last events (called under _tick_lock)."""
    try:
        state = {"seq": _event_seq, "events": _event_ring[-EVENTS_PERSIST_KEEP:]}
        tmp = EVENTS_STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False)
        os.replace(tmp, EVENTS_STATE_FILE)
    except OSError:
        pass

_load_events_state()

# Lifecycle diff state (per server run, in-memory)
_lifecycle: Dict[str, Any] = {
    "prev_active": None,       # sid -> {"by_proc": bool, "elapsed": int, "time_updated": int}
    "prev_known_ids": None,    # set of session ids seen last tick
    "prev_tick_ts": 0,
    "emitted_started": set(),  # sids that already got session_started
    "closed_ids": set(),       # sids that already got finished/exited this run
    "resumed_emitted": set(),  # sids that got session_resumed in the current streak
    "pending_started": {},     # sid -> first-seen ts (waiting for real title)
    "proc_seen": set(),        # sids that were proc-active during the current
                               # activity streak (sticky until they go inactive)
    "seen_updated": {},        # sid -> max time_updated ever observed (high-water
                               # mark; per-tick reads of the live WAL DB can be
                               # stale, so raw prev-tick comparison is unreliable)
}

def format_duration(seconds: int) -> str:
    """Format seconds into HH:MM:SS or MM:SS."""
    if seconds < 0:
        seconds = 0
    m, s = divmod(seconds, 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"

def get_opencode_process_details() -> list:
    """Check running opencode processes and get PID, elapsed seconds, command."""
    global proc_start_times
    procs = []
    try:
        res = subprocess.run(["ps", "-eo", "pid=,etimes=,comm=,args="], capture_output=True, text=True)
        current_pids = set()
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(maxsplit=3)
            if len(parts) < 4:
                continue
            pid_str, etimes_str, comm, cmd = parts
            executable = os.path.basename(cmd.split(maxsplit=1)[0]) if cmd else ""
            if is_opencode_process(comm, executable):
                try:
                    pid = int(pid_str)
                    etimes = int(etimes_str)
                    current_pids.add(pid)
                    procs.append({
                        "pid": pid,
                        "elapsed_seconds": etimes,
                        "cmd": cmd
                    })
                except ValueError:
                    pass
        # Clean stale pids
        proc_start_times = {p: t for p, t in proc_start_times.items() if p in current_pids}
    except Exception:
        pass
    return procs

def is_opencode_process(comm: str, executable: str) -> bool:
    """Accept the runtime itself, never a shell merely mentioning OpenCode."""
    names = {comm.lower(), executable.lower()}
    return any(name == "opencode" or name.startswith("opencode-") for name in names)

def read_file_safe(path: str, max_chars: int = 1000) -> str:
    try:
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                return f.read(max_chars).strip()
    except Exception:
        pass
    return ""

# ---------------------------------------------------------------------------
# Sessions observability (v2) — ~/.local/share/opencode/opencode.db + live procs
# ---------------------------------------------------------------------------

def get_opencode_proc_dirs() -> Tuple[Dict[str, Dict[str, Any]], Dict[str, Dict[str, Any]], List[int]]:
    """Map repo directories, --session ids and fresh-run starts from live procs.

    Returns (dir_map, proc_sessions, fresh_starts):
      dir_map:       repo dir -> {"elapsed_seconds": min etimes, "proc_count": n}
      proc_sessions: session id (from `--session/-s <id>` in cmdline) ->
                     {"elapsed_seconds": min etimes, "proc_count": n}
      fresh_starts:  start times (ms epoch) of `opencode run` procs WITHOUT a
                     --session flag — each creates a brand-new session seconds
                     after boot (matched by creation time downstream)

    /proc/<pid>/cwd is NOT readable from the systemd bridge service (kernel Yama
    ptrace_scope denies cross-process symlink reads), so we extract workspace
    paths from /proc/<pid>/cmdline instead (world-readable). Wrapper processes
    (bash -c 'cd <repo> && ./opencode.sh ...', tee <repo>/x.log) also carry the
    workspace path and are included — their start time is the earliest, which is
    what we want for elapsed. NOTE: unpiped `bash -c 'cd X && ./opencode.sh
    run ...'` exec-collapses into the bare `opencode run` proc, whose cmdline
    carries NO workspace path — those are matched via fresh_starts instead.
    """
    dir_map: Dict[str, Dict[str, Any]] = {}
    proc_sessions: Dict[str, Dict[str, Any]] = {}
    fresh_starts: List[int] = []
    for proc in get_opencode_process_details():
        pid = proc["pid"]
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                cmdline = f.read().decode("utf-8", errors="ignore").replace("\x00", " ")
        except Exception:
            continue
        candidates = set()
        for m in WORKSPACE_PATH_RE.finditer(cmdline):
            path = m.group(0).rstrip("/,.:")
            candidates.add(path)
            candidates.add(os.path.dirname(path))
        for d in candidates:
            # Skip the bare workspace root — too broad (matches every session);
            # the recency fallback covers anything we can't pin to a repo.
            if d == WORKSPACE_ROOT or not d.startswith(WORKSPACE_ROOT + "/"):
                continue
            entry = dir_map.setdefault(
                d, {"elapsed_seconds": proc["elapsed_seconds"], "proc_count": 0}
            )
            entry["elapsed_seconds"] = min(entry["elapsed_seconds"], proc["elapsed_seconds"])
            entry["proc_count"] += 1
        has_session_flag = bool(PROC_SESSION_RE.search(cmdline))
        for m in PROC_SESSION_RE.finditer(cmdline):
            sid = m.group(1)
            entry = proc_sessions.setdefault(
                sid, {"elapsed_seconds": proc["elapsed_seconds"], "proc_count": 0}
            )
            entry["elapsed_seconds"] = min(entry["elapsed_seconds"], proc["elapsed_seconds"])
            entry["proc_count"] += 1
        if not has_session_flag and re.search(r"opencode(\.sh)?\s+run", cmdline):
            fresh_starts.append(int(time.time() * 1000) - proc["elapsed_seconds"] * 1000)
    return dir_map, proc_sessions, fresh_starts

def _session_model_name(raw: Optional[str]) -> str:
    """Parse session.model JSON like {"id":"glm-5.3","providerID":...} -> 'glm-5.3'."""
    if not raw:
        return ""
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, dict) and parsed.get("id"):
            return str(parsed["id"])
    except Exception:
        pass
    return raw

def _last_activity_text(conn: sqlite3.Connection, session_id: str, fallback: str) -> str:
    """Latest text part for a session, whitespace-stripped, truncated to ~240 chars."""
    try:
        for (data,) in conn.execute(
            "SELECT data FROM part WHERE session_id = ? ORDER BY time_created DESC LIMIT 40",
            (session_id,),
        ):
            try:
                obj = json.loads(data)
            except Exception:
                continue
            if isinstance(obj, dict) and obj.get("type") == "text":
                text = str(obj.get("text") or "").strip()
                if text:
                    return " ".join(text.split())[:240]
    except sqlite3.OperationalError:
        pass
    return fallback

# ---------------------------------------------------------------------------
# Session lifecycle events (v2.1) — diff between ticks, emit start/resume/
# finish/exit events into an in-memory ring buffer with a monotonic seq.
# NOTE: the opencode DB stores the finish reason on the assistant MESSAGE
# (message.data.finish: "stop" | "tool-calls" | "unknown"), not as a part.
# ---------------------------------------------------------------------------

def _latest_finish(conn: sqlite3.Connection, session_id: str) -> Tuple[Optional[str], int]:
    """Latest finish reason + its time_created for a session (scans newest messages)."""
    try:
        for data, tc in conn.execute(
            "SELECT data, time_created FROM message WHERE session_id = ? "
            "ORDER BY time_created DESC LIMIT 10",
            (session_id,),
        ):
            try:
                obj = json.loads(data)
            except Exception:
                continue
            if isinstance(obj, dict) and obj.get("finish"):
                return str(obj["finish"]), tc
    except sqlite3.OperationalError:
        pass
    return None, 0

def _emit_event(evt_type: str, now_ts: int, r, elapsed: int) -> Dict[str, Any]:
    global _event_seq
    _event_seq += 1
    ev = {
        "id": f"evt_{uuid.uuid4().hex[:12]}",
        "seq": _event_seq,
        "type": evt_type,
        "ts": now_ts,
        "session": {
            "id": f"opencode:{r['id']}",
            "source_id": r["id"],
            "source": "opencode",
            "repo": os.path.basename(r["directory"]) or r["directory"],
            "title": r["title"],
            "model": _session_model_name(r["model"]),
            "elapsed_seconds": elapsed,
        },
    }
    _event_ring.append(ev)
    if len(_event_ring) > EVENT_RING_SIZE:
        del _event_ring[: len(_event_ring) - EVENT_RING_SIZE]
    _save_events_state()
    return ev

def _emit_normalized_event(evt_type: str, now_ts: int, session: Dict[str, Any]) -> Dict[str, Any]:
    """Emit an event from any normalized source adapter."""
    global _event_seq
    _event_seq += 1
    ev = {
        "id": f"evt_{uuid.uuid4().hex[:12]}",
        "seq": _event_seq,
        "type": evt_type,
        "ts": now_ts,
        "session": {
            key: session.get(key)
            for key in ("id", "source_id", "source", "repo", "title", "model", "elapsed_seconds")
        },
    }
    _event_ring.append(ev)
    if len(_event_ring) > EVENT_RING_SIZE:
        del _event_ring[: len(_event_ring) - EVENT_RING_SIZE]
    _save_events_state()
    return ev

def _detect_codex_lifecycle(now_ts: int, snapshot: Dict[str, Any]) -> None:
    """Reconcile Codex's authoritative in-progress turns across ticks."""
    global _codex_prev_active
    current = {item["id"]: item for item in snapshot.get("active", [])}
    if _codex_prev_active is None:
        _codex_prev_active = current
        return
    for sid in sorted(current.keys() - _codex_prev_active.keys()):
        item = current[sid]
        event_type = (
            SESSION_STARTED
            if now_ts - item.get("created_at", 0) <= STARTED_FRESH_WINDOW_MS
            else SESSION_RESUMED
        )
        _emit_normalized_event(event_type, now_ts, item)
    for sid in sorted(_codex_prev_active.keys() - current.keys()):
        item = dict(_codex_prev_active[sid])
        item["elapsed_seconds"] = max(
            item.get("elapsed_seconds", 0),
            (now_ts - item.get("updated_at", now_ts)) // 1000,
        )
        _emit_normalized_event(SESSION_FINISHED, now_ts, item)
    _codex_prev_active = current

def _detect_lifecycle_events(
    conn: sqlite3.Connection,
    now_ts: int,
    active: Dict[str, Dict[str, Any]],
    rows: List,
    proc_sessions: Dict[str, Dict[str, Any]],
) -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    known_ids = {r["id"] for r in rows}
    row_by_id = {r["id"]: r for r in rows}

    # First tick after (re)start: baseline only, no events.
    if _lifecycle["prev_known_ids"] is None:
        _lifecycle["prev_known_ids"] = known_ids
        _lifecycle["prev_active"] = {
            sid: {
                "by_proc": bool(e.get("by_proc")),
                "elapsed": e["elapsed"],
                "time_updated": e["row"]["time_updated"],
            }
            for sid, e in active.items()
        }
        _lifecycle["prev_tick_ts"] = now_ts
        return events

    prev_active: Dict[str, Dict[str, Any]] = _lifecycle["prev_active"]

    def get_row(sid: str):
        r = row_by_id.get(sid)
        if r is None:
            try:
                r = conn.execute(
                    """SELECT id, directory, title, agent, model, cost,
                              tokens_input, tokens_output, tokens_reasoning,
                              tokens_cache_read, tokens_cache_write,
                              time_created, time_updated
                       FROM session WHERE id = ?""",
                    (sid,),
                ).fetchone()
            except sqlite3.OperationalError:
                r = None
        return r

    # --- session_started: brand-new session row since the previous tick ---
    # Deferred until the real title lands (opencode initially writes a
    # "New session - <ts>" placeholder) or a 15s grace period expires.
    for sid in sorted(known_ids - _lifecycle["prev_known_ids"]):
        if sid in _lifecycle["emitted_started"] or sid in _lifecycle["pending_started"]:
            continue
        r = row_by_id.get(sid)
        if r is None:
            continue
        # Guard: an old session entering the recent-100 window for the first
        # time (updated from elsewhere) is not a fresh start.
        if now_ts - r["time_created"] > STARTED_FRESH_WINDOW_MS:
            continue
        _lifecycle["pending_started"][sid] = now_ts
    for sid, first_ts in list(_lifecycle["pending_started"].items()):
        r = row_by_id.get(sid)
        if r is None:
            if now_ts - first_ts > 60_000:
                del _lifecycle["pending_started"][sid]
            continue
        title_ready = not r["title"].startswith("New session")
        if title_ready or now_ts - first_ts > 15_000:
            events.append(_emit_event(SESSION_STARTED, now_ts, r, 0))
            _lifecycle["emitted_started"].add(sid)
            _lifecycle["closed_ids"].discard(sid)
            del _lifecycle["pending_started"][sid]

    # --- session_resumed: live proc with --session/-s <id> that wasn't active ---
    for sid in sorted(proc_sessions.keys()):
        if sid in prev_active or sid in _lifecycle["resumed_emitted"]:
            continue
        r = get_row(sid)
        if r is None:
            continue
        events.append(_emit_event(SESSION_RESUMED, now_ts, r, 0))
        _lifecycle["resumed_emitted"].add(sid)
        _lifecycle["closed_ids"].discard(sid)

    # High-water mark update + re-arm: a session that produced NEW output after
    # a closed event is alive again — allow a future finish/exit to notify
    # (e.g. resumed without --session). MUST run BEFORE the finish detection
    # below: with the previous tick's (possibly stale) mark, a session that
    # just finished this tick would look "newly updated" and get re-armed
    # immediately, causing a duplicate finished event at the recency expiry.
    # The monotonic mark also guards against stale WAL snapshot reads, which
    # would otherwise produce phantom "advances".
    for sid, e in active.items():
        tu = e["row"]["time_updated"]
        prev_max = _lifecycle["seen_updated"].get(sid)
        if (
            sid in _lifecycle["closed_ids"]
            and prev_max is not None
            and tu > prev_max
        ):
            _lifecycle["closed_ids"].discard(sid)
        if prev_max is None or tu > prev_max:
            _lifecycle["seen_updated"][sid] = tu

    # --- session_finished / session_exited ---
    for sid, prev_info in prev_active.items():
        cur = active.get(sid)
        r = get_row(sid)
        if r is None:
            continue
        elapsed = max(0, (r["time_updated"] - r["time_created"]) // 1000)
        if cur is None:
            # Full transition active -> inactive.
            if sid in _lifecycle["closed_ids"]:
                _lifecycle["resumed_emitted"].discard(sid)
                _lifecycle["proc_seen"].discard(sid)
                continue
            reason, finish_ts = _latest_finish(conn, sid)
            if reason == "stop" and finish_ts >= now_ts - FINISH_RECENT_WINDOW_MS:
                events.append(_emit_event(SESSION_FINISHED, now_ts, r, elapsed))
                _lifecycle["closed_ids"].add(sid)
            elif sid in _lifecycle["proc_seen"] or reason == "unknown":
                # proc died without a clean stop (sticky proc_seen: the proc may
                # have vanished several ticks before recency expired), or the
                # run errored out → exited. (recency-only sessions that merely
                # went quiet — latest finish is "tool-calls" or none — emit
                # nothing: can't tell quiet from dead without a proc.)
                events.append(_emit_event(SESSION_EXITED, now_ts, r, elapsed))
                _lifecycle["closed_ids"].add(sid)
            _lifecycle["resumed_emitted"].discard(sid)
            _lifecycle["proc_seen"].discard(sid)
        else:
            # Still active. Fast path: proc vanished this tick but a clean
            # finish was just written → emit immediately instead of waiting
            # for the ~90s recency window to expire.
            if (
                prev_info.get("by_proc")
                and not cur.get("by_proc")
                and sid not in _lifecycle["closed_ids"]
            ):
                reason, finish_ts = _latest_finish(conn, sid)
                if reason == "stop" and finish_ts >= now_ts - FINISH_FAST_WINDOW_MS:
                    events.append(_emit_event(SESSION_FINISHED, now_ts, r, elapsed))
                    _lifecycle["closed_ids"].add(sid)

    # Sticky proc-seen tracking for the current activity streak.
    for sid, e in active.items():
        if e.get("by_proc"):
            _lifecycle["proc_seen"].add(sid)

    # --- roll state forward ---
    _lifecycle["prev_known_ids"] = known_ids
    _lifecycle["prev_active"] = {
        sid: {
            "by_proc": bool(e.get("by_proc")),
            "elapsed": e["elapsed"],
            "time_updated": e["row"]["time_updated"],
        }
        for sid, e in active.items()
    }
    _lifecycle["prev_tick_ts"] = now_ts

    # Prune dedupe sets so they don't grow unbounded.
    if len(_lifecycle["emitted_started"]) > 2000:
        _lifecycle["emitted_started"] &= known_ids
    if len(_lifecycle["closed_ids"]) > 2000:
        _lifecycle["closed_ids"] &= known_ids
    if len(_lifecycle["resumed_emitted"]) > 2000:
        _lifecycle["resumed_emitted"] &= known_ids
    if len(_lifecycle["proc_seen"]) > 2000:
        _lifecycle["proc_seen"] &= known_ids
    if len(_lifecycle["seen_updated"]) > 2000:
        _lifecycle["seen_updated"] = {
            k: v for k, v in _lifecycle["seen_updated"].items() if k in known_ids
        }

    return events

def _payload_content_hash(payload: Dict[str, Any]) -> str:
    """Stable content hash of the payload, ignoring volatile transport fields
    (now_ts, events) and per-session presentation fields that advance every
    tick while a session runs (elapsed_seconds, formatted_time — clients
    re-derive elapsed locally from the frozen (elapsed, now_ts) pair, so they
    are not content). Including them would force a full tick every 2s during
    active-but-quiet periods, defeating the heartbeat."""
    basis = {k: v for k, v in payload.items() if k not in ("now_ts", "events")}
    scrubbed = dict(basis)
    scrubbed["active"] = [
        {**s, "elapsed_seconds": 0, "formatted_time": ""}
        for s in basis.get("active", [])
    ]
    return hashlib.md5(
        json.dumps(scrubbed, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()

def _refresh_tick_locked() -> None:
    global _last_tick_at, _tick_payload_cache, _tick_hash
    now = time.time()
    if _tick_payload_cache is not None and now - _last_tick_at < 1.0:
        return
    payload = get_sessions_payload()
    _tick_hash = _payload_content_hash(payload)
    _tick_payload_cache = payload
    _last_tick_at = now

def tick_sessions() -> Dict[str, Any]:
    """Build the sessions payload at most once per second, shared across all SSE
    connections and the REST endpoint. Also drives lifecycle event detection
    (exactly once per tick, under the lock)."""
    with _tick_lock:
        _refresh_tick_locked()
        return _tick_payload_cache

def tick_snapshot() -> Tuple[Dict[str, Any], Optional[str]]:
    """Atomically return (payload, content hash) — callers must serialize a
    payload and remember the hash from the SAME tick, otherwise a racing tick
    refresh could mark unsent changes as already-delivered."""
    with _tick_lock:
        _refresh_tick_locked()
        return _tick_payload_cache, _tick_hash

def _render_tick(
    payload: Dict[str, Any],
    tick_hash: Optional[str],
    last_sent_hash: Optional[str],
    events: List[Dict[str, Any]],
) -> Tuple[str, Optional[str]]:
    """Serialize one SSE tick. Full payload when the content changed since the
    last tick this connection sent (or on its first tick, or when lifecycle
    events are pending); otherwise a ~80-byte heartbeat. Returns (line, hash)."""
    if last_sent_hash is not None and tick_hash is not None and tick_hash == last_sent_hash and not events:
        heartbeat = {
            "now_ts": payload.get("now_ts", 0),
            "delta": False,
            "max_seq": _current_event_seq(),
            "events": [],
        }
        return f"data: {json.dumps(heartbeat, ensure_ascii=False)}\n\n", last_sent_hash
    out = dict(payload)
    out["delta"] = True
    out["max_seq"] = _current_event_seq()
    out["events"] = events
    return f"data: {json.dumps(out, ensure_ascii=False)}\n\n", tick_hash

def _current_event_seq() -> int:
    with _tick_lock:
        return _event_seq

def _events_after(cursor: int) -> List[Dict[str, Any]]:
    with _tick_lock:
        return [e for e in _event_ring if e["seq"] > cursor]


def _build_opencode_payload(now_ts: int) -> Dict[str, Any]:
    dir_map, proc_sessions, fresh_starts = get_opencode_proc_dirs()
    conn = sqlite3.connect(f"file:{OPENCODE_DB}?mode=ro", uri=True, timeout=2.0)
    try:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """SELECT id, directory, title, agent, model, cost,
                      tokens_input, tokens_output, tokens_reasoning,
                      tokens_cache_read, tokens_cache_write,
                      time_created, time_updated
               FROM session ORDER BY time_updated DESC LIMIT ?""",
            (SESSIONS_FETCH_LIMIT,),
        ).fetchall()

        by_directory: Dict[str, List[sqlite3.Row]] = {}
        for r in rows:
            by_directory.setdefault(r["directory"], []).append(r)

        # --- Active detection ---
        active: Dict[str, Dict[str, Any]] = {}  # session_id -> {row, elapsed, proc_count, by_proc}

        # 1) Exact: a live proc with `--session <id>` pins that session directly.
        for sid, pinfo in proc_sessions.items():
            r = next((x for x in rows if x["id"] == sid), None)
            if r is None:
                try:
                    r = conn.execute(
                        """SELECT id, directory, title, agent, model, cost,
                                  tokens_input, tokens_output, tokens_reasoning,
                                  tokens_cache_read, tokens_cache_write,
                                  time_created, time_updated
                           FROM session WHERE id = ?""",
                        (sid,),
                    ).fetchone()
                except sqlite3.OperationalError:
                    r = None
            if r is not None:
                active[sid] = {
                    "row": r,
                    "elapsed": pinfo["elapsed_seconds"],
                    "proc_count": pinfo["proc_count"],
                    "by_proc": True,
                }

        # 2) Dir join: proc cmdline workspace path -> session.directory
        for proc_dir, info in dir_map.items():
            candidates = by_directory.get(proc_dir)
            if not candidates:
                # Fallback: session dir under proc dir (worktree) or vice versa
                candidates = [
                    r for r in rows
                    if r["directory"].startswith(proc_dir + "/")
                    or proc_dir.startswith(r["directory"] + "/")
                ]
            if not candidates:
                # Fallback: targeted lookup for sessions in that exact directory
                # (covers active sessions outside the recent-100 window)
                try:
                    candidates = conn.execute(
                        """SELECT id, directory, title, agent, model, cost,
                                  tokens_input, tokens_output, tokens_reasoning,
                                  tokens_cache_read, tokens_cache_write,
                                  time_created, time_updated
                           FROM session WHERE directory = ?
                           ORDER BY time_updated DESC LIMIT 1""",
                        (proc_dir,),
                    ).fetchall()
                except sqlite3.OperationalError:
                    candidates = []
            if not candidates:
                continue
            best = max(candidates, key=lambda r: r["time_updated"])
            # Sessions born since the proc started are proc-backed too: a fresh
            # `opencode run` creates its session a few seconds after the wrapper
            # starts, and the newest-updated heuristic alone would miss it when
            # another session in the same repo (e.g. a gateway/web session)
            # happens to update more recently.
            proc_start_ms = now_ts - info["elapsed_seconds"] * 1000
            backed = {
                r["id"]: r for r in candidates
                if r["time_created"] >= proc_start_ms - 5_000
            }
            for r in [best] + list(backed.values()):
                born = r["time_created"] >= proc_start_ms - 5_000
                elapsed = (
                    min(info["elapsed_seconds"], max(0, (now_ts - r["time_created"]) // 1000))
                    if born else info["elapsed_seconds"]
                )
                prev = active.get(r["id"])
                if prev is None:
                    active[r["id"]] = {
                        "row": r,
                        "elapsed": elapsed,
                        "proc_count": info["proc_count"],
                        "by_proc": True,
                    }
                else:
                    prev["elapsed"] = min(prev["elapsed"], elapsed)
                    prev["proc_count"] += info["proc_count"]

        # 2b) Fresh-run join: an `opencode run` proc without --session creates
        # a brand-new session seconds after boot. Exec-collapsed launches carry
        # no workspace path in the cmdline, so match by creation time instead.
        # NOTE: no recency requirement here — a fresh session generating its
        # FIRST (long) response hasn't written a completed message yet, so its
        # time_updated still equals its creation time and it can look >90s
        # stale while the proc is very much alive.
        for proc_start_ms in fresh_starts:
            best_fresh = None
            for r in rows:
                if r["id"] in active:
                    continue
                if not (proc_start_ms - 2_000 <= r["time_created"] <= proc_start_ms + 60_000):
                    continue
                if best_fresh is None or r["time_created"] > best_fresh["time_created"]:
                    best_fresh = r
            if best_fresh is not None:
                elapsed = min(
                    int((now_ts - proc_start_ms) // 1000),
                    max(0, (now_ts - best_fresh["time_created"]) // 1000),
                )
                active[best_fresh["id"]] = {
                    "row": best_fresh,
                    "elapsed": elapsed,
                    "proc_count": 1,
                    "by_proc": True,
                }

        # 3) Recency fallback: a session updated within ACTIVE_RECENCY_MS is
        # considered active even when no proc cmdline matched (belt & braces —
        # live opencode sessions touch time_updated on every message).
        for r in rows:
            if r["id"] in active:
                continue
            if 0 <= now_ts - r["time_updated"] <= ACTIVE_RECENCY_MS:
                active[r["id"]] = {
                    "row": r,
                    "elapsed": max(0, (now_ts - r["time_created"]) // 1000),
                    "proc_count": 0,
                    "by_proc": False,
                }

        # --- Lifecycle event detection (start/resume/finish/exit) ---
        _detect_lifecycle_events(conn, now_ts, active, rows, proc_sessions)

        active_ids = set(active.keys())
        recent_rows = [r for r in rows if r["id"] not in active_ids][:SESSIONS_RECENT_LIMIT]

        # --- Bulk fetch message counts + todos for all payload sessions ---
        ids = list(active_ids | {r["id"] for r in recent_rows})
        message_counts: Dict[str, int] = {}
        todos: Dict[str, List[Dict[str, Any]]] = {sid: [] for sid in ids}
        if ids:
            placeholders = ",".join("?" * len(ids))
            try:
                for sid, count in conn.execute(
                    f"SELECT session_id, COUNT(*) FROM message WHERE session_id IN ({placeholders}) GROUP BY session_id",
                    ids,
                ):
                    message_counts[sid] = count
                for sid, content, status, priority, position in conn.execute(
                    f"""SELECT session_id, content, status, priority, position
                        FROM todo WHERE session_id IN ({placeholders}) ORDER BY position""",
                    ids,
                ):
                    todos.setdefault(sid, []).append({
                        "content": content,
                        "status": status,
                        "priority": priority,
                    })
            except sqlite3.OperationalError:
                pass

        def to_session(r: sqlite3.Row, is_active: bool, elapsed: int, proc_count: int) -> Dict[str, Any]:
            # Mobile-data diet: fields the Android UI never renders are dropped
            # (directory, agent, created_at, tokens_reasoning, tokens_cache_write),
            # and todo item TEXT is only sent for ACTIVE sessions (recent rows
            # only render the ✓counts). Kotlin models default these fields.
            todo_items = todos.get(r["id"], [])
            todo_stats = {
                "total": len(todo_items),
                "completed": sum(1 for t in todo_items if t["status"] == "completed"),
                "in_progress": sum(1 for t in todo_items if t["status"] == "in_progress"),
                "pending": sum(1 for t in todo_items if t["status"] == "pending"),
                "items": todo_items if is_active else [],
            }
            return {
                "id": f"opencode:{r['id']}",
                "source_id": r["id"],
                "source": "opencode",
                "title": r["title"],
                "repo": os.path.basename(r["directory"]) or r["directory"],
                "model": _session_model_name(r["model"]),
                "is_active": is_active,
                "elapsed_seconds": elapsed if is_active else 0,
                "formatted_time": format_duration(elapsed) if is_active else "",
                "message_count": message_counts.get(r["id"], 0),
                "tokens_input": r["tokens_input"],
                "tokens_output": r["tokens_output"],
                "tokens_cache_read": r["tokens_cache_read"],
                "cost": r["cost"],
                "updated_at": r["time_updated"],
                "last_activity": _last_activity_text(conn, r["id"], r["title"]),
                "todo": todo_stats,
                **({"proc_count": proc_count} if is_active else {}),
            }

        active_list = [
            to_session(entry["row"], True, entry["elapsed"], entry["proc_count"])
            for entry in sorted(active.values(), key=lambda e: e["row"]["time_updated"], reverse=True)
        ]
        recent_list = [to_session(r, False, 0, 0) for r in recent_rows]

        return {"active": active_list, "recent": recent_list}
    finally:
        conn.close()

def _build_sessions_payload(now_ts: int) -> Dict[str, Any]:
    """Aggregate provider adapters into the public, provider-neutral payload."""
    snapshots = [_build_opencode_payload(now_ts)]
    try:
        codex = CODEX_SOURCE.snapshot(now_ts)
        _detect_codex_lifecycle(now_ts, codex)
        snapshots.append(codex)
    except (OSError, sqlite3.Error) as exc:
        snapshots.append({"active": [], "recent": [], "warning": f"Codex unavailable: {exc}"})
    return merge_snapshots(now_ts, snapshots, SESSIONS_RECENT_LIMIT)

def get_sessions_payload() -> Dict[str, Any]:
    """Full sessions observability payload; graceful fallback to cached copy on DB lock."""
    global _sessions_payload_cache
    now_ts = int(time.time() * 1000)
    try:
        payload = _build_sessions_payload(now_ts)
        _sessions_payload_cache = payload
        return payload
    except sqlite3.OperationalError as e:
        if _sessions_payload_cache:
            cached = dict(_sessions_payload_cache)
            cached["now_ts"] = now_ts
            return cached
        return {"now_ts": now_ts, "active": [], "recent": [], "error": str(e)}

def get_aggregated_status() -> Dict[str, Any]:
    global custom_status
    now_ts = int(time.time() * 1000)

    # If manual override is fresh (< 30s old)
    if custom_status and (now_ts - custom_status.get("updated_at", 0) < 30000):
        return custom_status

    opencode_procs = get_opencode_process_details()
    current_action = read_file_safe(CURRENT_ACTION_FILE)

    subagent_data = {}
    if os.path.exists(SUBAGENT_MONITOR_FILE):
        try:
            with open(SUBAGENT_MONITOR_FILE, "r", encoding="utf-8") as f:
                subagent_data = json.load(f)
        except Exception:
            pass

    is_opencode_running = len(opencode_procs) > 0
    is_subagent_running = subagent_data.get("status") == "running"
    is_action_active = len(current_action) > 0

    if is_opencode_running:
        main_proc = opencode_procs[0]
        elapsed = main_proc["elapsed_seconds"]
        cmd_str = main_proc["cmd"]
        cmd_clean = cmd_str.split("opencode", 1)[-1].strip()
        if len(cmd_clean) > 80:
            cmd_clean = cmd_clean[:77] + "..."

        formatted_time = format_duration(elapsed)
        return {
            "active": True,
            "agent": "OpenCode",
            "status": "running",
            "state_type": "coding",
            "title": f"OpenCode ({formatted_time})",
            "detail": cmd_clean if cmd_clean else "Generating & running tools",
            "model": "glm-5.3",
            "elapsed_seconds": elapsed,
            "formatted_time": formatted_time,
            "proc_count": len(opencode_procs),
            "updated_at": now_ts
        }

    if is_subagent_running:
        task_name = subagent_data.get("task", "Subagent Task")
        reports = subagent_data.get("reports", [])
        last_report = reports[-1].get("progress", "") if reports else ""
        started_at = subagent_data.get("startedAt", "")
        # Parse elapsed time if possible
        elapsed = 0
        formatted_time = ""
        return {
            "active": True,
            "agent": "Subagent",
            "status": "running",
            "state_type": "thinking",
            "title": f"Subagent: {task_name}",
            "detail": last_report or "Thinking and researching...",
            "model": "ox-alpha-free",
            "elapsed_seconds": elapsed,
            "formatted_time": formatted_time,
            "proc_count": 1,
            "updated_at": now_ts
        }

    if is_action_active:
        return {
            "active": True,
            "agent": "OpenClaw",
            "status": "executing",
            "state_type": "active",
            "title": "OpenClaw Active",
            "detail": current_action.splitlines()[0][:80],
            "model": "ox-alpha-free",
            "elapsed_seconds": 0,
            "formatted_time": "",
            "proc_count": 1,
            "updated_at": now_ts
        }

    # Idle State
    return {
        "active": False,
        "agent": "OpenClaw",
        "status": "idle",
        "state_type": "idle",
        "title": "All Agents Idle",
        "detail": "Standing by for instructions",
        "model": "ox-alpha-free",
        "elapsed_seconds": 0,
        "formatted_time": "00:00",
        "proc_count": 0,
        "updated_at": now_ts
    }

@app.get("/healthz")
def healthz():
    return {"status": "ok", "service": "claw-island-server", "port": int(os.getenv("PORT", 18818))}

@app.get("/api/status")
def get_status():
    return get_aggregated_status()

@app.post("/api/status")
async def post_status(request: Request):
    global custom_status
    try:
        body = await request.json()
        body["updated_at"] = int(time.time() * 1000)
        custom_status = body
        return {"status": "success", "data": custom_status}
    except Exception as e:
        return JSONResponse(status_code=400, content={"error": str(e)})

@app.get("/api/events")
async def sse_events(request: Request):
    async def event_generator():
        while True:
            if await request.is_disconnected():
                break

            status = get_aggregated_status()
            data = json.dumps(status, ensure_ascii=False)
            yield f"data: {data}\n\n"

            await asyncio.sleep(2.0)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

@app.get("/api/sessions")
def get_sessions():
    return tick_sessions()

@app.get("/api/sessions/events/latest")
def latest_session_events(limit: int = 20):
    """Most recent lifecycle events (newest first), for debugging / future UI."""
    limit = max(1, min(limit, EVENT_RING_SIZE))
    with _tick_lock:
        events = list(_event_ring)
        seq = _event_seq
    return {"seq": seq, "max_seq": seq, "events": list(reversed(events[-limit:]))}

@app.get("/api/sessions/events")
async def sse_sessions_events(request: Request):
    # Per-connection cursor: ?after=<seq> backfills from the ring buffer;
    # without it, the cursor starts at the current seq so only events that
    # occur during this connection are delivered (no backfill spam).
    after_param = request.query_params.get("after")
    try:
        cursor = int(after_param) if after_param is not None else _current_event_seq()
    except (TypeError, ValueError):
        cursor = _current_event_seq()
    # Clamp a stale cursor (e.g. the state file was wiped): a client asking
    # for seq > max would otherwise stay blind forever — restart it from the
    # beginning of the ring so it gets a full backfill.
    if cursor > _current_event_seq():
        cursor = 0

    async def event_generator():
        nonlocal cursor
        last_sent_hash: Optional[str] = None
        while True:
            if await request.is_disconnected():
                break

            payload, tick_hash = await asyncio.to_thread(tick_snapshot)
            events = _events_after(cursor)
            if events:
                cursor = events[-1]["seq"]
            line, last_sent_hash = _render_tick(payload, tick_hash, last_sent_hash, events)
            yield line

            await asyncio.sleep(2.0)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

if __name__ == "__main__":
    port = int(os.getenv("PORT", 18818))
    # Bound shutdown below systemd's TimeoutStopSec=10s. Persistent SSE clients
    # otherwise make Uvicorn wait indefinitely and force systemd to SIGKILL.
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=port,
        log_level="info",
        timeout_graceful_shutdown=5,
    )
