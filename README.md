# 🏝️ Claw Island

> vivo OriginOS 5/6 原子岛 & 状态栏常驻实时监控系统 for OpenClaw & OpenCode

---

## 📁 目录结构

```
claw-island/
├── docs/                      # 架构方案与进度追踪 (mmddyy-title.md)
│   └── 082226-architecture-and-feature-plan.md
├── server/                    # Linux Gateway 状态监控与 SSE 服务 (Port 18818)
│   ├── server.py
│   └── requirements.txt
├── android/                   # Android 16 (API 36) Kotlin 客户端 App
│   ├── app/
│   │   └── src/main/java/com/cutuy/clawisland/
│   │       ├── MainActivity.kt
│   │       ├── models/AgentStatus.kt
│   │       ├── notification/VivoSuperXHelper.kt      # vivo SuperX 原子岛协议
│   │       ├── notification/StandardNotificationHelper.kt
│   │       └── service/ClawIslandService.kt          # 前台保活 + SSE 长连接
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── opencode.json              # OpenCode 配置 (opencode-go)
├── opencode.sh                # OpenCode 启动脚本
├── .env                       # API Key 配置
└── README.md
```

---

## 🚀 启动与使用

### 1. Linux Server (cutuy-claw)
```bash
cd /home/cutuy/.openclaw/workspace/claw-island/server
pip install -r requirements.txt
python3 server.py
```
- 端口：`18818`
- 状态接口：`GET http://100.64.0.4:18818/api/status`
- SSE 实时事件流：`GET http://100.64.0.4:18818/api/events`

### 2. Android Client 构建 (Workstation 100.64.0.7)
```bash
cd /home/cutuy/.openclaw/workspace/claw-island/android
./gradlew assembleDebug
```
- 生成 APK 并安装到 vivo 手机（Android 16 / OriginOS 5+）。
- 手机端设置：在「设置 -> 通知与状态栏 -> 原子通知」中确保打开 Claw Island 的原子岛与锁屏显示权限。

### 3. OpenCode 辅助编码
```bash
./opencode.sh          # 交互式 TUI
./opencode.sh run "..." # 命令行非交互式任务
```
