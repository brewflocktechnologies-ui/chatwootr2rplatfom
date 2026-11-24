import { test, expect, type Browser, type BrowserContext, type FrameLocator, type Page } from '@playwright/test';
import * as S from './support';

// HO-08 on the main stack, no fault injection and no AgentBot URL change (AgentBot -> main platform 8080).
// Timing approach: a knowledge question, then "I need a human agent, please." 300 ms later, in the same fresh conversation,
// so the human request lands while the RAG turn is in flight. Each trial is a fresh browser context and conversation.
// Known limit (documented, not tested here): one Chatwoot POST that is already in flight when the handoff starts can still
// reach the customer.
const QUESTION = 'What is your standard delivery time?';
const HUMAN_Q = 'I need a human agent, please.';
const TRIALS = 3;
const SETTLE_MS = 45_000;

interface Trial {
  trial: number;
  convId: number;
  questionMessageId: number;
  humanMessageId: number;
  gapMs: number;
  botRepliesAfterQuestion: string[];
  botRepliesAfterHuman: number;
  questionOutcome: string | null;
  humanOutcome: string | null;
  privateNotes: number;
  finalState: string;
  observedStates: string[];
}

async function open(browser: Browser): Promise<{ ctx: BrowserContext; page: Page; frame: FrameLocator }> {
  const ctx = await browser.newContext({ baseURL: S.WEB, viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  await page.goto(`${S.WEB}/demo`);
  const launcher = page.locator('.woot--bubble, .woot-widget-bubble, .woot--bubble-holder button').first();
  await launcher.waitFor({ state: 'visible', timeout: 60_000 });
  await launcher.click();
  const frame = page.frameLocator('iframe#chatwoot_live_chat_widget');
  const start = frame.getByText('Start Conversation', { exact: false }).first();
  if (await start.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true, () => false)) await start.click();
  await expect(frame.locator('textarea').first()).toBeVisible({ timeout: 60_000 });
  return { ctx, page, frame };
}

test.describe.configure({ mode: 'serial' });

test('HO-08: human request during an in-flight AI turn; no late bot answer (main stack, 300 ms timing)', async ({ browser }) => {
  test.setTimeout(60 * 60_000);
  S.ensureDirs();
  const L = new S.Ledger('ledger-ho08');
  const trials: Trial[] = [];

  for (let i = 1; i <= TRIALS; i++) {
    await L.run(i, `HO-08 trial ${i}/${TRIALS}: human request 300 ms after the question; no bot answer after the human request`, [], async (ev) => {
      const { ctx, page, frame } = await open(browser);
      try {
        const before = new Set(await S.cwConversationIds());
        const input = frame.locator('textarea').first();
        await input.fill(QUESTION);
        await input.press('Enter');
        await expect(frame.getByText(QUESTION, { exact: true })).toBeVisible({ timeout: 30_000 });
        const convId = await S.waitFor(
          'conversation for the question',
          async () => {
            for (const id of await S.cwConversationIds()) {
              if (before.has(id)) continue;
              if ((await S.cwMessages(id)).some((m) => S.isIncoming(m) && m.content === QUESTION)) return id;
            }
            return null;
          },
          60_000,
        );
        const qId = (await S.cwMessages(convId)).find((m) => S.isIncoming(m) && m.content === QUESTION)!.id;

        // The timing under test: human request 300 ms after the question.
        const t0 = Date.now();
        await new Promise((r) => setTimeout(r, 300));
        await input.fill(HUMAN_Q);
        await input.press('Enter');
        const gapMs = Date.now() - t0;
        await expect(frame.getByText(HUMAN_Q, { exact: true })).toBeVisible({ timeout: 30_000 });
        const hId = await S.waitFor(
          'human request stored',
          async () => (await S.cwMessages(convId)).find((m) => S.isIncoming(m) && m.content === HUMAN_Q)?.id ?? null,
          60_000,
        );

        const observed = new Set<string>();
        await S.waitFor('ownership HUMAN_ACTIVE', async () => {
          const st = (await S.adminGet<any>(`conversations/${convId}`)).state;
          observed.add(st);
          return st === 'HUMAN_ACTIVE' ? true : null;
        }, 180_000, 1000);
        await new Promise((r) => setTimeout(r, SETTLE_MS));

        const msgs = await S.cwMessages(convId);
        const conv = await S.adminGet<any>(`conversations/${convId}`);
        const rows = (await S.adminGet<any>('webhooks')).webhooks.filter((w: any) => w.chatwootConversationId === convId);
        const t: Trial = {
          trial: i,
          convId,
          questionMessageId: qId,
          humanMessageId: hId,
          gapMs,
          botRepliesAfterQuestion: msgs.filter((m) => S.isBotPublic(m) && m.id > qId).map((m) => m.content ?? ''),
          botRepliesAfterHuman: msgs.filter((m) => S.isBotPublic(m) && m.id > hId).length,
          questionOutcome: rows.find((w: any) => w.chatwootMessageId === qId)?.outcome ?? null,
          humanOutcome: rows.find((w: any) => w.chatwootMessageId === hId)?.outcome ?? null,
          privateNotes: msgs.filter(S.isPrivateNote).length,
          finalState: conv.state,
          observedStates: [...observed],
        };
        trials.push(t);
        Object.assign(ev, t, { botRepliesAfterQuestion: t.botRepliesAfterQuestion.map((c) => c.slice(0, 120)) });
        await page.screenshot({ path: `${S.ART}/ho08/trial-${i}-widget.png`, fullPage: true }).catch(() => undefined);

        expect(t.botRepliesAfterHuman, 'zero bot answers after the human request').toBe(0);
        expect(t.botRepliesAfterQuestion, 'the question was not answered (discarded or not sent)').toEqual([]);
        expect(t.privateNotes, 'one handoff note').toBe(1);
        expect(t.finalState, 'ownership HUMAN_ACTIVE').toBe('HUMAN_ACTIVE');
      } finally {
        await ctx.close();
      }
    });
  }

  S.writeArtifact('api/ho08-trials.json', JSON.stringify(trials, null, 2));
  const failed = L.records.filter((r) => r.verdict !== 'PASS').map((r) => `step ${r.step} ${r.verdict}: ${r.reason ?? ''}`);
  expect(failed, 'HO-08 trials not all PASS').toEqual([]);
});
