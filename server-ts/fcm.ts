import { createSign } from "node:crypto";
import { readFileSync } from "node:fs";
import type { LifecycleEvent } from "./contracts.js";
import type { PushDevice } from "./device-registry.js";

export type PushResult = "sent" | "unregistered" | "failed" | "unconfigured";
export interface PushSender {
  readonly configured: boolean;
  send(device: PushDevice, event: LifecycleEvent): Promise<PushResult>;
}

type ServiceAccount = { client_email: string; private_key: string; project_id: string };

export class FcmSender implements PushSender {
  private accessToken: { value: string; expiresAt: number } | null = null;

  constructor(private readonly account: ServiceAccount | null, private readonly fetcher: typeof fetch = fetch) {}

  static fromEnvironment(): FcmSender {
    const path = process.env.FIREBASE_SERVICE_ACCOUNT_FILE || process.env.GOOGLE_APPLICATION_CREDENTIALS;
    if (!path) return new FcmSender(null);
    try {
      const parsed = JSON.parse(readFileSync(path, "utf8")) as ServiceAccount;
      return parsed.client_email && parsed.private_key && parsed.project_id ? new FcmSender(parsed) : new FcmSender(null);
    } catch { return new FcmSender(null); }
  }

  get configured(): boolean { return this.account !== null; }

  async send(device: PushDevice, event: LifecycleEvent): Promise<PushResult> {
    if (!this.account) return "unconfigured";
    try {
      const token = await this.getAccessToken();
      const response = await this.fetcher(`https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
        body: JSON.stringify({ message: buildMessage(device.token, event) }),
      });
      if (response.ok) return "sent";
      const body = await response.text();
      if (response.status === 404 || body.includes("UNREGISTERED")) return "unregistered";
      return "failed";
    } catch { return "failed"; }
  }

  private async getAccessToken(): Promise<string> {
    if (this.accessToken && this.accessToken.expiresAt - Date.now() > 60_000) return this.accessToken.value;
    const assertion = createAssertion(this.account!);
    const response = await this.fetcher("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
    });
    if (!response.ok) throw new Error(`FCM OAuth failed: ${response.status}`);
    const body = await response.json() as { access_token: string; expires_in?: number };
    this.accessToken = { value: body.access_token, expiresAt: Date.now() + (body.expires_in ?? 3600) * 1000 };
    return body.access_token;
  }
}

function buildMessage(token: string, event: LifecycleEvent) {
  const repo = short(event.session.repo, 40) || "session";
  const sessionTitle = short(event.session.title, 80) || "Agent session";
  const title = event.type === "session_started" ? `Session started · ${repo}`
    : event.type === "session_resumed" ? `Session resumed · ${repo}`
    : event.type === "session_exited" ? `Session exited · ${repo}`
    : `Session finished · ${repo}`;
  return {
    token,
    data: {
      event_id: event.id,
      seq: String(event.seq),
      event_type: event.type,
      session_id: String(event.session.id ?? ""),
      source: String(event.session.source ?? ""),
      repo,
      title,
      body: sessionTitle,
    },
    android: {
      priority: "high",
      ttl: "300s",
    },
  };
}

function createAssertion(account: ServiceAccount): string {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${header}.${claims}`;
  const signer = createSign("RSA-SHA256");
  signer.update(unsigned);
  signer.end();
  return `${unsigned}.${signer.sign(account.private_key).toString("base64url")}`;
}

function base64Url(value: string): string { return Buffer.from(value).toString("base64url"); }
function short(value: unknown, limit: number): string { return Array.from(String(value ?? "").trim()).slice(0, limit).join(""); }
