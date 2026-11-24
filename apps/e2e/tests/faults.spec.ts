import { test, expect, type Browser, type BrowserContext, type FrameLocator, type Page } from '@playwright/test';
import path from 'node:path';
import { createHmac, randomUUID } from 'node:crypto';
import * as S from './support';

// Fault-injection suite. Customer messages go through the real widget on the main stack (admin web /demo -> Chatwoot 3100).
// Chatwoot AgentBot delivers webhooks to the throwaway platform (127.0.0.1:8081, compose project saas-e2e-faults), which
// reaches Chatwoot and R2R through the fault proxy (3900 / 7373, control API 3999). Assertions read the throwaway
// platform's admin API (8081) and Chatwoot's API (3100). Nothing here changes platform state to make a step pass.
// host.docker.internal is used from the host-networked container: the proxy is a host process and the throwaway platform publishes 8081.
const FAULT = 'http://host.docker.internal:8081';
const CTL = 'http://host.docker.internal:3999';
const QUESTION = 'What is your standard delivery time?';
const HUMAN_Q = 'I need a human agent, please.';
const HUMAN_TEXT = 'Human reply during an AI turn (fault test).';
const WEBHOOK_PATH = '/api/webhooks/chatwoot/agent-bot';
const TERMINAL = ['PROCESSED', 'FAILED', 'DEAD', 'DEAD_LETTER'];

const fget = async (p: string): Promise<any> => {
  const r = await fetch(FAULT + p, { headers: { 'X-Admin-Api-Key': S.secrets.adminApiKey } });
  if (!r.ok) throw new Error(`throwaway GET ${p} -> HTTP ${r.status}`);
  return r.json();
};
const ctl = async (method: string, p: string, body?: unknown) => {
  const r = await fetch(CTL + p, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, json: await r.json().catch(() => null) };
};
const rules = async (): Promise<any[]> => (await fetch(CTL + '/rules')).json();
const proxyLog = async (): Promise<any[]> => (await fetch(CTL + '/log')).json();
const ruleHits = async (id: string) => (await rules()).find((r) => r.id === id)?.hits ?? 0;

const fconv = (id: number) => fget(`/api/admin/conversations/${id}`);
const fwebhooks = async (convId: number) => (await fget('/api/admin/webhooks')).webhooks.filter((w: any) => w.chatwootConversationId === convId);
const botPublic = async (convId: number) => (await S.cwMessages(convId)).filter(S.isBotPublic);
const notes = async (convId: number) => (await S.cwMessages(convId)).filter(S.isPrivateNote);

async function postSigned(payload: unknown): Promise<number> {
  const body = JSON.stringify(payload);
  const ts = Math.floor(Date.now() / 1000).toString();
  const sig = 'sha256=' + createHmac('sha256', S.secrets.botSecret).update(`${ts}.${body}`).digest('hex');
  const r = await fetch(FAULT + WEBHOOK_PATH, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Chatwoot-Delivery': randomUUID(), 'X-Chatwoot-Timestamp': ts, 'X-Chatwoot-Signature': sig },
    body,
  });
  return r.status;
}

interface Widget {
  ctx: BrowserContext;
  page: Page;
  frame: FrameLocator;
  convId: number;
}

// Opens the demo page in a fresh browser context and sends the first customer message (new contact, new conversation).
async function openConversation(browser: Browser, firstMessage: string): Promise<Widget> {
  const ctx = await browser.newContext({ baseURL: S.WEB, viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  await page.goto(`${S.WEB}/demo`);
  const launcher = page.locator('.woot--bubble, .woot-widget-bubble, .woot--bubble-holder button').first();
  await launcher.waitFor({ state: 'visible', timeout: 60_000 });
  await launcher.click();
  const frame = page.frameLocator('iframe#chatwoot_live_chat_widget');
  const start = frame.getByText('Start Conversation', { exact: false }).first();
  if (await start.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true, () => false)) await start.click();
  const input = frame.locator('textarea').first();
  await expect(input).toBeVisible({ timeout: 60_000 });
  const before = new Set(await S.cwConversationIds());
  await input.fill(firstMessage);
  await input.press('Enter');
  await expect(frame.getByText(firstMessage, { exact: true })).toBeVisible({ timeout: 30_000 });
  const convId = await S.waitFor(
    'new conversation for the first message',
    async () => {
      for (const id of await S.cwConversationIds()) {
        if (before.has(id)) continue;
        if ((await S.cwMessages(id)).some((m) => S.isIncoming(m) && m.content === firstMessage)) return id;
      }
      return null;
    },
    60_000,
  );
  return { ctx, page, frame, convId };
}

async function sendInConversation(w: Widget, text: string): Promise<number> {
  const input = w.frame.locator('textarea').first();
  await input.fill(text);
  await input.press('Enter');
  await expect(w.frame.getByText(text, { exact: true })).toBeVisible({ timeout: 30_000 });
  const m = await S.waitFor(
    `customer message "${text}" in Chatwoot`,
    async () => (await S.cwMessages(w.convId)).find((x) => S.isIncoming(x) && x.content === text),
    60_000,
  );
  return m!.id;
}

const msgIdFor = async (convId: number, text: string) =>
  (await S.cwMessages(convId)).find((x) => S.isIncoming(x) && x.content === text)?.id ?? null;

async function closeWidget(w: Widget | undefined) {
  if (w) await w.ctx.close();
}

test.describe.configure({ mode: 'serial' });

test('fault-injection suite', async ({ browser }) => {
  test.setTimeout(120 * 60_000);
  S.ensureDirs();
  const L = new S.Ledger('ledger-faults');
  let w: Widget | undefined;

  // ---- 1-3: admin write guard (main admin web, read-only GET tenant before and after)
  const tenantBefore = await (await fetch('http://localhost:4300/api/admin/tenant')).json();
  await L.run(1, 'Admin write guard: cross-site POST /api/admin/conversations/1/resume returns 403 forbidden_origin', [], async (ev) => {
    const r = await fetch('http://localhost:4300/api/admin/conversations/1/resume', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Origin: 'https://attacker.example', 'Sec-Fetch-Site': 'cross-site' },
      body: '{}',
    });
    const body = await r.json().catch(() => ({}));
    Object.assign(ev, { status: r.status, body });
    expect(r.status).toBe(403);
    expect(body.error).toBe('forbidden_origin');
  });

  await L.run(2, 'Admin write guard: same-origin POST is not 403 (passed the guard)', [], async (ev) => {
    const before = await S.adminGet<any>('conversations/1');
    const r = await fetch('http://localhost:4300/api/admin/conversations/1/resume', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Origin: 'http://localhost:4300', 'Sec-Fetch-Site': 'same-origin' },
      body: '{}',
    });
    const body = await r.json().catch(() => ({}));
    const after = await S.adminGet<any>('conversations/1');
    Object.assign(ev, { status: r.status, body, stateBefore: before.state, stateAfter: after.state });
    expect(r.status).not.toBe(403);
    expect(after.state, 'same-origin POST did not change conversation 1 state').toBe(before.state);
  });

  await L.run(3, 'Admin write guard: GET tenant unchanged after the guard checks', [], async (ev) => {
    const tenantAfter = await (await fetch('http://localhost:4300/api/admin/tenant')).json();
    Object.assign(ev, { before: tenantBefore, after: tenantAfter });
    expect(tenantAfter).toEqual(tenantBefore);
  });

  // ---- 4: unknown-send handoff. The public bot POST is forwarded upstream but its response is withheld.
  let unknownConv = 0;
  let unknownMsg = 0;
  await L.run(4, 'Unknown-send handoff: hung public bot POST -> no duplicate reply, handoff note, terminal webhook row (HO, RS)', [], async (ev) => {
    const hang = await ctl('POST', '/rules', { target: 'chatwoot', method: 'POST', pathRe: '^/api/v1/accounts/4/conversations/\\d+/messages$', bodyRe: '"private":false', action: { type: 'hang' }, maxHits: 1 });
    expect(hang.status).toBe(201);
    const ruleId = hang.json.id;
    w = await openConversation(browser, QUESTION);
    unknownConv = w.convId;
    unknownMsg = (await msgIdFor(unknownConv, QUESTION))!;
    await S.waitFor('proxy holds the bot POST', async () => (await ruleHits(ruleId)) >= 1, 120_000, 1000);
    const row = await S.waitFor(
      'terminal webhook row for the customer message',
      async () => (await fwebhooks(unknownConv)).find((x: any) => x.chatwootMessageId === unknownMsg && TERMINAL.includes(x.status)),
      240_000,
      2000,
    );
    const st = await fconv(unknownConv);
    await ctl('DELETE', '/rules'); // release the withheld response (the platform has already given up on it)
    await new Promise((r) => setTimeout(r, 15_000));
    const bots = await botPublic(unknownConv);
    const nts = await notes(unknownConv);
    const noteText = nts.map((n) => n.content ?? '').join('\n---\n');
    const logEntry = (await proxyLog()).find((e) => e.rule === ruleId);
    Object.assign(ev, {
      conversationId: unknownConv,
      customerMessageId: unknownMsg,
      ownershipState: (await fconv(unknownConv)).state,
      stateAtRowTerminal: st.state,
      webhookRow: { status: row.status, outcome: row.outcome, attempts: row.attempts },
      publicBotMessages: bots.map((b) => b.content),
      privateNotes: nts.length,
      noteText: noteText.slice(0, 600),
      proxyHeldRequest: logEntry ? { upstreamStatus: logEntry.status ?? null, error: logEntry.error ?? null } : null,
    });
    expect(bots.length, 'exactly one public bot message (no duplicate reply)').toBe(1);
    expect(nts.length, 'handoff note present').toBeGreaterThanOrEqual(1);
    expect(noteText, 'note says the AI reply may or may not have been delivered').toMatch(/may (or may not )?have been|may or may not|not (be )?confirmed|unknown/i);
    expect(['HANDOFF_PENDING', 'HUMAN_ACTIVE'], 'ownership left AI_ACTIVE').toContain(st.state);
    expect(TERMINAL).toContain(row.status);
  });

  // ---- 5: redelivery of the same customer webhook after the unknown send.
  await L.run(5, 'Unknown-send redelivery: same webhook again creates no second reply or note', [4], async (ev) => {
    const msgs = await S.cwMessages(unknownConv);
    const orig = msgs.find((m) => m.id === unknownMsg)!;
    const payload = {
      event: 'message_created', id: orig.id, content: orig.content, message_type: 'incoming', content_type: 'text', private: false,
      created_at: orig.created_at, sender: { id: orig.sender?.id, name: orig.sender?.name, type: 'contact' },
      conversation: { id: unknownConv, status: 'open', inbox_id: S.INBOX }, account: { id: S.ACCOUNT }, inbox: { id: S.INBOX },
    };
    const before = { bots: (await botPublic(unknownConv)).length, notes: (await notes(unknownConv)).length, rows: (await fwebhooks(unknownConv)).filter((x: any) => x.chatwootMessageId === unknownMsg).length };
    const status = await postSigned(payload);
    await new Promise((r) => setTimeout(r, 20_000));
    const after = { bots: (await botPublic(unknownConv)).length, notes: (await notes(unknownConv)).length, rows: (await fwebhooks(unknownConv)).filter((x: any) => x.chatwootMessageId === unknownMsg).length };
    Object.assign(ev, { replayHttpStatus: status, before, after });
    expect(after.bots, 'no second bot reply after redelivery').toBe(before.bots);
    expect(after.notes, 'no second handoff note after redelivery').toBe(before.notes);
  });

  // ---- 6: HO-02. toggle_status to open is delayed 15 s (platform read timeout is 10 s); poll ownership during the delay.
  let hoConv = 0;
  await L.run(6, 'HO-02: HANDOFF_PENDING observed during a 15 s toggle_status delay, then HUMAN_ACTIVE', [], async (ev) => {
    const d = await ctl('POST', '/rules', { target: 'chatwoot', method: 'POST', pathRe: '^/api/v1/accounts/4/conversations/\\d+/toggle_status$', action: { type: 'delay', ms: 15_000 }, maxHits: 1 });
    expect(d.status).toBe(201);
    if (w) await closeWidget(w);
    w = await openConversation(browser, HUMAN_Q);
    hoConv = w.convId;
    const seen: Array<{ t: number; state: string }> = [];
    const t0 = Date.now();
    let humanAt: number | null = null;
    while (Date.now() - t0 < 60_000) {
      const st = (await fconv(hoConv)).state;
      seen.push({ t: Date.now() - t0, state: st });
      if (st === 'HUMAN_ACTIVE' && seen.some((x) => x.state === 'HANDOFF_PENDING')) { humanAt = Date.now() - t0; break; }
      await new Promise((r) => setTimeout(r, 500));
    }
    const states = [...new Set(seen.map((x) => x.state))];
    const proxyToggle = (await proxyLog()).find((e) => e.rule === d.json.id);
    const nts = await notes(hoConv);
    const cw = await S.cwConversation(hoConv);
    Object.assign(ev, {
      conversationId: hoConv,
      observedStates: states,
      firstHandoffPendingMs: seen.find((x) => x.state === 'HANDOFF_PENDING')?.t ?? null,
      humanActiveAfterMs: humanAt,
      toggleProxyEntry: proxyToggle ? { status: proxyToggle.status ?? null, ms: proxyToggle.ms ?? null } : null,
      privateNotes: nts.length,
      chatwootStatus: cw.status,
    });
    expect(states, 'HANDOFF_PENDING observed while the toggle was delayed').toContain('HANDOFF_PENDING');
    expect(humanAt, 'HUMAN_ACTIVE reached after the delay').not.toBeNull();
    expect(nts.length, 'one handoff note').toBe(1);
    expect(cw.status).toBe('open');
  });

  // ---- 7: HO-08. RAG is delayed 20 s; a human request arrives during the flight; the late answer must not be sent.
  let lateConv = 0;
  let lateMsg = 0;
  await L.run(7, 'HO-08: late RAG answer discarded after a human request during the in-flight turn', [], async (ev) => {
    const d = await ctl('POST', '/rules', { target: 'r2r', method: 'POST', pathRe: '^/v3/retrieval/rag$', action: { type: 'delay', ms: 20_000 }, maxHits: 1 });
    expect(d.status).toBe(201);
    if (w) await closeWidget(w);
    w = await openConversation(browser, QUESTION);
    lateConv = w.convId;
    lateMsg = (await msgIdFor(lateConv, QUESTION))!;
    await S.waitFor('RAG call in flight at the proxy', async () => (await ruleHits(d.json.id)) >= 1, 120_000, 500);
    const humanMsg = await sendInConversation(w, HUMAN_Q);
    const ragEntry = await S.waitFor(
      'delayed RAG response completed at the proxy',
      async () => (await proxyLog()).find((e) => e.rule === d.json.id && typeof e.status === 'number'),
      120_000,
      1000,
    );
    await new Promise((r) => setTimeout(r, 30_000));
    const bots = await botPublic(lateConv);
    const lateBots = bots.filter((b) => b.id > humanMsg);
    const rows = await fwebhooks(lateConv);
    const rowFirst = rows.find((x: any) => x.chatwootMessageId === lateMsg);
    const rowHuman = rows.find((x: any) => x.chatwootMessageId === humanMsg);
    const st = await fconv(lateConv);
    Object.assign(ev, {
      conversationId: lateConv,
      firstCustomerMessageId: lateMsg,
      humanRequestMessageId: humanMsg,
      ragProxyStatus: ragEntry.status,
      ragProxyMs: ragEntry.ms ?? null,
      publicBotMessages: bots.map((b) => ({ id: b.id, content: (b.content ?? '').slice(0, 120) })),
      outcomeFirstMessage: rowFirst?.outcome ?? null,
      outcomeHumanRequest: rowHuman?.outcome ?? null,
      finalState: st.state,
    });
    expect(bots.length, 'no bot answer reaches the customer').toBe(0);
    expect(lateBots.length, 'no bot answer after the human request').toBe(0);
    expect(['DISCARDED', 'NOT_AI_ACTIVE'], 'late turn outcome').toContain(rowFirst?.outcome);
  });

  // ---- 8: queued human reply. GET messages (the pre-send human check) is delayed 8 s; a human reply lands during the delay.
  let queuedConv = 0;
  await L.run(8, 'Queued human reply: human message during the send-guard delay stops the AI answer', [], async (ev) => {
    const d = await ctl('POST', '/rules', { target: 'chatwoot', method: 'GET', pathRe: '^/api/v1/accounts/4/conversations/\\d+/messages$', action: { type: 'delay', ms: 8_000 }, maxHits: 1 });
    expect(d.status).toBe(201);
    if (w) await closeWidget(w);
    w = await openConversation(browser, QUESTION);
    queuedConv = w.convId;
    const custMsg = (await msgIdFor(queuedConv, QUESTION))!;
    await S.waitFor('GET messages held at the proxy', async () => (await ruleHits(d.json.id)) >= 1, 120_000, 300);
    const human = await fetch(`${S.CHATWOOT}/api/v1/accounts/4/conversations/${queuedConv}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', api_access_token: S.secrets.chatwootApiToken },
      body: JSON.stringify({ content: HUMAN_TEXT, message_type: 'outgoing', private: false }),
    });
    expect(human.status).toBe(200);
    const humanBody: any = await human.json();
    await new Promise((r) => setTimeout(r, 40_000));
    const bots = await botPublic(queuedConv);
    const afterHuman = bots.filter((b) => b.id > humanBody.id);
    const rows = await fwebhooks(queuedConv);
    const rowCust = rows.find((x: any) => x.chatwootMessageId === custMsg);
    const rowHuman = rows.find((x: any) => x.chatwootMessageId === humanBody.id);
    const st = await fconv(queuedConv);
    Object.assign(ev, {
      conversationId: queuedConv,
      customerMessageId: custMsg,
      humanMessageId: humanBody.id,
      humanSenderType: humanBody.sender?.type ?? null,
      outcomeCustomerMessage: rowCust?.outcome ?? null,
      outcomeHumanMessage: rowHuman?.outcome ?? null,
      publicBotMessages: bots.map((b) => ({ id: b.id, content: (b.content ?? '').slice(0, 120) })),
      finalState: st.state,
    });
    expect(afterHuman.length, 'no AI answer after the human message').toBe(0);
    expect(st.state, 'ownership HUMAN_ACTIVE').toBe('HUMAN_ACTIVE');
  });

  // ---- 9: live turn for AI-07 (connections are sampled by the host driver during this run).
  await L.run(9, 'AI-07: live knowledge turn completes through R2R (connections sampled by the host driver)', [], async (ev) => {
    if (w) await closeWidget(w);
    w = await openConversation(browser, QUESTION);
    const bot = await S.waitFor('bot reply on the live turn', async () => (await botPublic(w!.convId)).at(0), 240_000, 3000);
    const ragEntries = (await proxyLog()).filter((e) => e.path === '/v3/retrieval/rag');
    Object.assign(ev, { conversationId: w.convId, botReplyChars: (bot.content ?? '').length, ragCallsSeen: ragEntries.length });
    expect(bot).toBeTruthy();
  });

  if (w) await closeWidget(w);
  const failed = L.records.filter((r) => r.verdict !== 'PASS').map((r) => `step ${r.step} ${r.verdict}: ${r.reason ?? ''}`);
  expect(failed, 'fault suite steps not all PASS').toEqual([]);
});
