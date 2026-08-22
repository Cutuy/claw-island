import os
import sys
import json
import time
import asyncio
import subprocess
from typing import Dict, Any, Optional
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

app = FastAPI(title="Claw Island Status Bridge", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

WORKSPACE_ROOT = os.path.expanduser("/home/cutuy/.openclaw/workspace")
CURRENT_ACTION_FILE = os.path.join(WORKSPACE_ROOT, "network-dashboard/current_action.md")
SUBAGENT_MONITOR_FILE = os.path.join(WORKSPACE_ROOT, "shared/subagent-monitor.json")

# In-memory manual override / hook state
custom_status: Optional[Dict[str, Any]] = None

def get_opencode_processes() -> list:
    """Check running opencode processes on the host."""
    try:
        res = subprocess.run(["pgrep", "-af", "opencode"], capture_output=True, text=True)
        lines = [line.strip() for line in res.stdout.splitlines() if line.strip()]
        # Filter out this python server or grep itself
        valid = [l for l in lines if "python" not in l and "pgrep" not in l and "grep" not in l]
        return valid
    except Exception:
        return []

def read_file_safe(path: str, max_chars: int = 1000) -> str:
    try:
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                return f.read(max_chars).strip()
    except Exception:
        pass
    return ""

def get_aggregated_status() -> Dict[str, Any]:
    global custom_status
    now_ts = int(time.time() * 1000)

    # If manual override is fresh (< 30s old)
    if custom_status and (now_ts - custom_status.get("updated_at", 0) < 30000):
        return custom_status

    opencode_procs = get_opencode_processes()
    current_action = read_file_safe(CURRENT_ACTION_FILE)
    
    subagent_data = {}
    if os.path.exists(SUBAGENT_MONITOR_FILE):
        try:
            with open(SUBAGENT_MONITOR_FILE, "r", encoding="utf-8") as f:
                subagent_data = json.load(f)
        except Exception:
            pass

    is_opencode_running = len(opencode_procs) > 0
    is_subagent_running = subagent_data.get("status") == "running"
    is_action_active = len(current_action) > 0

    if is_opencode_running:
        cmd_snippet = opencode_procs[0].split("opencode", 1)[-1].strip()
        if len(cmd_snippet) > 60:
            cmd_snippet = cmd_snippet[:57] + "..."
        return {
            "active": True,
            "agent": "OpenCode",
            "status": "running",
            "state_type": "coding",
            "title": "OpenCode Running",
            "detail": cmd_snippet if cmd_snippet else "Executing code generation & tools",
            "model": "glm-5.3",
            "proc_count": len(opencode_procs),
            "updated_at": now_ts
        }

    if is_subagent_running:
        task_name = subagent_data.get("task", "Subagent Task")
        reports = subagent_data.get("reports", [])
        last_report = reports[-1].get("progress", "") if reports else ""
        return {
            "active": True,
            "agent": "Subagent",
            "status": "running",
            "state_type": "thinking",
            "title": f"Subagent: {task_name}",
            "detail": last_report or "Subagent working...",
            "model": "ox-alpha-free",
            "proc_count": 1,
            "updated_at": now_ts
        }

    if is_action_active:
        return {
            "active": True,
            "agent": "OpenClaw",
            "status": "executing",
            "state_type": "active",
            "title": "OpenClaw Task",
            "detail": current_action.splitlines()[0][:80],
            "model": "ox-alpha-free",
            "proc_count": 1,
            "updated_at": now_ts
        }

    # Idle State
    return {
        "active": False,
        "agent": "OpenClaw",
        "status": "idle",
        "state_type": "idle",
        "title": "All Agents Idle",
        "detail": "Standing by for instructions",
        "model": "ox-alpha-free",
        "proc_count": 0,
        "updated_at": now_ts
    }

@app.get("/healthz")
def healthz():
    return {"status": "ok", "service": "claw-island-server", "port": 18818}

@app.get("/api/status")
def get_status():
    return get_aggregated_status()

@app.post("/api/status")
async def post_status(request: Request):
    global custom_status
    try:
        body = await request.json()
        body["updated_at"] = int(time.time() * 1000)
        custom_status = body
        return {"status": "success", "data": custom_status}
    except Exception as e:
        return JSONResponse(status_code=400, content={"error": str(e)})

@app.get("/api/events")
async def sse_events(request: Request):
    async def event_generator():
        last_hash = ""
        while True:
            if await request.is_disconnected():
                break
            
            status = get_aggregated_status()
            cur_hash = f"{status.get('agent')}-{status.get('status')}-{status.get('title')}-{status.get('detail')}"
            
            # Send status update on change or heartbeat every 4s
            data = json.dumps(status, ensure_ascii=False)
            yield f"data: {data}\n\n"
            
            await asyncio.sleep(2.5)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

if __name__ == "__main__":
    port = int(os.getenv("PORT", 18818))
    uvicorn.run("server:app", host="0.0.0.0", port=port, log_level="info")
