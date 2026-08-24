import { readFileSync, renameSync, writeFileSync } from "node:fs";

export type PushDevice = {
  deviceId: string;
  platform: "android";
  token: string;
  updatedAt: number;
};

export class DeviceRegistry {
  private devices = new Map<string, PushDevice>();

  constructor(private readonly path: string) { this.load(); }

  list(): PushDevice[] { return [...this.devices.values()]; }

  upsert(device: Omit<PushDevice, "updatedAt">): PushDevice {
    const value = { ...device, updatedAt: Date.now() };
    this.devices.set(value.deviceId, value);
    this.save();
    return value;
  }

  remove(deviceId: string): boolean {
    const removed = this.devices.delete(deviceId);
    if (removed) this.save();
    return removed;
  }

  removeByToken(token: string): void {
    let changed = false;
    for (const [id, device] of this.devices) {
      if (device.token === token) { this.devices.delete(id); changed = true; }
    }
    if (changed) this.save();
  }

  private load(): void {
    try {
      const stored = JSON.parse(readFileSync(this.path, "utf8")) as { devices?: PushDevice[] };
      for (const device of stored.devices ?? []) {
        if (device.deviceId && device.platform === "android" && device.token) this.devices.set(device.deviceId, device);
      }
    } catch { /* Missing or corrupt state starts with an empty registry. */ }
  }

  private save(): void {
    const temp = `${this.path}.tmp`;
    writeFileSync(temp, JSON.stringify({ devices: this.list() }));
    renameSync(temp, this.path);
  }
}
