import { DatabaseSync } from "node:sqlite";
import { basename, join } from "node:path";
import { existsSync, readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { clean, duration, type Session, type SourceSnapshot } from "../contracts.js";
import { normalizePlan } from "../plan.js";

type Row=Record<string,unknown>; type Proc={pid:number;elapsed:number;args:string};
export class OpenCodeSource {
  constructor(private dbPath=join(process.env.HOME??"", ".local/share/opencode/opencode.db"),private limit=20){}
  snapshot(now:number):SourceSnapshot {
    if(!existsSync(this.dbPath)) return {active:[],recent:[],warning:"OpenCode database not found"};
    const db=new DatabaseSync(this.dbPath,{readOnly:true});
    try {
      const rows=db.prepare("SELECT id,directory,title,agent,model,cost,tokens_input,tokens_output,tokens_reasoning,tokens_cache_read,tokens_cache_write,time_created,time_updated FROM session ORDER BY time_updated DESC LIMIT 100").all() as Row[];
      const procs=this.processes(), activeIds=new Map<string,Proc>();
      for(const p of procs){ const m=p.args.match(/(?:--session|-s)[= ]+(ses_[A-Za-z0-9]+)/); if(m?.[1]) activeIds.set(m[1],p); }
      const activeRows=rows.filter(r=>activeIds.has(String(r.id)) || now-Number(r.time_updated)<90_000);
      const ids=new Set(activeRows.map(r=>String(r.id))), recentRows=rows.filter(r=>!ids.has(String(r.id))).slice(0,this.limit);
      const all=[...activeRows,...recentRows], messageCounts=new Map<string,number>(), todos=new Map<string,Array<{content:string,status:string,priority:string}>>();
      for(const r of all){ const id=String(r.id); messageCounts.set(id,Number((db.prepare("SELECT COUNT(*) count FROM message WHERE session_id=?").get(id) as Row)?.count??0)); todos.set(id,(db.prepare("SELECT content,status,priority FROM todo WHERE session_id=? ORDER BY position").all(id) as Row[]).map(t=>({content:String(t.content),status:String(t.status),priority:String(t.priority)}))); }
      const normalize=(r:Row,isActive:boolean):Session=>{const raw=String(r.id),p=activeIds.get(raw),elapsed=isActive?(p?.elapsed??Math.max(0,Math.floor((now-Number(r.time_created))/1000))):0,items=todos.get(raw)??[],title=clean(r.title||"OpenCode session"); let last=title;
        for(const part of db.prepare("SELECT data FROM part WHERE session_id=? ORDER BY time_created DESC LIMIT 40").all(raw) as Row[]){try{const d=JSON.parse(String(part.data)) as {type?:string;text?:string};if(d.type==="text"&&clean(d.text)){last=clean(d.text);break}}catch{}}
        let model=String(r.model??"");try{model=String((JSON.parse(model) as {id?:string}).id??model)}catch{}
        return {id:`opencode:${raw}`,source_id:raw,source:"opencode",title,repo:basename(String(r.directory??""))||"opencode",directory:String(r.directory??""),agent:String(r.agent??""),model,status:isActive?"running":"idle",is_active:isActive,elapsed_seconds:elapsed,formatted_time:isActive?duration(elapsed):"",message_count:messageCounts.get(raw)??0,tokens_total:0,tokens_input:Number(r.tokens_input??0),tokens_output:Number(r.tokens_output??0),tokens_reasoning:Number(r.tokens_reasoning??0),tokens_cache_read:Number(r.tokens_cache_read??0),tokens_cache_write:Number(r.tokens_cache_write??0),cost:Number(r.cost??0),created_at:Number(r.time_created??0),updated_at:Number(r.time_updated??0),last_activity:last,todo:normalizePlan(items,isActive),proc_count:p?1:0};};
      return {active:activeRows.map(r=>normalize(r,true)).sort(byUpdated),recent:recentRows.map(r=>normalize(r,false)).sort(byUpdated)};
    } finally {db.close()}
  }
  private processes():Proc[]{try{return execFileSync("ps",["-eo","pid=,etimes=,comm=,args="],{encoding:"utf8"}).split("\n").flatMap(line=>{const m=line.trim().match(/^(\d+)\s+(\d+)\s+(\S+)\s+(.+)$/);if(!m)return[];const exe=basename(m[4]!.split(/\s+/,1)[0]!);if(!isOpenCodeProcess(m[3]!,exe))return[];return[{pid:Number(m[1]),elapsed:Number(m[2]),args:m[4]!}]})}catch{return[]}}
}
export const isOpenCodeProcess=(comm:string,executable:string)=>[comm,executable].map(x=>x.toLowerCase()).some(name=>name==="opencode"||name.startsWith("opencode-"));
const byUpdated=(a:Session,b:Session)=>b.updated_at-a.updated_at||b.id.localeCompare(a.id);
