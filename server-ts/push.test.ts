import { afterEach, describe, expect, it, vi } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { DeviceRegistry } from "./device-registry.js";
import type { LifecycleEvent } from "./contracts.js";
import type { PushResult, PushSender } from "./fcm.js";
import { PushDispatcher } from "./push-dispatcher.js";

const dirs: string[] = [];
afterEach(() => { for (const dir of dirs) rmSync(dir, { recursive: true, force: true }); dirs.length = 0; });

describe("DeviceRegistry", () => {
  it("persists registrations and token removal atomically", () => {
    const path = statePath();
    const devices = new DeviceRegistry(path);
    devices.upsert({ deviceId: "android-demo", platform: "android", token: "token-12345678901234567890" });
    expect(new DeviceRegistry(path).list()).toMatchObject([{ deviceId: "android-demo", platform: "android" }]);
    devices.removeByToken("token-12345678901234567890");
    expect(new DeviceRegistry(path).list()).toEqual([]);
  });
});

describe("PushDispatcher", () => {
  it("suppresses FCM for active SSE devices and removes invalid tokens", async () => {
    const devices = new DeviceRegistry(statePath());
    devices.upsert({ deviceId: "connected-1", platform: "android", token: "connected-token-1234567890" });
    devices.upsert({ deviceId: "offline-1", platform: "android", token: "expired-token-123456789012" });
    const send = vi.fn(async (device): Promise<PushResult> => device.deviceId === "offline-1" ? "unregistered" : "sent");
    const sender: PushSender = { configured: true, send };
    await new PushDispatcher(devices, sender).deliver([event()], new Set(["connected-1"]));
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0]![0].deviceId).toBe("offline-1");
    expect(devices.list().map(device => device.deviceId)).toEqual(["connected-1"]);
  });

  it("does nothing when FCM is unconfigured", async () => {
    const devices = new DeviceRegistry(statePath());
    devices.upsert({ deviceId: "offline-1", platform: "android", token: "token-12345678901234567890" });
    const send = vi.fn(async (): Promise<PushResult> => "sent");
    await new PushDispatcher(devices, { configured: false, send }).deliver([event()], new Set());
    expect(send).not.toHaveBeenCalled();
  });
});

function statePath(): string {
  const dir = mkdtempSync(join(tmpdir(), "claw-push-")); dirs.push(dir); return join(dir, "devices.json");
}

function event(): LifecycleEvent {
  return { id: "evt_demo", seq: 1, type: "session_started", ts: 1, session: { id: "codex:x", source: "codex", repo: "claw-island", title: "Demo" } };
}
