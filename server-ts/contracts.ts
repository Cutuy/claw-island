import { z } from "zod";

export const TodoSchema = z.object({
  total: z.number().int().nonnegative().default(0),
  completed: z.number().int().nonnegative().default(0),
  in_progress: z.number().int().nonnegative().default(0),
  pending: z.number().int().nonnegative().default(0),
  items: z.array(z.object({ content: z.string(), status: z.string(), priority: z.string() })).default([]),
});

export const SessionSchema = z.object({
  id: z.string(), source_id: z.string(), source: z.enum(["opencode", "codex"]),
  title: z.string(), repo: z.string(), directory: z.string().default(""),
  agent: z.string().default(""), model: z.string().default(""), status: z.string().default("idle"),
  is_active: z.boolean(), elapsed_seconds: z.number().int().nonnegative(), formatted_time: z.string(),
  message_count: z.number().int().nonnegative(), tokens_total: z.number().nonnegative().default(0),
  tokens_input: z.number().nonnegative(), tokens_output: z.number().nonnegative(),
  tokens_reasoning: z.number().nonnegative(), tokens_cache_read: z.number().nonnegative(),
  tokens_cache_write: z.number().nonnegative(), cost: z.number().nonnegative(),
  created_at: z.number().nonnegative(), updated_at: z.number().nonnegative(),
  last_activity: z.string(), todo: TodoSchema, proc_count: z.number().int().nonnegative().default(0),
  git_branch: z.string().optional(), reasoning_effort: z.string().optional(),
  phase: z.string().optional(), last_action: z.string().optional(),
});

export type Session = z.infer<typeof SessionSchema>;
export type SourceSnapshot = { active: Session[]; recent: Session[]; warning?: string };
export type LifecycleType = "session_started" | "session_resumed" | "session_finished" | "session_exited";
export type LifecycleEvent = { id: string; seq: number; type: LifecycleType; ts: number; session: Partial<Session> };
export type SessionsPayload = { now_ts: number; active: Session[]; recent: Session[]; warnings?: string[] };

export const emptyTodo = () => ({ total: 0, completed: 0, in_progress: 0, pending: 0, items: [] });
export const duration = (seconds: number) => {
  const safe = Math.max(0, Math.floor(seconds));
  const h = Math.floor(safe / 3600), m = Math.floor((safe % 3600) / 60), s = safe % 60;
  return h ? `${String(h).padStart(2,"0")}:${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}` : `${String(m).padStart(2,"0")}:${String(s).padStart(2,"0")}`;
};
export const clean = (value: unknown, limit = 240) => String(value ?? "").trim().replace(/\s+/g, " ").slice(0, limit);
