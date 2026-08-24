import Fastify from "fastify";
import cors from "@fastify/cors";
import { createHash, timingSafeEqual } from "node:crypto";
import { join } from "node:path";
import type { ServerResponse } from "node:http";
import { z } from "zod";
import { merge } from "./aggregator.js";
import { DeviceRegistry } from "./device-registry.js";
import { EventStore } from "./event-store.js";
import { FcmSender, type PushSender } from "./fcm.js";
import { PushDispatcher } from "./push-dispatcher.js";
import { CodexSource } from "./sources/codex.js";
import { OpenCodeSource } from "./sources/opencode.js";
import type { SessionsPayload } from "./contracts.js";

export function buildApp(options:{stateFile?:string;devicesFile?:string;pushSender?:PushSender}={}){
 const app=Fastify({logger:true}),clients=new Set<ServerResponse>(),activeDevices=new Map<string,number>(),events=new EventStore(options.stateFile??join(import.meta.dirname,"../server/.events-state.json"));
 const devices=new DeviceRegistry(options.devicesFile??join(import.meta.dirname,"../server/.push-devices.json")),push=new PushDispatcher(devices,options.pushSender??FcmSender.fromEnvironment());
 const sources=[new OpenCodeSource(),new CodexSource()];let payload:SessionsPayload={now_ts:Date.now(),active:[],recent:[]},wire:any={now_ts:Date.now(),active:[],recent:[]},hash="",custom:any=null;
 const tick=()=>{const now=Date.now(),snapshots=sources.map(source=>{try{return source.snapshot(now)}catch(e){return{active:[],recent:[],warning:String(e)}}});payload=merge(now,snapshots);const emitted=events.reconcile(payload);wire=toWire(payload);hash=createHash("sha256").update(JSON.stringify(hashableWire(wire))).digest("hex");if(emitted.length)void push.deliver(emitted,new Set(activeDevices.keys())).catch(error=>app.log.warn({error},"push delivery failed"))};tick();
 const timer=setInterval(tick,2000);timer.unref(); app.register(cors,{origin:true,credentials:false});
 app.get("/healthz",()=>({status:"ok",service:"claw-island-server",port:Number(process.env.PORT??18818),push:{configured:push.configured,devices:devices.list().length}}));
 app.get("/api/sessions",()=>wire);
 app.get("/api/sessions/events/latest",(req:any)=>{const limit=Math.max(1,Math.min(Number(req.query?.limit??20),100));return{seq:events.current(),max_seq:events.current(),events:events.latest(limit)}});
 app.get("/api/status",()=>{if(custom&&Date.now()-custom.updated_at<30_000)return custom;const top=payload.active[0];return top?{active:true,agent:top.source==="codex"?"Codex":"OpenCode",status:"running",state_type:"coding",title:top.title,detail:top.last_activity,model:top.model,elapsed_seconds:top.elapsed_seconds,formatted_time:top.formatted_time,proc_count:top.proc_count,updated_at:Date.now()}:{active:false,agent:"OpenClaw",status:"idle",state_type:"idle",title:"All Agents Idle",detail:"Standing by for instructions",model:"",elapsed_seconds:0,formatted_time:"00:00",proc_count:0,updated_at:Date.now()}});
 app.post("/api/status",async(req:any)=>{custom={...(req.body??{}),updated_at:Date.now()};return{status:"success",data:custom}});
 const DeviceSchema=z.object({device_id:z.string().min(8).max(128).regex(/^[A-Za-z0-9._:-]+$/),platform:z.literal("android"),token:z.string().min(20).max(4096)});
 app.post("/api/push/devices",async(req:any,reply)=>{if(!pairingAllowed(req.headers["x-claw-pairing-token"]))return reply.code(401).send({error:"invalid pairing token"});const parsed=DeviceSchema.safeParse(req.body);if(!parsed.success)return reply.code(400).send({error:"invalid device registration"});const value=devices.upsert({deviceId:parsed.data.device_id,platform:parsed.data.platform,token:parsed.data.token});return{status:"registered",device_id:value.deviceId,push_configured:push.configured}});
 app.delete("/api/push/devices/:deviceId",async(req:any,reply)=>{if(!pairingAllowed(req.headers["x-claw-pairing-token"]))return reply.code(401).send({error:"invalid pairing token"});return{status:devices.remove(String(req.params.deviceId))?"removed":"not_found"}});
 const openSse=(reply:any)=>{reply.hijack();const res=reply.raw as ServerResponse;clients.add(res);res.writeHead(200,{"Content-Type":"text/event-stream","Cache-Control":"no-cache","Connection":"keep-alive","X-Accel-Buffering":"no"});res.on("close",()=>clients.delete(res));return res};
 app.get("/api/events",(req,reply)=>{const res=openSse(reply);const send=()=>res.write(`data: ${JSON.stringify((app as any).legacyStatus?.()??(payload.active[0]?{active:true,agent:payload.active[0].source==="codex"?"Codex":"OpenCode",status:"running",title:payload.active[0].title,detail:payload.active[0].last_activity,updated_at:Date.now()}:{active:false,agent:"OpenClaw",status:"idle",title:"All Agents Idle",detail:"Standing by",updated_at:Date.now()}))}\n\n`);send();const id=setInterval(send,2000);res.on("close",()=>clearInterval(id))});
 app.get("/api/sessions/events",(req:any,reply)=>{const deviceId=validDeviceId(req.query?.device_id)?String(req.query.device_id):"";if(deviceId)activeDevices.set(deviceId,(activeDevices.get(deviceId)??0)+1);const res=openSse(reply);let cursor=Number(req.query?.after??events.current());if(!Number.isFinite(cursor)||cursor>events.current())cursor=0;let last="",lastFullAt=0;const send=()=>{const pending=events.after(cursor);if(pending.length)cursor=pending.at(-1)!.seq;const now=Date.now(),full=!last||pending.length>0||(last!==hash&&now-lastFullAt>=10_000);if(!full)res.write(`data: ${JSON.stringify({now_ts:wire.now_ts,delta:false,max_seq:events.current(),events:[]})}\n\n`);else{res.write(`data: ${JSON.stringify({...wire,delta:true,max_seq:events.current(),events:pending})}\n\n`);last=hash;lastFullAt=now}};send();const id=setInterval(send,2000);res.on("close",()=>{clearInterval(id);if(deviceId){const count=(activeDevices.get(deviceId)??1)-1;if(count>0)activeDevices.set(deviceId,count);else activeDevices.delete(deviceId)}})});
 app.addHook("onClose",async()=>{clearInterval(timer);for(const client of clients)client.end();clients.clear()});
 return {app,closeClients:()=>{for(const client of clients)client.end();clients.clear()}};
}

function validDeviceId(value:unknown):boolean{return typeof value==="string"&&/^[A-Za-z0-9._:-]{8,128}$/.test(value)}
function pairingAllowed(provided:unknown):boolean{const expected=process.env.CLAW_ISLAND_PUSH_PAIRING_TOKEN;if(!expected)return true;if(typeof provided!=="string")return false;const a=Buffer.from(expected),b=Buffer.from(provided);return a.length===b.length&&timingSafeEqual(a,b)}

export function toWire(payload:SessionsPayload){
 const active=payload.active.map(({source_id,directory,agent,status,created_at,tokens_reasoning,tokens_cache_write,...session})=>session);
 const recent=payload.recent.map(session=>({id:session.id,source:session.source,title:session.title,repo:session.repo,model:session.model,message_count:session.message_count,tokens_total:session.tokens_total,tokens_input:session.tokens_input,tokens_output:session.tokens_output,cost:session.cost,updated_at:session.updated_at,todo:{total:session.todo.total,completed:session.todo.completed,in_progress:session.todo.in_progress,pending:session.todo.pending,items:[]}}));
 return {now_ts:payload.now_ts,active,recent,...(payload.warnings?{warnings:payload.warnings}:{})};
}

function hashableWire(wire:any){
 return {...wire,now_ts:0,active:wire.active.map(({elapsed_seconds,formatted_time,...session}:any)=>session)};
}
