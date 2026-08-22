# Claw Island — OriginOS 5/6 原子岛 & 常驻状态栏实时监控

**Document ID:** `082226-architecture-and-feature-plan.md`  
**Date:** 2026-08-22  
**Status:** Implemented & Verified on Android 16 (API 36)  
**Target Device:** vivo / iQOO (OriginOS 5+, Android 16, API 36)  
**Host:** cutuy-claw (Gateway Server) & 100.64.0.7 (Workstation Build Environment)

---

## 1. 目标与背景

为了在离开电脑时随时感知 OpenClaw 和 OpenCode 的工作状态（如：正在运行的任务、修改的文件、执行的命令、耗时、模型状态），本项目构建一套：
1. **Linux Server (Daemon/Bridge)**：运行在 OpenClaw Gateway 宿主机上，汇聚 OpenCode、OpenClaw Sub-agents 及系统任务状态，提供 SSE / WebSocket / REST 实时状态推送。
2. **Android 客户端 (ClawIsland App)**：目标 Android 16，针对 vivo OriginOS 5/6 原生适配**原子岛（Atomic Island）胶囊**与**通知大卡片**，并在不支持机型上平滑降级为 Android 标准 Live Activity / Ongoing Notification。

---

## 2. 整体架构 (Architecture)

```
┌─────────────────────────────────────────────────────────────┐
│                 OpenClaw Gateway (cutuy-claw)               │
│                                                             │
│  ┌────────────────┐   ┌────────────────┐   ┌─────────────┐  │
│  │ OpenCode Agent │   │ Subagents /    │   │ OpenClaw    │  │
│  │ Processes/Logs │   │ Tasks Monitor  │   │ Actions     │  │
│  └───────┬────────┘   └───────┬────────┘   └──────┬──────┘  │
│          │                    │                   │         │
│          ▼                    ▼                   ▼         │
│     ┌─────────────────────────────────────────────────┐     │
│     │         Claw Island Status Bridge (Server)       │     │
│     │         • Aggregates live state every 1~2s      │     │
│     │         • SSE: GET /api/events                  │     │
│     │         • REST: GET /api/status                 │     │
│     └─────────────────────────┬───────────────────────┘     │
└───────────────────────────────┼─────────────────────────────┘
                                │ Tailscale Mesh (100.64.0.x)
                                │ SSE Stream (JSON)
                                ▼
┌─────────────────────────────────────────────────────────────┐
│             vivo Phone (Android 16 / OriginOS 5+)           │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ ClawIsland Foreground Service                         │  │
│  │ • OkHttp SSE Client with auto-reconnect               │  │
│  │ • Throttle manager (respects vivo 10s refresh rule)   │  │
│  └───────────────────────────┬───────────────────────────┘  │
│                              │                              │
│                              ▼                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Vivo SuperX Bundle Engine                             │  │
│  │ • Left Island: Icon + "OpenCode" / "OpenClaw"         │  │
│  │ • Right Island: Animated pulse / Step text            │  │
│  │ • Expanded Card: Task detail, changed files, timer    │  │
│  │ • Fallback: Android 16 Ongoing Live Notification      │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. vivo OriginOS 原子通知 / 原子岛协议规范

vivo OriginOS 通过在原生 `Notification` 的 `extras` 中注入 `notification.superx` 树形 Bundle 实现原子岛与小卡/大卡渲染：

### 3.1 核心控制字段
- `notification.superx.operation`: `0` (Create), `1` (Update), `2` (Dismiss)
- `notification.superx.displays`: `0x111` (状态栏/原子岛 + 锁屏 + 通知中心)
- `notification.superx.dismissWhenKill`: `true`
- `notification.superx.sound`: `false`

### 3.2 原子岛胶囊字段 (`notification.superx.island`)
- `island.superx.leftTemplate`: `1` (Icon + Text)
- `island.superx.leftInfo`: Bundle 包含 title, icon
- `island.superx.rightTemplate`: 
  - `1`: 律动动画
  - `2`: 进度动画
  - `3`: 加载中转圈动画
  - `4`: 文本+图片
  - `6`: 纯胶囊文本 (如 `Building...`, `Thinking`)
- `island.superx.rightInfo`: Bundle 包含 content
- `island.superx.islandClick`: `0` (展开大卡) / `1` (跳落地页)

### 3.3 展开大卡模版
- `island.superx.template`: `1` (强调信息模版) 或 `4` (基础模版)
- `island.superx.baseInfos`: Bundle 包含 title, state, content, icon

---

## 4. 状态数据格式 (JSON Schema)

```json
{
  "active": true,
  "agent": "OpenCode",
  "status": "running",
  "state_type": "coding",
  "title": "Refactoring store.ts",
  "detail": "3 files changed • +45/-12 lines",
  "model": "glm-5.3",
  "proc_count": 1,
  "updated_at": 1755846640000
}
```

---

## 5. 进度跟踪 (Progress Tracking)

- [x] 创建新 Repo `claw-island`
- [x] 配置 `opencode.json`, `opencode.sh`, `.env`, `.gitignore`
- [x] 建立 `docs/` 并编写架构方案与追踪文档
- [x] 编写 Linux Server 端（实时汇聚 OpenCode 进程/状态并输出 SSE/REST）
- [x] 编写 Android 16 Kotlin 客户端框架
- [x] 实现 OriginOS 5/6 SuperX Bundle 封装与降级兼容
- [x] 在 100.64.0.7 Workstation 上配置构建脚本并编译 APK (`BUILD SUCCESSFUL in 1m 16s`)
- [x] 生成 `claw-island-debug.apk` 并就绪实机调试
