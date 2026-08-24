import type { LifecycleEvent } from "./contracts.js";
import type { DeviceRegistry } from "./device-registry.js";
import type { PushSender } from "./fcm.js";

export class PushDispatcher {
  constructor(private readonly devices: DeviceRegistry, private readonly sender: PushSender) {}

  get configured(): boolean { return this.sender.configured; }

  async deliver(events: LifecycleEvent[], activeDeviceIds: ReadonlySet<string>): Promise<void> {
    if (!events.length || !this.sender.configured) return;
    const targets = this.devices.list().filter(device => !activeDeviceIds.has(device.deviceId));
    for (const event of events) {
      await Promise.allSettled(targets.map(async device => {
        const result = await this.sender.send(device, event);
        if (result === "unregistered") this.devices.removeByToken(device.token);
      }));
    }
  }
}
