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

app = FastAPI(title="Claw Island Status Bridge", version="1.1.0")

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

# Process tracking cache: pid -> first_seen_timestamp
proc_start_times: Dict[int, float] = {}
custom_status: Optional[Dict[str, Any]] = None

def format_duration(seconds: int) -> str:
    """Format seconds into HH:MM:SS or MM:SS."""
    if seconds < 0:
        seconds = 0
    m, s = divmod(seconds, 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"

def get_opencode_process_details() -> list:
    """Check running opencode processes and get PID, elapsed seconds, command."""
    global proc_start_times
    procs = []
    try:
        res = subprocess.run(["ps", "-eo", "pid,etimes,cmd"], capture_output=True, text=True)
        current_pids = set()
        for line in res.stdout.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(maxsplit=2)
            if len(parts) < 3:
                continue
            pid_str, etimes_str, cmd = parts[0], parts[1], parts[2]
            if "opencode" in cmd and "grep" not in cmd and "python" not in cmd and "server.py" not in cmd:
                try:
                    pid = int(pid_str)
                    etimes = int(etimes_str)
                    current_pids.add(pid)
                    procs.append({
                        "pid": pid,
                        "elapsed_seconds": etimes,
                        "cmd": cmd
                    })
                except ValueError:
                    pass
        # Clean stale pids
        proc_start_times = {p: t for p, t in proc_start_times.items() if p in current_pids}
    except Exception:
        pass
    return procs

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

    opencode_procs = get_opencode_process_details()
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
        main_proc = opencode_procs[0]
        elapsed = main_proc["elapsed_seconds"]
        cmd_str = main_proc["cmd"]
        cmd_clean = cmd_str.split("opencode", 1)[-1].strip()
        if len(cmd_clean) > 80:
            cmd_clean = cmd_clean[:77] + "..."
            
        formatted_time = format_duration(elapsed)
        return {
            "active": True,
            "agent": "OpenCode",
            "status": "running",
            "state_type": "coding",
            "title": f"OpenCode ({formatted_time})",
            "detail": cmd_clean if cmd_clean else "Generating & running tools",
            "model": "glm-5.3",
            "elapsed_seconds": elapsed,
            "formatted_time": formatted_time,
            "proc_count": len(opencode_procs),
            "updated_at": now_ts
        }

    if is_subagent_running:
        task_name = subagent_data.get("task", "Subagent Task")
        reports = subagent_data.get("reports", [])
        last_report = reports[-1].get("progress", "") if reports else ""
        started_at = subagent_data.get("startedAt", "")
        # Parse elapsed time if possible
        elapsed = 0
        formatted_time = ""
        return {
            "active": True,
            "agent": "Subagent",
            "status": "running",
            "state_type": "thinking",
            "title": f"Subagent: {task_name}",
            "detail": last_report or "Thinking and researching...",
            "model": "ox-alpha-free",
            "elapsed_seconds": elapsed,
            "formatted_time": formatted_time,
            "proc_count": 1,
            "updated_at": now_ts
        }

    if is_action_active:
        return {
            "active": True,
            "agent": "OpenClaw",
            "status": "executing",
            "state_type": "active",
            "title": "OpenClaw Active",
            "detail": current_action.splitlines()[0][:80],
            "model": "ox-alpha-free",
            "elapsed_seconds": 0,
            "formatted_time": "",
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
        "elapsed_seconds": 0,
        "formatted_time": "00:00",
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
        while True:
            if await request.is_disconnected():
                break
            
            status = get_aggregated_status()
            data = json.dumps(status, ensure_ascii=False)
            yield f"data: {data}\n\n"
            
            await asyncio.sleep(2.0)

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
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
