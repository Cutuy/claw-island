"""Provider adapters for Claw Island's normalized session contract.

Adapters only discover and normalize source data. Lifecycle reconciliation and
transport stay in server.py, so adding another runtime does not require another
HTTP or SSE implementation.
"""

from __future__ import annotations

import json
import os
import sqlite3
from pathlib import Path
from typing import Any, Dict, Iterable, List, Set


def _readonly(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=2.0)
    conn.row_factory = sqlite3.Row
    return conn


def _clean_text(value: object, limit: int = 240) -> str:
    return " ".join(str(value or "").split())[:limit]


class CodexSource:
    """Read Codex's local projections without touching its writer-owned data."""

    name = "codex"

    def __init__(self, codex_home: Path | None = None, recent_limit: int = 20):
        self.home = codex_home or Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
        self.state_db = self.home / "state_5.sqlite"
        self.history_db = self.home / "thread_history_1.sqlite"
        self.recent_limit = recent_limit

    def available(self) -> bool:
        return self.state_db.is_file() and self.history_db.is_file()

    def snapshot(self, now_ts: int) -> Dict[str, Any]:
        if not self.available():
            return {"active": [], "recent": [], "warning": "Codex databases not found"}

        with _readonly(self.history_db) as history:
            active_turns = history.execute(
                """SELECT thread_id, MIN(started_at) AS started_at, COUNT(*) AS turn_count
                   FROM thread_turns WHERE status = 'inProgress' GROUP BY thread_id"""
            ).fetchall()
            active_by_id = {row["thread_id"]: row for row in active_turns}
            counts = {
                row["thread_id"]: row["count"]
                for row in history.execute(
                    "SELECT thread_id, COUNT(*) AS count FROM thread_turns GROUP BY thread_id"
                )
            }
            activity = self._latest_activity(history)

        with _readonly(self.state_db) as state:
            rows = state.execute(
                """SELECT id, cwd, title, model, model_provider, tokens_used,
                          created_at_ms, updated_at_ms, preview
                   FROM threads WHERE archived = 0
                   ORDER BY updated_at_ms DESC LIMIT ?""",
                (max(100, self.recent_limit + len(active_by_id)),),
            ).fetchall()
            missing = active_by_id.keys() - {row["id"] for row in rows}
            if missing:
                placeholders = ",".join("?" for _ in missing)
                rows += state.execute(
                    f"""SELECT id, cwd, title, model, model_provider, tokens_used,
                               created_at_ms, updated_at_ms, preview
                        FROM threads WHERE id IN ({placeholders})""",
                    tuple(missing),
                ).fetchall()

        sessions = [
            self._normalize(row, active_by_id.get(row["id"]), counts, activity, now_ts)
            for row in rows
        ]
        active = sorted(
            (item for item in sessions if item["is_active"]),
            key=lambda item: item["updated_at"], reverse=True,
        )
        active_ids = {item["id"] for item in active}
        recent = [item for item in sessions if item["id"] not in active_ids][: self.recent_limit]
        return {"active": active, "recent": recent}

    @staticmethod
    def _latest_activity(conn: sqlite3.Connection) -> Dict[str, str]:
        result: Dict[str, str] = {}
        for row in conn.execute(
            """SELECT thread_id, item_json FROM thread_items
               WHERE item_type = 'agentMessage'
               ORDER BY rollout_ordinal DESC"""
        ):
            if row["thread_id"] in result:
                continue
            try:
                data = json.loads(row["item_json"])
            except (TypeError, json.JSONDecodeError):
                continue
            text = _clean_text(data.get("text")) if isinstance(data, dict) else ""
            if text:
                result[row["thread_id"]] = text
        return result

    @staticmethod
    def _normalize(
        row: sqlite3.Row,
        active_turn: sqlite3.Row | None,
        counts: Dict[str, int],
        activity: Dict[str, str],
        now_ts: int,
    ) -> Dict[str, Any]:
        active = active_turn is not None
        started_ms = int(active_turn["started_at"] or 0) * 1000 if active else 0
        elapsed = max(0, (now_ts - started_ms) // 1000) if started_ms else 0
        cwd = row["cwd"] or ""
        title = _clean_text(row["title"] or row["preview"] or "Codex session")
        tokens = int(row["tokens_used"] or 0)
        return {
            "id": f"codex:{row['id']}",
            "source_id": row["id"],
            "source": "codex",
            "title": title,
            "repo": os.path.basename(cwd.rstrip("/")) or cwd or "codex",
            "directory": cwd,
            "agent": "codex",
            "model": row["model"] or row["model_provider"] or "",
            "status": "running" if active else "idle",
            "is_active": active,
            "elapsed_seconds": elapsed,
            "formatted_time": _format_duration(elapsed) if active else "",
            "message_count": counts.get(row["id"], 0),
            # Codex currently exposes a combined thread total, not a stable
            # input/output split. Keep it explicit rather than inventing one.
            "tokens_total": tokens,
            "tokens_input": 0,
            "tokens_output": 0,
            "tokens_reasoning": 0,
            "tokens_cache_read": 0,
            "tokens_cache_write": 0,
            "cost": 0.0,
            "created_at": int(row["created_at_ms"] or 0),
            "updated_at": int(row["updated_at_ms"] or 0),
            "last_activity": activity.get(row["id"], title),
            "todo": {"total": 0, "completed": 0, "in_progress": 0, "pending": 0, "items": []},
            "proc_count": int(active_turn["turn_count"] or 1) if active else 0,
        }


def merge_snapshots(now_ts: int, snapshots: Iterable[Dict[str, Any]], recent_limit: int = 20) -> Dict[str, Any]:
    """Merge normalized adapters with deterministic ordering and identity."""
    active: List[Dict[str, Any]] = []
    recent: List[Dict[str, Any]] = []
    warnings: List[str] = []
    seen: Set[str] = set()
    for snapshot in snapshots:
        warning = snapshot.get("warning")
        if warning:
            warnings.append(str(warning))
        for item in snapshot.get("active", []):
            if item["id"] not in seen:
                active.append(item)
                seen.add(item["id"])
        for item in snapshot.get("recent", []):
            if item["id"] not in seen:
                recent.append(item)
                seen.add(item["id"])
    active.sort(key=lambda item: (item.get("updated_at", 0), item["id"]), reverse=True)
    recent.sort(key=lambda item: (item.get("updated_at", 0), item["id"]), reverse=True)
    result: Dict[str, Any] = {"now_ts": now_ts, "active": active, "recent": recent[:recent_limit]}
    if warnings:
        result["warnings"] = warnings
    return result


def _format_duration(seconds: int) -> str:
    hours, remainder = divmod(max(0, seconds), 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}" if hours else f"{minutes:02d}:{seconds:02d}"
