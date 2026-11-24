import { createHmac, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

// Repo root when running from apps/e2e/tests (in the container this is /work).
export const REPO = path.resolve(__dirname, '../../..');
export const ART = path.join(REPO, 'artifacts', 'e2e');
export const WEB = 'http://localhost:4300';
export const PLATFORM = 'http://127.0.0.1:8080';
export const CHATWOOT = 'http://127.0.0.1:3100';
export const ACCOUNT = 4;
export const INBOX = 1;
export const POLICY_FILE = path.join(REPO, 'docs', 'demo-tenant', 'acme-support-policy.md');
export const CONTROL = path.join(ART, 'control');

// Secrets are read from local ignored files only; values are never logged or written to artifacts.
function readEnvFile(rel: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of readFileSync(path.join(REPO, rel), 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (m) out[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
  return out;
}

const platformEnv = readEnvFile('infra/platform/.env');
const chatwootSecrets = readEnvFile('infra/chatwoot/.env.secrets');

export const secrets = {
  adminApiKey: platformEnv.PLATFORM_ADMIN_API_KEY,
  chatwootApiToken: platformEnv.CHATWOOT_API_TOKEN,
  botSecret: platformEnv.CHATWOOT_BOT_SECRET,
  humanEmail: chatwootSecrets.HUMAN_AGENT_EMAIL,
  humanPassword: chatwootSecrets.HUMAN_AGENT_PASSWORD,
};

const secretValues = Object.values(secrets).filter((v): v is string => !!v && v.length >= 6);

// Sanitizer for any text written to artifacts: removes known secret values, emails and JWT-like strings.
export function sanitize(text: string): string {
  let out = text;
  for (const v of secretValues) out = out.split(v).join('[REDACTED]');
  return redactTokens(out);
}

// Token-like redaction: JWTs, and header/field values for auth-related names (JSON or key=value form).
export function redactTokens(text: string): string {
  return text
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[EMAIL]')
    .replace(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}(\.[A-Za-z0-9_-]{10,})?/g, '[JWT]')
    .replace(
      /("name"\s*:\s*"(?:access-token|client|uid|authorization|api_access_token|x-auth-token|x-admin-api-key|cookie|set-cookie|x-chatwoot-signature)"\s*,\s*"value"\s*:\s*")[^"]*/gi,
      '$1[REDACTED]',
    )
    .replace(/("(?:password|token|access_token|api_access_token|secret|authorization)"\s*:\s*")[^"]*/gi, '$1[REDACTED]')
    .replace(/((?:api_access_token|access-token|x-auth-token|authorization)\s*[:=]\s*)[^\s"',;]+/gi, '$1[REDACTED]');
}

export function writeArtifact(rel: string, content: string | Buffer): string {
  const full = path.join(ART, rel);
  mkdirSync(path.dirname(full), { recursive: true });
  writeFileSync(full, typeof content === 'string' ? sanitize(content) : content);
  return full;
}

export function ensureDirs(): void {
  for (const d of ['control', 'traces', 'screenshots', 'api']) mkdirSync(path.join(ART, d), { recursive: true });
}

export async function adminGet<T = any>(p: string): Promise<T> {
  const r = await fetch(`${PLATFORM}/api/admin/${p}`, { headers: { 'X-Admin-Api-Key': secrets.adminApiKey } });
  if (!r.ok) throw new Error(`platform GET ${p} -> HTTP ${r.status}`);
  return (await r.json()) as T;
}

export async function cwGet<T = any>(p: string): Promise<T> {
  const r = await fetch(`${CHATWOOT}/api/v1/accounts/${ACCOUNT}/${p}`, {
    headers: { api_access_token: secrets.chatwootApiToken },
  });
  if (!r.ok) throw new Error(`chatwoot GET ${p} -> HTTP ${r.status}`);
  return (await r.json()) as T;
}

export interface CwMessage {
  id: number;
  content: string | null;
  message_type: number | string;
  private: boolean;
  sender_type?: string | null;
  sender?: { type?: string; id?: number; name?: string } | null;
  created_at: number;
  content_type?: string;
}

// Chatwoot returns messages newest-first in some versions; normalize to oldest-first.
export async function cwMessages(convId: number): Promise<CwMessage[]> {
  const body = await cwGet<{ payload: CwMessage[] }>(`conversations/${convId}/messages`);
  return [...body.payload].sort((a, b) => a.id - b.id);
}

export async function cwConversationIds(): Promise<number[]> {
  const body = await cwGet<{ data: { payload: { id: number }[] } }>('conversations?status=all&page=1');
  return body.data.payload.map((c) => c.id);
}

export async function cwConversation(convId: number): Promise<{ status: string; id: number }> {
  return cwGet(`conversations/${convId}`);
}

// Message direction helpers, matching Chatwoot message_type numbers (0 incoming, 1 outgoing).
export const isIncoming = (m: CwMessage) => m.message_type === 0 || m.message_type === 'incoming';
export const isBotPublic = (m: CwMessage) =>
  (m.message_type === 1 || m.message_type === 'outgoing') && !m.private && m.sender?.type === 'agent_bot';
export const isHumanPublic = (m: CwMessage) =>
  (m.message_type === 1 || m.message_type === 'outgoing') && !m.private && m.sender?.type === 'user';
export const isPrivateNote = (m: CwMessage) => m.private === true;

export async function waitFor<T>(
  label: string,
  fn: () => Promise<T | null | undefined | false>,
  timeoutMs = 180_000,
  intervalMs = 2_000,
): Promise<T> {
  const end = Date.now() + timeoutMs;
  let last: unknown;
  while (Date.now() < end) {
    try {
      const v = await fn();
      if (v) return v as T;
    } catch (e) {
      last = e;
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`timeout waiting for: ${label}${last ? ` (last error: ${(last as Error).message})` : ''}`);
}

// Replays a Chatwoot AgentBot delivery, signed the same way Chatwoot signs (HMAC-SHA256 over "ts.body").
export async function postSignedWebhook(payload: unknown): Promise<{ status: number; deliveryId: string }> {
  const body = JSON.stringify(payload);
  const ts = Math.floor(Date.now() / 1000).toString();
  const deliveryId = randomUUID();
  const sig = 'sha256=' + createHmac('sha256', secrets.botSecret).update(`${ts}.${body}`).digest('hex');
  const r = await fetch(`${PLATFORM}/api/webhooks/chatwoot/agent-bot`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Chatwoot-Delivery': deliveryId,
      'X-Chatwoot-Timestamp': ts,
      'X-Chatwoot-Signature': sig,
    },
    body,
  });
  return { status: r.status, deliveryId };
}

// Step ledger: each step records PASS / FAIL / NOT RUN and is only executed when its dependencies passed.
export type Verdict = 'PASS' | 'FAIL' | 'NOT RUN';
export interface StepRecord {
  step: number;
  name: string;
  verdict: Verdict;
  reason?: string;
  evidence: Record<string, unknown>;
  startedAt: string;
  endedAt: string;
}

export class Ledger {
  readonly records: StepRecord[] = [];
  constructor(private readonly name = 'ledger') {}
  private readonly verdicts = new Map<number, Verdict>();

  async run(
    step: number,
    name: string,
    deps: number[],
    fn: (evidence: Record<string, unknown>) => Promise<void>,
  ): Promise<void> {
    const startedAt = new Date().toISOString();
    const evidence: Record<string, unknown> = {};
    const blocked = deps.filter((d) => this.verdicts.get(d) !== 'PASS');
    let verdict: Verdict;
    let reason: string | undefined;
    if (blocked.length > 0) {
      verdict = 'NOT RUN';
      reason = `blocked by step(s) ${blocked.join(', ')}`;
    } else {
      try {
        await fn(evidence);
        verdict = 'PASS';
      } catch (e) {
        verdict = 'FAIL';
        reason = sanitize((e as Error).message).split('\n')[0];
      }
    }
    this.verdicts.set(step, verdict);
    this.records.push({ step, name, verdict, reason, evidence, startedAt, endedAt: new Date().toISOString() });
    this.flush();
    console.log(`STEP ${step} ${verdict} ${name}${reason ? ` :: ${reason}` : ''}`);
  }

  flush(): void {
    ensureDirs();
    writeArtifact(`api/${this.name}.json`, JSON.stringify(this.records, null, 2));
  }
}

export function requireFile(p: string): void {
  if (!existsSync(p)) throw new Error(`missing file ${path.relative(REPO, p)}`);
}
