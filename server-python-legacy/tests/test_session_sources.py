import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from session_sources import CodexSource, merge_snapshots


class CodexSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = Path(self.temp.name)
        state = sqlite3.connect(self.home / "state_5.sqlite")
        state.execute(
            """CREATE TABLE threads (
                id TEXT PRIMARY KEY, cwd TEXT, title TEXT, model TEXT,
                model_provider TEXT, tokens_used INTEGER, created_at_ms INTEGER,
                updated_at_ms INTEGER, preview TEXT, archived INTEGER)"""
        )
        state.executemany(
            "INSERT INTO threads VALUES (?,?,?,?,?,?,?,?,?,?)",
            [
                ("active", "/work/alpha", "Active task", "gpt-test", "openai", 1234, 1000, 9000, "", 0),
                ("recent", "/work/beta", "Recent task", "gpt-test", "openai", 55, 2000, 8000, "", 0),
            ],
        )
        state.commit()
        state.close()

        history = sqlite3.connect(self.home / "thread_history_1.sqlite")
        history.execute(
            """CREATE TABLE thread_turns (
                thread_id TEXT, turn_id TEXT, status TEXT, started_at INTEGER)"""
        )
        history.execute("INSERT INTO thread_turns VALUES ('active','turn','inProgress',5)")
        history.execute("INSERT INTO thread_turns VALUES ('recent','turn','completed',2)")
        history.execute(
            """CREATE TABLE thread_items (
                thread_id TEXT, item_type TEXT, rollout_ordinal INTEGER, item_json TEXT)"""
        )
        history.execute(
            "INSERT INTO thread_items VALUES (?,?,?,?)",
            ("active", "agentMessage", 1, json.dumps({"type": "agentMessage", "text": "  Doing   work  "})),
        )
        history.commit()
        history.close()

    def tearDown(self):
        self.temp.cleanup()

    def test_normalizes_active_and_recent_threads(self):
        payload = CodexSource(self.home).snapshot(10_000)
        self.assertEqual(["codex:active"], [item["id"] for item in payload["active"]])
        self.assertEqual("codex", payload["active"][0]["source"])
        self.assertEqual("Doing work", payload["active"][0]["last_activity"])
        self.assertEqual(5, payload["active"][0]["elapsed_seconds"])
        self.assertEqual(1234, payload["active"][0]["tokens_total"])
        self.assertEqual(["codex:recent"], [item["id"] for item in payload["recent"]])

    def test_missing_databases_degrade_to_warning(self):
        payload = CodexSource(self.home / "missing").snapshot(10_000)
        self.assertEqual([], payload["active"])
        self.assertIn("warning", payload)


class MergeSnapshotsTest(unittest.TestCase):
    def test_deduplicates_and_sorts_provider_neutrally(self):
        older = {"id": "opencode:a", "updated_at": 1}
        newer = {"id": "codex:b", "updated_at": 2}
        merged = merge_snapshots(10, [
            {"active": [older], "recent": []},
            {"active": [newer], "recent": [older]},
        ])
        self.assertEqual(["codex:b", "opencode:a"], [item["id"] for item in merged["active"]])
        self.assertEqual([], merged["recent"])


if __name__ == "__main__":
    unittest.main()
