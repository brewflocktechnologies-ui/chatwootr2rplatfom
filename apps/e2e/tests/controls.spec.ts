import { test, expect, type Browser, type Page } from '@playwright/test';
import path from 'node:path';
import * as S from './support';

// Negative controls: questions absent from the policy must hand off and must not invent policy.
const NEGATIVE: Array<[number, string]> = [
  [101, 'What is your refund policy for opened electronics?'],
  [102, 'Do you sell gift cards?'],
  [103, 'Can I change my delivery address after dispatch?'],
];
const RETURNS_Q = 'What is your return policy?';
const DELIVERY_Q = 'What is your standard delivery time?';
const POLICY_CLAIM = /business days?|refund|gift card|\d+\s*(days?|%)/i;

interface AskResult {
  convId: number;
  customerMessageId: number;
  state: string;
  botReplies: string[];
  privateNotes: number;
  handoff: unknown;
  webhookOutcome: string | null;
}

// One fresh browser context = one fresh widget contact = one fresh Chatwoot conversation.
async function askInFreshConversation(browser: Browser, question: string, label: string): Promise<AskResult> {
  const ctx = await browser.newContext({ baseURL: S.WEB, viewport: { width: 1280, height: 900 } });
  try {
    const page: Page = await ctx.newPage();
    await page.goto(`${S.WEB}/demo`);
    const launcher = page.locator('.woot--bubble, .woot-widget-bubble, .woot--bubble-holder button').first();
    await launcher.waitFor({ state: 'visible', timeout: 60_000 });
    await launcher.click();
    const widget = page.frameLocator('iframe#chatwoot_live_chat_widget');
    const start = widget.getByText('Start Conversation', { exact: false }).first();
    if (await start.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true, () => false)) await start.click();
    const input = widget.locator('textarea').first();
    await expect(input).toBeVisible({ timeout: 60_000 });
    const before = new Set(await S.cwConversationIds());
    await input.fill(question);
    await input.press('Enter');
    await expect(widget.getByText(question, { exact: true })).toBeVisible({ timeout: 30_000 });
    const found = await S.waitFor(
      `conversation containing "${question}"`,
      async () => {
        for (const id of await S.cwConversationIds()) {
          if (before.has(id)) continue;
          const m = (await S.cwMessages(id)).find((x) => S.isIncoming(x) && x.content === question);
          if (m) return { id, msgId: m.id };
        }
        return null;
      },
      60_000,
    );
    // Outcome: a public bot reply appears, or ownership leaves AI_ACTIVE (handoff).
    await S.waitFor(
      'AI outcome (bot reply or handoff)',
      async () => {
        const bots = (await S.cwMessages(found.id)).some(S.isBotPublic);
        const st = (await S.adminGet<any>(`conversations/${found.id}`)).state;
        return bots || st !== 'AI_ACTIVE' ? true : null;
      },
      240_000,
      3000,
    );
    await new Promise((r) => setTimeout(r, 15_000)); // allow a second reply/note to appear before counting
    const msgs = await S.cwMessages(found.id);
    const conv = await S.adminGet<any>(`conversations/${found.id}`);
    const rec = (await S.adminGet<any>('webhooks')).webhooks.find((x: any) => x.chatwootMessageId === found.msgId);
    await shotWidget(page, label);
    return {
      convId: found.id,
      customerMessageId: found.msgId,
      state: conv.state,
      botReplies: msgs.filter(S.isBotPublic).map((m) => m.content ?? ''),
      privateNotes: msgs.filter(S.isPrivateNote).length,
      handoff: conv.handoff ?? null,
      webhookOutcome: rec?.outcome ?? null,
    };
  } finally {
    await ctx.close();
  }
}

async function shotWidget(page: Page, label: string): Promise<void> {
  await page.screenshot({ path: path.join(S.ART, 'screenshots', `control-${label}.png`), fullPage: true }).catch(() => undefined);
}

test.describe.configure({ mode: 'serial' });

test('negative controls and delivery 10-run check (each in a fresh conversation)', async ({ browser }) => {
  test.setTimeout(120 * 60_000);
  S.ensureDirs();
  const L = new S.Ledger('ledger-controls');
  // GF_CONTROL_ONLY=<step> runs a single control (re-check after an assertion fix); unset runs everything.
  const only = process.env.GF_CONTROL_ONLY ? Number(process.env.GF_CONTROL_ONLY) : null;
  const want = (n: number) => only === null || only === n;

  for (const [step, question] of NEGATIVE) {
    if (!want(step)) continue;
    await L.run(step, `Negative control: "${question}" must hand off, no invented policy`, [], async (ev) => {
      const r = await askInFreshConversation(browser, question, `n${step}`);
      Object.assign(ev, { conversationId: r.convId, customerMessageId: r.customerMessageId, state: r.state, privateNotes: r.privateNotes, webhookOutcome: r.webhookOutcome, botReplies: r.botReplies });
      expect(r.state, 'ownership left AI_ACTIVE (handoff)').not.toBe('AI_ACTIVE');
      expect(r.privateNotes, 'handoff note created').toBeGreaterThanOrEqual(1);
      const invented = r.botReplies.filter((t) => POLICY_CLAIM.test(t));
      expect(invented, 'no invented policy facts in any bot reply').toEqual([]);
    });
  }

  if (want(104)) await L.run(104, `Returns control: "${RETURNS_Q}" replies with the returns sentence only`, [], async (ev) => {
    const r = await askInFreshConversation(browser, RETURNS_Q, 'r104');
    Object.assign(ev, { conversationId: r.convId, customerMessageId: r.customerMessageId, state: r.state, botReplies: r.botReplies });
    expect(r.botReplies.length, 'exactly one bot reply').toBe(1);
    const a = r.botReplies[0];
    expect(a, 'returns window stated').toMatch(/14 calendar days/i);
    expect(a, 'no delivery or refund sentences in a returns-only answer').not.toMatch(/business days|refund|dispatch/i);
    expect(a, 'policy source reference present').toMatch(/source/i);
  });

  const runs: Array<{ run: number; convId: number; reply: string; window: boolean; source: boolean; dispatch: boolean; bots: number }> = [];
  for (let i = 1; i <= 10; i++) {
    if (want(200 + i)) await L.run(200 + i, `Delivery run ${i}/10: "${DELIVERY_Q}" replies with the 3-5 business day window and source`, [], async (ev) => {
      const r = await askInFreshConversation(browser, DELIVERY_Q, `d${i}`);
      const reply = r.botReplies[0] ?? '';
      const row = {
        run: i,
        convId: r.convId,
        reply,
        window: /3\s*[–-]\s*5\s+business\s+days/i.test(reply),
        source: /source|policy|\[\d+\]/i.test(reply),
        dispatch: /1\s*[–-]\s*2\s+business\s+days/i.test(reply),
        bots: r.botReplies.length,
      };
      runs.push(row);
      Object.assign(ev, { conversationId: r.convId, customerMessageId: r.customerMessageId, botReplies: r.botReplies, window: row.window, source: row.source, dispatchClause: row.dispatch });
      expect(r.botReplies.length, 'exactly one bot reply').toBe(1);
      expect(row.window, 'answer states 3-5 business days after dispatch').toBe(true);
      expect(row.source, 'answer carries a source reference').toBe(true);
    });
  }

  if (only === null) await L.run(211, 'Delivery 10-run summary (dispatch clause tracked as known issue)', [], async (ev) => {
    const summary = {
      runs: runs.length,
      windowPass: runs.filter((x) => x.window).length,
      sourcePass: runs.filter((x) => x.source).length,
      singleReplyPass: runs.filter((x) => x.bots === 1).length,
      dispatchClausePresent: runs.filter((x) => x.dispatch).length,
      conversations: runs.map((x) => x.convId),
    };
    Object.assign(ev, summary);
    S.writeArtifact('api/delivery-10-runs.json', JSON.stringify(runs, null, 2));
    expect(summary.runs, 'all 10 delivery runs executed').toBe(10);
  });

  // Ledger verdicts are the source of truth: a FAIL recorded by L.run must fail this test (not just the ledger).
  const failed = L.records.filter((r) => r.verdict !== 'PASS').map((r) => `step ${r.step} ${r.verdict}: ${r.reason ?? ''}`);
  expect(failed, 'control steps not all PASS').toEqual([]);
});
