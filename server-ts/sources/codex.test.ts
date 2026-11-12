import { afterEach, describe, expect, it, vi } from "vitest";
import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { CodexSource } from "./codex.js";

const dirs: string[] = [], databases: DatabaseSync[] = [];
afterEach(() => {
  vi.restoreAllMocks();
  for (const db of databases) db.close();
  databases.length = 0;
  for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
  dirs.length = 0;
});

describe("Codex history polling", () => {
  it("ignores unlisted threads and stops before older activity payloads", () => {
    const { home, state, history } = fixture();
    state.exec("INSERT INTO threads(id,title,archived,updated_at_ms) VALUES('visible','Current task',0,9000),('archived','Archived task',1,10000)");
    history.exec("INSERT INTO thread_turns VALUES('visible',5,'inProgress')");
    const insert = history.prepare("INSERT INTO thread_items VALUES(?,?,?,?)");
    const oldPayload = JSON.stringify({ text: "old activity ".repeat(1000), command: "old command" });
    history.exec("BEGIN");
    for (let i = 0; i < 1000; i++) {
      insert.run("visible", "agentMessage", i, oldPayload);
      insert.run("archived", "commandExecution", i, oldPayload);
      insert.run("unlisted", "agentMessage", i, oldPayload);
    }
    insert.run("visible", "agentMessage", 1000, JSON.stringify({ text: "Latest update" }));
    insert.run("visible", "commandExecution", 1001, JSON.stringify({ command: "npm test" }));
    insert.run("visible", "reasoning", 1002, oldPayload);
    history.exec("COMMIT");

    const parse = vi.spyOn(JSON, "parse");
    const out = new CodexSource(home).snapshot(10_000);
    expect(out.active).toHaveLength(1);
    expect(out.active[0]).toMatchObject({
      id: "codex:visible", last_activity: "Latest update", phase: "Reasoning",
      last_action: "Running tests", message_count: 1,
    });
    expect(out.recent).toEqual([]);
    expect(parse.mock.calls.some(([text]) => text === oldPayload)).toBe(false);
  });

  it("keeps valid fallbacks and refreshes items edited at the same ordinal", () => {
    const { home, state, history } = fixture();
    state.exec("INSERT INTO threads(id,title,archived,updated_at_ms) VALUES('t','Task',0,9000),('idle','Idle task',0,8000)");
    history.exec("INSERT INTO thread_turns VALUES('t',5,'inProgress'),('t',6,'inProgress'),('t',1,'completed')");
    const insert = history.prepare("INSERT INTO thread_items VALUES('t',?,?,?)");
    insert.run("agentMessage", 1, JSON.stringify({ text: "Previous update" }));
    insert.run("fileChange", 2, JSON.stringify({ changes: [{}, {}] }));
    insert.run("commandExecution", 3, JSON.stringify({ command: "" }));
    insert.run("agentMessage", 4, JSON.stringify({ text: " \n " }));
    insert.run("commandExecution", 5, "broken json");
    insert.run("agentMessage", 6, "broken json");

    const source = new CodexSource(home, 1);
    expect(source.snapshot(10_000).active[0]).toMatchObject({
      phase: "Responding", last_activity: "Previous update", last_action: "Edited 2 files",
      proc_count: 2, message_count: 3, elapsed_seconds: 5,
    });
    const update = history.prepare("UPDATE thread_items SET item_json=? WHERE thread_id='t' AND rollout_ordinal=?");
    update.run(JSON.stringify({ command: "cargo test" }), 5);
    update.run(JSON.stringify({ text: "Updated message" }), 6);
    expect(source.snapshot(11_000).active[0]).toMatchObject({
      phase: "Responding", last_activity: "Updated message", last_action: "Running Rust tests",
      elapsed_seconds: 6,
    });

    history.exec("UPDATE thread_turns SET status='completed' WHERE thread_id='t'");
    const finished = source.snapshot(12_000);
    expect(finished.active).toEqual([]);
    expect(finished.recent).toHaveLength(1);
    expect(finished.recent[0]).toMatchObject({ id: "codex:t", last_activity: "Updated message", message_count: 3, is_active: false });
    expect(finished.recent[0]).not.toHaveProperty("phase");
  });

  it("returns an empty snapshot when no visible threads exist", () => {
    const { home } = fixture();
    expect(new CodexSource(home).snapshot(10_000)).toEqual({ active: [], recent: [] });
  });
});

function fixture() {
  const home = mkdtempSync(join(tmpdir(), "claw-codex-poll-"));
  dirs.push(home);
  const state = new DatabaseSync(join(home, "state_5.sqlite"));
  const history = new DatabaseSync(join(home, "thread_history_1.sqlite"));
  databases.push(state, history);
  state.exec(`CREATE TABLE threads(
    id TEXT, rollout_path TEXT, cwd TEXT, title TEXT, model TEXT, model_provider TEXT,
    tokens_used INTEGER, created_at_ms INTEGER, updated_at_ms INTEGER, preview TEXT,
    archived INTEGER, git_branch TEXT, reasoning_effort TEXT
  )`);
  history.exec(`
    CREATE TABLE thread_turns(thread_id TEXT, started_at INTEGER, status TEXT);
    CREATE INDEX turns_by_thread ON thread_turns(thread_id);
    CREATE TABLE thread_items(thread_id TEXT, item_type TEXT, rollout_ordinal INTEGER, item_json TEXT);
    CREATE UNIQUE INDEX idx_thread_items_page ON thread_items(thread_id, rollout_ordinal);
  `);
  return { home, state, history };
}
