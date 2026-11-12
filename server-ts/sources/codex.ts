import { DatabaseSync } from "node:sqlite";
import { basename, join } from "node:path";
import { existsSync } from "node:fs";
import { clean, duration, emptyTodo, type Session, type SourceSnapshot } from "../contracts.js";
import { CodexPlanReader, normalizePlan, type Plan } from "../plan.js";

type Row = Record<string, unknown>;
export class CodexSource {
  constructor(private home = process.env.CODEX_HOME ?? join(process.env.HOME ?? "", ".codex"), private limit = 20, private plans = new CodexPlanReader()) {}
  snapshot(now: number): SourceSnapshot {
    const statePath = join(this.home, "state_5.sqlite"), historyPath = join(this.home, "thread_history_1.sqlite");
    if (!existsSync(statePath) || !existsSync(historyPath)) return { active: [], recent: [], warning: "Codex databases not found" };
    const state = new DatabaseSync(statePath, { readOnly: true }), history = new DatabaseSync(historyPath, { readOnly: true });
    try {
      const rows=state.prepare("SELECT id,rollout_path,cwd,title,model,model_provider,tokens_used,created_at_ms,updated_at_ms,preview,git_branch,reasoning_effort FROM threads WHERE archived=0 ORDER BY updated_at_ms DESC LIMIT 100").all() as Row[];
      if (!rows.length) return { active: [], recent: [] };
      const ids = rows.map(r => String(r.id)), placeholders = ids.map(() => "?").join(",");
      const turns = history.prepare(`SELECT thread_id, MIN(started_at) started_at, COUNT(*) turn_count FROM thread_turns WHERE thread_id IN (${placeholders}) AND status='inProgress' GROUP BY thread_id`).all(...ids) as Row[];
      const active = new Map(turns.map(r => [String(r.thread_id), r]));
      const counts = new Map((history.prepare(`SELECT thread_id, COUNT(*) count FROM thread_turns WHERE thread_id IN (${placeholders}) GROUP BY thread_id`).all(...ids) as Row[]).map(r => [String(r.thread_id), Number(r.count)]));
      const activities = new Map<string,string>(), phases = new Map<string,string>(), actions = new Map<string,string>();
      // Codex indexes items by (thread_id, rollout_ordinal). Seek within each
      // visible thread and stop at the latest usable value instead of loading
      // and parsing the entire history on every poll.
      const latestItem = history.prepare("SELECT item_type FROM thread_items WHERE thread_id=? ORDER BY rollout_ordinal DESC LIMIT 1");
      const messages = history.prepare("SELECT item_json FROM thread_items WHERE thread_id=? AND item_type='agentMessage' ORDER BY rollout_ordinal DESC");
      const actionItems = history.prepare("SELECT item_type,item_json FROM thread_items WHERE thread_id=? AND item_type IN ('commandExecution','fileChange') ORDER BY rollout_ordinal DESC");
      for (const r of rows) {
        const id = String(r.id), latest = latestItem.get(id) as Row | undefined;
        if (latest) phases.set(id, phaseFor(String(latest.item_type)));
        for (const message of messages.iterate(id)) {
          try {
            const text = clean(JSON.parse(String(message.item_json)).text);
            if (text) { activities.set(id, text); break; }
          } catch {}
        }
        for (const item of actionItems.iterate(id)) {
          try {
            const action = actionFor(String(item.item_type), JSON.parse(String(item.item_json)));
            if (action) { actions.set(id, action); break; }
          } catch {}
        }
      }
      const sessions=rows.map(r => {const turn=active.get(String(r.id));const plan=turn?normalizePlan(this.plans.read(String(r.rollout_path??"")),true):emptyTodo();return this.normalize(r,turn,counts,activities,phases,actions,plan,now)});
      const current=sessions.filter(s=>s.is_active).sort(byUpdated), currentIds=new Set(current.map(s=>s.id));
      return {active:current,recent:sessions.filter(s=>!currentIds.has(s.id)).sort(byUpdated).slice(0,this.limit)};
    } finally { history.close(); state.close(); }
  }
  private normalize(r:Row, turn:Row|undefined, counts:Map<string,number>, activity:Map<string,string>, phases:Map<string,string>, actions:Map<string,string>, todo:Plan, now:number):Session {
    const raw=String(r.id), cwd=String(r.cwd??""), started=Number(turn?.started_at??0)*1000, elapsed=started?Math.max(0,Math.floor((now-started)/1000)):0;
    const title=clean(r.title||r.preview||"Codex session");
    return {id:`codex:${raw}`,source_id:raw,source:"codex",title,repo:basename(cwd)||"codex",directory:cwd,agent:"codex",model:String(r.model||r.model_provider||""),status:turn?"running":"idle",is_active:!!turn,elapsed_seconds:elapsed,formatted_time:turn?duration(elapsed):"",message_count:counts.get(raw)??0,tokens_total:Number(r.tokens_used??0),tokens_input:0,tokens_output:0,tokens_reasoning:0,tokens_cache_read:0,tokens_cache_write:0,cost:0,created_at:Number(r.created_at_ms??0),updated_at:Number(r.updated_at_ms??0),last_activity:activity.get(raw)??title,todo,proc_count:Number(turn?.turn_count??0),...(turn&&r.git_branch?{git_branch:clean(r.git_branch,40)}:{}),...(turn&&r.reasoning_effort?{reasoning_effort:clean(r.reasoning_effort,20)}:{}),...(turn?{phase:phases.get(raw)??"Working",last_action:actions.get(raw)??"Processing turn"}:{})};
  }
}
const byUpdated=(a:Session,b:Session)=>b.updated_at-a.updated_at||b.id.localeCompare(a.id);
const phaseFor=(type:string)=>({reasoning:"Reasoning",commandExecution:"Executing",fileChange:"Editing",agentMessage:"Responding",userMessage:"Queued"}[type]??"Working");
const actionFor=(type:string,item:{command?:string;changes?:unknown[]})=>{if(type==="fileChange")return `Edited ${item.changes?.length??1} file${item.changes?.length===1?"":"s"}`;if(type!=="commandExecution"||!item.command)return "";const c=item.command.toLowerCase();if(c.includes("gradlew"))return "Building Android";if(c.includes("npm test")||c.includes("vitest"))return "Running tests";if(c.includes("cargo test"))return "Running Rust tests";if(c.includes("git "))return "Updating Git";if(c.includes("curl "))return "Checking API";return "Running command"};
