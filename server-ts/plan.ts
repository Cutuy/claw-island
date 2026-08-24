import { closeSync, fstatSync, openSync, readSync } from "node:fs";
import { clean } from "./contracts.js";

export const PLAN_ITEM_LIMIT = 12;
export const PLAN_STEP_MAX_CHARS = 160;

export type PlanStatus = "completed" | "in_progress" | "pending";
export type PlanItem = { content: string; status: PlanStatus; priority: string };
export type Plan = {
  total: number;
  completed: number;
  in_progress: number;
  pending: number;
  items: PlanItem[];
};

export function normalizePlan(
  sourceItems: Array<{ content: unknown; status: unknown; priority?: unknown }>,
  includeItems = true,
): Plan {
  const normalized = sourceItems.flatMap(item => {
    const content = truncateCodePoints(clean(item.content, PLAN_STEP_MAX_CHARS * 4), PLAN_STEP_MAX_CHARS);
    if (!content) return [];
    return [{
      content,
      status: normalizeStatus(item.status),
      priority: clean(item.priority || "medium", 20) || "medium",
    } satisfies PlanItem];
  });
  return {
    total: normalized.length,
    completed: normalized.filter(item => item.status === "completed").length,
    in_progress: normalized.filter(item => item.status === "in_progress").length,
    pending: normalized.filter(item => item.status === "pending").length,
    items: includeItems ? normalized.slice(0, PLAN_ITEM_LIMIT) : [],
  };
}

export function extractPlanFromToolInput(input: unknown): PlanItem[] | null {
  if (typeof input !== "string") return extractDirectPlan(input);
  const marker = input.lastIndexOf("tools.update_plan(");
  if (marker < 0) {
    try { return extractDirectPlan(JSON.parse(input)); } catch { return null; }
  }
  const call = input.slice(marker);
  const items: PlanItem[] = [];
  // Codex has emitted both JavaScript-style keys (`step:`) and JSON-style
  // keys (`"step":`) for the same internal tool call. Accept either form.
  const pair = /\{\s*"?step"?\s*:\s*("(?:\\.|[^"\\])*")\s*,\s*"?status"?\s*:\s*("(?:\\.|[^"\\])*")\s*\}/g;
  for (const match of call.matchAll(pair)) {
    try {
      const content = JSON.parse(match[1]!) as string;
      const status = JSON.parse(match[2]!) as string;
      items.push({ content, status: normalizeStatus(status), priority: "medium" });
    } catch { /* A malformed item must not hide a later valid plan update. */ }
  }
  return items.length ? items : null;
}

type CacheEntry = { size: number; text: string; plan: PlanItem[] };

/** Reads only newly appended rollout bytes after the first scan. */
export class CodexPlanReader {
  private readonly cache = new Map<string, CacheEntry>();

  read(path: string): PlanItem[] {
    if (!path) return [];
    let fd: number | undefined;
    try {
      fd = openSync(path, "r");
      const size = fstatSync(fd).size;
      const previous = this.cache.get(path);
      const reset = !previous || size < previous.size;
      const offset = reset ? 0 : previous.size;
      if (!reset && size === offset) return previous.plan;
      const buffer = Buffer.alloc(size - offset);
      if (buffer.length) readSync(fd, buffer, 0, buffer.length, offset);
      const text = (reset ? "" : previous.text) + buffer.toString("utf8");
      const lines = text.split("\n");
      const carry = lines.pop() ?? "";
      let plan = reset ? [] : previous.plan;
      for (const line of lines) {
        const extracted = extractPlanFromRolloutLine(line);
        if (extracted) plan = extracted;
      }
      this.cache.set(path, { size, text: carry, plan });
      return plan;
    } catch {
      return this.cache.get(path)?.plan ?? [];
    } finally {
      if (fd !== undefined) closeSync(fd);
    }
  }
}

function extractPlanFromRolloutLine(line: string): PlanItem[] | null {
  if (!line.includes("update_plan")) return null;
  try {
    const record = JSON.parse(line) as { payload?: { type?: string; name?: string; input?: unknown } };
    const payload = record.payload;
    if (!payload) return null;
    if (payload.name === "update_plan") return extractPlanFromToolInput(payload.input);
    if (payload.type === "custom_tool_call" && payload.name === "exec") {
      return extractPlanFromToolInput(payload.input);
    }
  } catch { /* Rollout logs are append-only; an incomplete final line is retried later. */ }
  return null;
}

function extractDirectPlan(input: unknown): PlanItem[] | null {
  const value = input && typeof input === "object" && "plan" in input
    ? (input as { plan?: unknown }).plan
    : null;
  if (!Array.isArray(value)) return null;
  const items = value.flatMap(item => {
    if (!item || typeof item !== "object") return [];
    const { step, status } = item as { step?: unknown; status?: unknown };
    if (typeof step !== "string") return [];
    return [{ content: step, status: normalizeStatus(status), priority: "medium" } satisfies PlanItem];
  });
  return items.length ? items : null;
}

function normalizeStatus(status: unknown): PlanStatus {
  if (status === "completed") return "completed";
  if (status === "in_progress") return "in_progress";
  return "pending";
}

function truncateCodePoints(value: string, limit: number): string {
  return Array.from(value).slice(0, limit).join("");
}
