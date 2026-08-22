# Claw Island — 构建与实机调试验证文档

**Document ID:** `082226-build-and-integration.md`  
**Date:** 2026-08-22  
**Status:** Built & Verified  
**Target Device:** vivo / iQOO (Android 16, API 36, OriginOS 5+)

---

## 1. 编译构建记录 (Build Log)

- **构建机器：** `cutuy-powerhouse` (`100.64.0.7`)
- **Android SDK：** `/home/cutuy/Android/Sdk` (API Level 36)
- **Gradle 版本：** Gradle 9.6.1 + AGP 8.8.0
- **Kotlin 版本：** 2.1.0 (Jetpack Compose + Coroutines + Serialization)
- **编译结果：** `BUILD SUCCESSFUL in 1m 16s`
- **产物文件：**
  - Workstation: `/home/cutuy/claw-island/android/app/build/outputs/apk/debug/app-debug.apk`
  - Gateway: `/home/cutuy/.openclaw/workspace/claw-island/claw-island-debug.apk` (23MB)

---

## 2. 手机端安装与运行步骤

### 2.1 安装 APK
如果手机连接在 100.64.0.7 上或通过无线调试 (adb connect):
```bash
adb install -r /home/cutuy/claw-island/android/app/build/outputs/apk/debug/app-debug.apk
```
或者在浏览器/微信/文件传输直接下载 `claw-island-debug.apk` 安装。

### 2.2 vivo OriginOS 系统权限配置 (关键)
1. **允许通知与常驻**：打开 App，授予通知权限。
2. **原子通知与原子岛开关**：
   - 打开「设置」->「通知与状态栏」->「原子通知」
   - 找到 **Claw Island**，确保打开 **「原子岛」**、**「锁屏」** 和 **「状态栏」** 显示开关。
3. **后台保活**：在系统多任务界面中长按 Claw Island 加锁，并在「电池设置」中设置为「允许后台高耗电 / 无限制」。

---

## 3. Server 启动与实机联动

### 3.1 Gateway 启动 Server (cutuy-claw)
```bash
cd /home/cutuy/.openclaw/workspace/claw-island/server
python3 server.py
```
- 服务监听端口：`18818` (内网 Tailscale: `http://100.64.0.4:18818`)

### 3.2 客户端连接
1. 打开 Claw Island App。
2. 确认 Server SSE URL 为 `http://100.64.0.4:18818`。
3. 点击 **「启动原子岛常驻监控」**。
4. 手机顶部状态栏及摄像头两侧的原子岛即刻亮起！
5. 在电脑上运行 OpenCode 或 OpenClaw 任务时，手机原子岛胶囊将实时显示工作状态（`🔨 Coding` / `🧠 Thinking` / 文件修改），点击即可展开大卡片查看详情。
