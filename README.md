# 🏝️ Claw Island

> vivo OriginOS 5/6 原子岛 & 状态栏常驻实时监控系统 for OpenCode & Codex

---

## 📁 目录结构

```
claw-island/
├── docs/                      # 架构方案与进度追踪 (mmddyy-title.md)
│   └── 082226-architecture-and-feature-plan.md
├── server-ts/                 # TypeScript bridge source (Port 18818)
│   ├── sources/              # OpenCode + Codex read-only adapters
│   ├── contracts.ts          # Zod runtime contract + inferred types
│   ├── aggregator.ts         # provider-neutral merge
│   ├── event-store.ts        # persistent lifecycle sequence/ring
│   └── app.ts                # Fastify REST/SSE transport
├── server/                    # persisted lifecycle event state
├── server-python-legacy/      # retired Python reference implementation
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
├── macos/                     # macOS 14+ native SwiftUI menu-bar client
│   ├── Sources/ClawIslandMac/
│   ├── scripts/self-test.sh
│   └── scripts/package-app.sh
├── opencode.json              # OpenCode 配置 (opencode-go)
├── opencode.sh                # OpenCode 启动脚本
├── .env                       # API Key 配置
└── README.md
```

---

## 🚀 启动与使用

### 1. Linux Server (cutuy-claw)
```bash
cd /home/cutuy/.openclaw/workspace/claw-island
npm install
npm run build
npm start
```
- 端口：`18818`
- 聚合会话：`GET http://100.64.0.4:18818/api/sessions`
- 会话快照 + 生命周期 SSE：`GET http://100.64.0.4:18818/api/sessions/events`
- 最近生命周期事件：`GET http://100.64.0.4:18818/api/sessions/events/latest`
- 旧版兼容接口：`GET /api/status`、`GET /api/events`

会话 ID 使用 `<source>:<source_id>`，并显式包含 `source`（`opencode` 或
`codex`）。Codex 元数据来自只读的 `~/.codex/state_5.sqlite`，运行状态与
最新 activity 来自只读的 `~/.codex/thread_history_1.sqlite`。单个数据源不可用
时会降级并在 `warnings` 中报告，不会令 SSE bridge 崩溃。

开发验证：`npm run check && npm test && npm run build`。服务只使用 Node
内置的只读 SQLite API，不依赖 native addon。

### 2. Android Client 构建 (Workstation 100.64.0.7)
```bash
ssh cutuy@100.64.0.7
cd /home/cutuy/claw-island/android
./gradlew assembleDebug
```
- Android SDK、Gradle 构建、模拟器与设备操作仅在 `100.64.0.7` 上执行。
- 生成 APK 并安装到 vivo 手机（Android 16 / OriginOS 5+）。
- 手机端设置：在「设置 -> 通知与状态栏 -> 原子通知」中确保打开 Claw Island 的原子岛与锁屏显示权限。

#### Optional FCM fallback

The Android app continues to work with SSE when Firebase is absent. To enable
push delivery while SSE is disconnected, register the package
`com.cutuy.clawisland` in Firebase and provide these values when building:

```bash
export CLAW_FIREBASE_APP_ID='...'
export CLAW_FIREBASE_API_KEY='...'
export CLAW_FIREBASE_PROJECT_ID='...'
export CLAW_FIREBASE_SENDER_ID='...'
export CLAW_PUSH_PAIRING_TOKEN='optional-shared-value'
./gradlew assembleDebug
```

The first four values are Android app/project identifiers from
`google-services.json`, not server credentials. Keep the Admin SDK private-key
JSON outside this repository. The bridge reads its path from
`FIREBASE_SERVICE_ACCOUNT_FILE` (or `GOOGLE_APPLICATION_CREDENTIALS`) and may
enforce the matching `CLAW_ISLAND_PUSH_PAIRING_TOKEN`. FCM primarily targets
devices with Google Play services; devices without GMS retain the SSE path.

### 3. macOS menu-bar client

On a Mac with Swift 6/Xcode installed:

```bash
cd macos
./scripts/self-test.sh
./scripts/package-app.sh
open "dist/Claw Island.app"
```

The macOS app uses SSE cursor replay and reconnects after sleep or network
changes. APNs is not required for this resident menu-bar version.

### 4. OpenCode 辅助编码
```bash
./opencode.sh          # 交互式 TUI
./opencode.sh run "..." # 命令行非交互式任务
```
