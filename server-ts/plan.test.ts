import { afterEach, describe, expect, it } from "vitest";
import { appendFileSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { CodexPlanReader, extractPlanFromToolInput, normalizePlan, PLAN_ITEM_LIMIT, PLAN_STEP_MAX_CHARS } from "./plan.js";

const dirs: string[] = [];
afterEach(() => { for (const dir of dirs) rmSync(dir, { recursive: true, force: true }); dirs.length = 0; });

describe("plan normalization", () => {
  it("keeps complete counts while bounding transmitted names", () => {
    const raw = Array.from({ length: PLAN_ITEM_LIMIT + 3 }, (_, index) => ({
      content: `${"😀".repeat(PLAN_STEP_MAX_CHARS + 5)} ${index}`,
      status: index < 2 ? "completed" : index === 2 ? "in_progress" : "pending",
    }));
    const plan = normalizePlan(raw);
    expect(plan).toMatchObject({ total: PLAN_ITEM_LIMIT + 3, completed: 2, in_progress: 1, pending: PLAN_ITEM_LIMIT });
    expect(plan.items).toHaveLength(PLAN_ITEM_LIMIT);
    expect(Array.from(plan.items[0]!.content)).toHaveLength(PLAN_STEP_MAX_CHARS);
  });
});

describe("Codex plan extraction", () => {
  it("extracts full step names from an update_plan call", () => {
    const result = extractPlanFromToolInput(`const p = await tools.update_plan({explanation:"x",plan:[
      {step:"Inspect the complete server contract",status:"completed"},
      {step:"Render named checklist items",status:"in_progress"},
      {step:"Measure mobile payload bytes",status:"pending"}
    ]}); text(p);`);
    expect(result).toEqual([
      { content: "Inspect the complete server contract", status: "completed", priority: "medium" },
      { content: "Render named checklist items", status: "in_progress", priority: "medium" },
      { content: "Measure mobile payload bytes", status: "pending", priority: "medium" },
    ]);
  });

  it("extracts Codex plan calls with JSON-quoted property names", () => {
    const result = extractPlanFromToolInput(
      'const r=await tools.update_plan({plan:[{"step":"Create project","status":"completed"},{"step":"Verify SSE","status":"in_progress"}]}); text(r);',
    );
    expect(result).toEqual([
      { content: "Create project", status: "completed", priority: "medium" },
      { content: "Verify SSE", status: "in_progress", priority: "medium" },
    ]);
  });

  it("uses the latest complete plan and incrementally refreshes the cache", () => {
    const dir = mkdtempSync(join(tmpdir(), "claw-plan-")); dirs.push(dir);
    const path = join(dir, "rollout.jsonl");
    writeFileSync(path, `${line("Old step", "in_progress")}\n`);
    const reader = new CodexPlanReader();
    expect(reader.read(path).map(item => item.content)).toEqual(["Old step"]);
    appendFileSync(path, `${JSON.stringify({ type: "event_msg", payload: { type: "token_count" } })}\n`);
    expect(reader.read(path).map(item => item.content)).toEqual(["Old step"]);
    appendFileSync(path, `${line("New full step name", "completed")}\n`);
    expect(reader.read(path)).toEqual([{ content: "New full step name", status: "completed", priority: "medium" }]);
  });

  it("tolerates malformed and absent plan records", () => {
    expect(extractPlanFromToolInput("tools.update_plan({plan:[{step:broken}]})")).toBeNull();
    expect(extractPlanFromToolInput("tools.exec_command({})")).toBeNull();
  });
});

function line(step: string, status: string): string {
  return JSON.stringify({
    type: "response_item",
    payload: {
      type: "custom_tool_call",
      name: "exec",
      input: `const p=await tools.update_plan({plan:[{step:${JSON.stringify(step)},status:${JSON.stringify(status)}}]});`,
    },
  });
}
