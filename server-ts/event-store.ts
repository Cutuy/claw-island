import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import type { LifecycleEvent, LifecycleType, Session, SessionsPayload } from "./contracts.js";
export class EventStore{private seq=0;private ring:LifecycleEvent[]=[];private previous:Map<string,Session>|null=null;constructor(private path:string,private size=100){this.load()}
 reconcile(payload:SessionsPayload){const emitted:LifecycleEvent[]=[];const current=new Map(payload.active.map(s=>[s.id,s]));if(this.previous){for(const [id,s] of current)if(!this.previous.has(id))emitted.push(this.emit(payload.now_ts-s.created_at<600_000?"session_started":"session_resumed",payload.now_ts,s));for(const[id,s]of this.previous)if(!current.has(id))emitted.push(this.emit("session_finished",payload.now_ts,s))}this.previous=current;return emitted}
 after(cursor:number){return this.ring.filter(e=>e.seq>cursor)} current(){return this.seq} latest(n=20){return this.ring.slice(-n).reverse()}
 private emit(type:LifecycleType,ts:number,s:Session){const event={id:`evt_${randomUUID().slice(0,12)}`,seq:++this.seq,type,ts,session:{id:s.id,source_id:s.source_id,source:s.source,repo:s.repo,title:s.title,model:s.model,elapsed_seconds:s.elapsed_seconds}} satisfies LifecycleEvent;this.ring.push(event);this.ring=this.ring.slice(-this.size);this.save();return event}
 private load(){try{const x=JSON.parse(readFileSync(this.path,"utf8")) as {seq:number;events:LifecycleEvent[]};this.seq=x.seq??0;this.ring=(x.events??[]).slice(-this.size)}catch{}}
 private save(){try{const tmp=`${this.path}.tmp`;writeFileSync(tmp,JSON.stringify({seq:this.seq,events:this.ring.slice(-50)}));renameSync(tmp,this.path)}catch{}}
}
