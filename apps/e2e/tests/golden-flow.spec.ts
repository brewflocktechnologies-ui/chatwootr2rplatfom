import { test, expect, type Browser, type BrowserContext, type FrameLocator, type Page } from '@playwright/test';
import path from 'node:path';
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import * as S from './support';

// Fixed customer/agent wording from bootstrap section 9 (curly apostrophes are intentional).
const Q_DELIVERY = 'What is your standard delivery time?';
const Q_HUMAN = 'I need to speak to a human agent, please.';
const HUMAN_REPLY = 'Hello, I’m Alex from support. I’ll review this and help you.';
const Q_FOLLOWUP = 'Can you also check this for me?';
const Q_AFTER_RESTART = 'Are you still there after the restart? Please check this for me.';
const QUIET_WINDOW_MS = 45_000;

interface TracedContext {
  ctx: BrowserContext;
  name: string;
  trace: boolean;
}

async function openContext(browser: Browser, name: string, trace: boolean): Promise<TracedContext> {
  const ctx = await browser.newContext({ baseURL: S.WEB, viewport: { width: 1280, height: 900 } });
  if (trace) await ctx.tracing.start({ screenshots: true, snapshots: true });
  return { ctx, name, trace };
}

async function closeContext(c: TracedContext): Promise<void> {
  if (c.trace) await c.ctx.tracing.stop({ path: path.join(S.ART, 'traces', `${c.name}.zip`) });
  await c.ctx.close();
}

const shot = (page: Page, name: string) =>
  page.screenshot({ path: path.join(S.ART, 'screenshots', `${name}.png`), fullPage: true });

test.describe.configure({ mode: 'serial' });

test('golden flow steps 3-17 (conversation created fresh in this run)', async ({ browser }) => {
  test.setTimeout(40 * 60_000);
  S.ensureDirs();
  const L = new S.Ledger();
  const contexts: TracedContext[] = [];
  const consoleErrors: string[] = [];
  const st: Record<string, any> = {};
  const widgetBase = process.env.GF_WIDGET_BASE_OVERRIDE || '';
  st.variant = widgetBase ? 'PARTIAL: widget base URL rewritten by route interception' : 'primary (no rewrite)';

  let customer: TracedContext | undefined;
  let page: Page | undefined;
  let widget: FrameLocator | undefined;

  try {
    // Step 3: knowledge page shows acme-demo and the policy as ingested.
    await L.run(3, 'Admin knowledge page shows acme-demo and policy as ingested', [], async (ev) => {
      const admin = await openContext(browser, 'admin', true);
      contexts.push(admin);
      const p = await admin.ctx.newPage();
      await p.goto(`${S.WEB}/admin/knowledge`);
      await expect(p.getByRole('heading', { name: 'Knowledge' })).toBeVisible();
      await expect(p.getByText(/acme-demo/).first()).toBeVisible();
      const row = p.locator('tr', { hasText: 'acme-support-policy.md' });
      if ((await row.count()) === 0) {
        // Bootstrap precondition: "policy document ingested". Tenant had zero documents, so the policy
        // is uploaded through the admin UI (not the API). The ingestion status is then verified below.
        ev.precondition = 'tenant had 0 documents; uploaded docs/demo-tenant/acme-support-policy.md via admin UI';
        await p.locator('input[type=file]').setInputFiles(S.POLICY_FILE);
        await p.getByRole('button', { name: 'Upload' }).click();
        await expect(p.getByText('Uploaded acme-support-policy.md')).toBeVisible({ timeout: 60_000 });
      }
      // Poll until the status leaves PENDING/INGESTING (FAILED is reported immediately, not waited out).
      await expect
        .poll(
          async () => {
            await p.getByRole('button', { name: 'Refresh' }).click();
            await p.waitForTimeout(1500);
            const t = await row.innerText().catch(() => '');
            if (/INGESTING|PENDING/i.test(t)) return 'in-progress';
            return /\bREADY\b|\bReady\b/.test(t) ? 'READY' : 'not-ready';
          },
          { timeout: 180_000, intervals: [3000] },
        )
        .not.toBe('in-progress');
      ev.statusRowText = ((await row.innerText().catch(() => '')) as string).replace(/\s+/g, ' ').slice(0, 200);
      await shot(p, 'step03-admin-knowledge-status');
      const docs = await S.adminGet<any>('knowledge/documents');
      const tenant = await S.adminGet<any>('tenant');
      ev.tenantKey = tenant.tenantKey;
      ev.documents = docs.documents.map((d: any) => ({ filename: d.filename, status: d.status, sizeBytes: d.sizeBytes }));
      expect(ev.statusRowText, 'policy row shows READY in the admin UI').toMatch(/READY|Ready/);
      expect(docs.documents.some((d: any) => d.filename === 'acme-support-policy.md' && d.status === 'READY')).toBe(true);
      await closeContext(admin);
      contexts.splice(contexts.indexOf(admin), 1);
    });

    // Step 4: clean customer context opens the native widget at /demo.
    // Step 4 is not blocked by step 3: R2R may still hold the policy from earlier probes, and running the
    // remaining steps gives evidence for the rest of the flow. The step 3 failure is reported separately.
    await L.run(4, 'Demo page opens native Chatwoot widget in a clean context', [], async (ev) => {
      customer = await openContext(browser, 'customer', true);
      contexts.push(customer);
      page = await customer.ctx.newPage();
      page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
      page.on('pageerror', (e) => consoleErrors.push(e.message));
      if (widgetBase) {
        await page.route('**/api/public/widget-config', async (route) => {
          const resp = await route.fetch();
          const json = await resp.json();
          json.chatwootBaseUrl = widgetBase;
          await route.fulfill({ response: resp, json });
        });
      }
      await page.goto(`${S.WEB}/demo`);
      const launcher = page.locator('.woot--bubble, .woot-widget-bubble, .woot--bubble-holder button').first();
      await launcher.waitFor({ state: 'visible', timeout: 60_000 });
      await shot(page, 'step04-demo-launcher');
      await launcher.click();
      widget = page.frameLocator('iframe#chatwoot_live_chat_widget');
      // The widget opens in "away" mode with a "Start Conversation" call to action before the message box appears.
      const start = widget.getByText('Start Conversation', { exact: false }).first();
      // isVisible() does not wait; use waitFor so the widget has time to render.
      if (await start.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true, () => false)) {
        ev.awayState = true;
        await start.click();
      }
      await expect(widget.locator('textarea').first()).toBeVisible({ timeout: 60_000 });
      await shot(page, 'step04-widget-open');
      ev.widgetIframe = 'iframe#chatwoot_live_chat_widget';
    });

    // Step 5: first customer question in a fresh conversation.
    await L.run(5, 'Customer asks the standard delivery question', [4], async (ev) => {
      const w = widget!;
      st.before = new Set(await S.cwConversationIds());
      const input = w.locator('textarea').first();
      await input.fill(Q_DELIVERY);
      await input.press('Enter');
      await expect(w.getByText(Q_DELIVERY, { exact: true })).toBeVisible({ timeout: 30_000 });
      const found = await S.waitFor(
        'new Chatwoot conversation containing the customer question',
        async () => {
          for (const id of await S.cwConversationIds()) {
            if (st.before.has(id)) continue;
            const m = (await S.cwMessages(id)).find((x) => S.isIncoming(x) && x.content === Q_DELIVERY);
            if (m) return { id, msgId: m.id };
          }
          return null;
        },
        60_000,
      );
      st.convId = found.id;
      st.firstMsgId = found.msgId;
      ev.conversationId = found.id;
      ev.customerMessageId = found.msgId;
      await shot(page!, 'step05-question-sent');
    });

    // Step 6: one durable webhook record, one bot reply, shown once in the widget.
    await L.run(6, 'One webhook record, one AI reply, displayed once in widget', [5], async (ev) => {
      const recs = await S.waitFor(
        'PROCESSED webhook record for the customer message',
        async () => {
          const all = (await S.adminGet<any>('webhooks')).webhooks;
          const mine = all.filter((x: any) => x.chatwootConversationId === st.convId);
          return mine.some((x: any) => x.status === 'PROCESSED') ? mine : null;
        },
        180_000,
      );
      const forMsg = recs.filter((x: any) => x.chatwootMessageId === st.firstMsgId && x.eventKind === 'CUSTOMER_MESSAGE');
      ev.webhookRecordsForConversation = recs.length;
      ev.webhookRecordsForCustomerMessage = forMsg.length;
      expect(forMsg.length, 'exactly one durable webhook record for the customer message').toBe(1);

      await S.waitFor('bot public reply', async () => (await S.cwMessages(st.convId)).some(S.isBotPublic), 240_000);
      await new Promise((r) => setTimeout(r, 15_000)); // allow a duplicate reply to appear before counting
      const bots = (await S.cwMessages(st.convId)).filter(S.isBotPublic);
      ev.botPublicReplies = bots.length;
      expect(bots.length, 'exactly one bot reply in Chatwoot').toBe(1);
      st.botAnswer = bots[0].content ?? '';
      ev.botSenderType = bots[0].sender?.type;
      await expect(widget!.getByText(st.botAnswer.slice(0, 40))).toBeVisible({ timeout: 60_000 });
      await shot(page!, 'step06-bot-reply-visible');
    });

    // Step 7: grounded answer with the policy source. Known issue: dispatch clause is expected to be missing.
    await L.run(7, 'Answer states 3-5 business days after dispatch (1-2 business days) with a source', [6], async (ev) => {
      const a = st.botAnswer as string;
      ev.answer = a;
      const delivery = /3\s*[–-]\s*5\s+business\s+days/i.test(a);
      const dispatch = /1\s*[–-]\s*2\s+business\s+days/i.test(a);
      const source = /source|acme-support-policy|policy|\[\d+\]/i.test(a);
      ev.checks = { deliveryWindow: delivery, dispatchClause: dispatch, sourceReference: source };
      expect(delivery, 'answer contains 3-5 business days after dispatch').toBe(true);
      expect(source, 'answer carries a policy source reference').toBe(true);
      expect(dispatch, 'KNOWN ISSUE: answer contains the 1-2 business days dispatch clause').toBe(true);
    });

    // Step 8: explicit human request.
    // Step 8 depends on step 5 only: the handoff path does not need an AI answer, so it still runs when step 6 fails.
    await L.run(8, 'Customer asks for a human agent', [5], async (ev) => {
      const input = widget!.locator('textarea').first();
      await input.fill(Q_HUMAN);
      await input.press('Enter');
      await expect(widget!.getByText(Q_HUMAN, { exact: true })).toBeVisible({ timeout: 30_000 });
      const m = (await S.cwMessages(st.convId)).find((x) => S.isIncoming(x) && x.content === Q_HUMAN);
      expect(m, 'human request stored in Chatwoot').toBeTruthy();
      st.handoffMsgId = m!.id;
      ev.handoffRequestMessageId = m!.id;
    });

    // Step 9: handoff state, private note, open status, no AI message after the request.
    await L.run(9, 'Handoff: HUMAN_ACTIVE, one private note, conversation open, no AI message', [8], async (ev) => {
      const seen = new Set<string>();
      const owner = await S.waitFor(
        'ownership HUMAN_ACTIVE',
        async () => {
          const c = await S.adminGet<any>(`conversations/${st.convId}`);
          seen.add(c.state);
          return c.state === 'HUMAN_ACTIVE' ? c : null;
        },
        120_000,
        1000,
      );
      ev.observedOwnershipStates = [...seen];
      ev.finalState = owner.state;
      const conv = await S.cwConversation(st.convId);
      ev.chatwootStatus = conv.status;
      expect(conv.status, 'Chatwoot conversation is open').toBe('open');
      const msgs = await S.cwMessages(st.convId);
      const notes = msgs.filter(S.isPrivateNote);
      ev.privateNotes = notes.length;
      expect(notes.length, 'exactly one private handoff note').toBe(1);
      st.noteText = notes[0].content ?? '';
      const aiAfter = msgs.filter((m) => S.isBotPublic(m) && m.id > st.handoffMsgId);
      ev.publicAiMessagesAfterHandoff = aiAfter.length;
      expect(aiAfter.length, 'no customer-visible AI message after handoff request').toBe(0);
      await shot(page!, 'step09-after-handoff-widget');
    });

    // Step 10: human agent opens the conversation in the Chatwoot dashboard (second browser context).
    // No Playwright trace for this context: the login POST and the dashboard session headers would be recorded.
    await L.run(10, 'Human agent sees the private handoff note in the Chatwoot dashboard', [9], async (ev) => {
      const human = await openContext(browser, 'human-agent', false);
      contexts.push(human);
      const p = await human.ctx.newPage();
      await p.goto(`${S.CHATWOOT}/app/login`);
      await p.locator('input[name=email_address], input[type=email], input[name=email]').first().fill(S.secrets.humanEmail, { timeout: 30_000 });
      await p.locator('input[type=password]').first().fill(S.secrets.humanPassword, { timeout: 30_000 });
      await p.locator('button[type=submit]').first().click({ timeout: 30_000 });
      try {
        await p.waitForURL(/\/app\/accounts\/4\//, { timeout: 60_000 });
      } catch (e) {
        // Observed: /app/login redirects to /installation/onboarding ("Finish Setup"), so no sign-in form exists.
        ev.loginUrl = new URL(p.url()).pathname;
        await shot(p, 'step10-human-login-blocked');
        throw new Error(`dashboard sign-in did not reach /app/accounts/4 (landed on ${ev.loginUrl})`);
      }
      // Trace starts only after sign-in, so the login POST (password) is not recorded. Session headers are redacted later.
      human.trace = true;
      await human.ctx.tracing.start({ screenshots: true, snapshots: true });
      await p.goto(`${S.CHATWOOT}/app/accounts/4/conversations/${st.convId}`);
      const snippet = st.noteText.replace(/\s+/g, ' ').trim().slice(0, 25);
      ev.noteSnippetLength = snippet.length;
      await expect(p.getByText(snippet, { exact: false }).first()).toBeVisible({ timeout: 60_000 });
      await shot(p, 'step10-human-dashboard-note');
      st.humanPage = p;
      st.humanCtx = human;
    });

    // Step 11: human agent replies through the Chatwoot dashboard UI. No API fallback: if the UI path fails, step 11 FAILs.
    await L.run(11, 'Human agent sends the reply through the Chatwoot UI', [10], async (ev) => {
      const p: Page = st.humanPage;
      ev.path = 'UI';
      const editor = p.locator('.ProseMirror').first();
      await expect(editor, 'dashboard reply editor visible').toBeVisible({ timeout: 30_000 });
      await editor.click();
      await p.keyboard.type(HUMAN_REPLY);
      // Chatwoot labels the button "Send (CTRL + ↵)"; Ctrl+Enter is the keyboard equivalent.
      const send = p.getByRole('button', { name: /^send/i }).first();
      if (await send.waitFor({ state: 'visible', timeout: 10_000 }).then(() => true, () => false)) await send.click();
      else await p.keyboard.press('Control+Enter');
      const m = await S.waitFor(
        'human public reply in Chatwoot (sent from UI)',
        async () => (await S.cwMessages(st.convId)).find((x) => S.isHumanPublic(x) && x.content === HUMAN_REPLY),
        60_000,
      );
      st.humanMsgId = m!.id;
      ev.humanReply = { id: m!.id, private: m!.private, senderType: m!.sender?.type ?? null, messageType: m!.message_type };
      await shot(p, 'step11-human-reply-sent');
      // AI-06: a human outgoing message must not start an AI turn. Look for any platform webhook row for it.
      await new Promise((r) => setTimeout(r, 15_000));
      const rowsForHuman = (await S.adminGet<any>('webhooks')).webhooks.filter((x: any) => x.chatwootMessageId === st.humanMsgId);
      ev.platformRowsForHumanMessage = rowsForHuman.map((x: any) => ({ eventKind: x.eventKind, status: x.status, outcome: x.outcome }));
      const botAfterHuman = (await S.cwMessages(st.convId)).filter((x) => S.isBotPublic(x) && x.id > st.humanMsgId);
      ev.botRepliesAfterHumanMessage = botAfterHuman.length;
      expect(botAfterHuman.length, 'no AI reply triggered by the human message').toBe(0);
    });

    // Step 12: reply reaches the customer widget.
    await L.run(12, 'Customer receives the human reply in the same widget conversation', [11], async () => {
      await expect(widget!.getByText(HUMAN_REPLY, { exact: true })).toBeVisible({ timeout: 90_000 });
      await shot(page!, 'step12-human-reply-in-widget');
    });

    // Step 13: follow-up while the human owns the conversation.
    await L.run(13, 'Customer sends a follow-up while the human owns the conversation', [12], async (ev) => {
      const input = widget!.locator('textarea').first();
      await input.fill(Q_FOLLOWUP);
      await input.press('Enter');
      await expect(widget!.getByText(Q_FOLLOWUP, { exact: true })).toBeVisible({ timeout: 30_000 });
      const m = await S.waitFor(
        'follow-up stored in Chatwoot',
        async () => (await S.cwMessages(st.convId)).find((x) => S.isIncoming(x) && x.content === Q_FOLLOWUP),
        60_000,
      );
      st.followupMsgId = m!.id;
      ev.followupMessageId = m!.id;
    });

    // Step 14: no AI reply, ownership unchanged, platform recorded the event without a customer AI answer.
    await L.run(14, 'No AI reply after takeover; ownership stays HUMAN_ACTIVE', [13], async (ev) => {
      const rec = await S.waitFor(
        'PROCESSED webhook record for follow-up',
        async () =>
          (await S.adminGet<any>('webhooks')).webhooks.find((x: any) => x.chatwootMessageId === st.followupMsgId && x.status === 'PROCESSED'),
        180_000,
      );
      ev.followupWebhook = { status: rec.status, eventKind: rec.eventKind, outcome: rec.outcome };
      expect(rec.outcome, 'follow-up outcome recorded by the platform').toBeTruthy();
      expect(rec.outcome, 'follow-up did not produce a SENT AI outcome').not.toBe('SENT');
      await new Promise((r) => setTimeout(r, QUIET_WINDOW_MS));
      const aiAfter = (await S.cwMessages(st.convId)).filter((m) => S.isBotPublic(m) && m.id > st.followupMsgId);
      ev.publicAiMessagesAfterFollowup = aiAfter.length;
      expect(aiAfter.length, 'no automatic AI reply after takeover').toBe(0);
      const c = await S.adminGet<any>(`conversations/${st.convId}`);
      ev.stateAfterFollowup = c.state;
      expect(c.state).toBe('HUMAN_ACTIVE');
      await shot(page!, 'step14-after-followup');
    });

    // Step 15: replay of the original customer webhook (reconstructed from the stored message, same message id,
    // signed with the bot secret; a fresh delivery id). Direct POST to the platform webhook, not a UI action.
    await L.run(15, 'Replayed customer webhook creates no duplicate reply or handoff note', [9], async (ev) => {
      const msgs = await S.cwMessages(st.convId);
      const orig = msgs.find((m) => m.id === st.firstMsgId)!;
      const payload = {
        event: 'message_created',
        id: orig.id,
        content: orig.content,
        message_type: 'incoming',
        content_type: 'text',
        private: false,
        created_at: orig.created_at,
        sender: { id: orig.sender?.id, name: orig.sender?.name, type: 'contact' },
        conversation: { id: st.convId, status: 'open', inbox_id: S.INBOX },
        account: { id: S.ACCOUNT },
        inbox: { id: S.INBOX },
      };
      const before = {
        bots: (await S.cwMessages(st.convId)).filter(S.isBotPublic).length,
        notes: (await S.cwMessages(st.convId)).filter(S.isPrivateNote).length,
        rows: (await S.adminGet<any>('webhooks')).webhooks.filter((x: any) => x.chatwootMessageId === st.firstMsgId).length,
      };
      const r = await S.postSignedWebhook(payload);
      ev.replayHttpStatus = r.status;
      expect(r.status, 'webhook endpoint accepted the replay').toBeGreaterThanOrEqual(200);
      expect(r.status).toBeLessThan(300);
      await new Promise((res) => setTimeout(res, 20_000));
      const after = {
        bots: (await S.cwMessages(st.convId)).filter(S.isBotPublic).length,
        notes: (await S.cwMessages(st.convId)).filter(S.isPrivateNote).length,
        rows: (await S.adminGet<any>('webhooks')).webhooks.filter((x: any) => x.chatwootMessageId === st.firstMsgId).length,
      };
      ev.counts = { before, after };
      expect(after.bots, 'no duplicate bot reply').toBe(before.bots);
      expect(after.notes, 'no duplicate handoff note').toBe(before.notes);
    });

    // Step 16: restart only platform-api (host driver performs `docker compose restart platform-api`).
    await L.run(16, 'Platform API restart keeps HUMAN_ACTIVE; next customer message gets no AI reply', [9], async (ev) => {
      const rowsBefore = (await S.adminGet<any>('webhooks')).webhooks.map((x: any) => x.id).sort();
      writeFileSync(path.join(S.CONTROL, 'restart-request'), new Date().toISOString());
      const done = await S.waitFor(
        'driver restart-done marker',
        async () => (existsSync(path.join(S.CONTROL, 'restart-done')) ? readFileSync(path.join(S.CONTROL, 'restart-done'), 'utf8') : null),
        420_000,
        2000,
      );
      ev.restartResult = done.trim().slice(0, 200);
      expect(done.startsWith('OK'), 'platform-api restarted and healthy').toBe(true);
      const rowsAfter = (await S.adminGet<any>('webhooks')).webhooks.map((x: any) => x.id);
      ev.webhookRowsBeforeAfter = [rowsBefore.length, rowsAfter.length];
      expect(rowsBefore.every((id: string) => rowsAfter.includes(id)), 'webhook inbox rows survive restart').toBe(true);
      const c = await S.adminGet<any>(`conversations/${st.convId}`);
      ev.stateAfterRestart = c.state;
      expect(c.state, 'ownership still HUMAN_ACTIVE after restart').toBe('HUMAN_ACTIVE');

      const input = widget!.locator('textarea').first();
      await input.fill(Q_AFTER_RESTART);
      await input.press('Enter');
      await expect(widget!.getByText(Q_AFTER_RESTART, { exact: true })).toBeVisible({ timeout: 30_000 });
      const m = await S.waitFor(
        'post-restart customer message stored',
        async () => (await S.cwMessages(st.convId)).find((x) => S.isIncoming(x) && x.content === Q_AFTER_RESTART),
        60_000,
      );
      st.restartMsgId = m!.id;
      await S.waitFor(
        'webhook record for post-restart message',
        async () => (await S.adminGet<any>('webhooks')).webhooks.find((x: any) => x.chatwootMessageId === st.restartMsgId && x.status === 'PROCESSED'),
        180_000,
      );
      await new Promise((r) => setTimeout(r, QUIET_WINDOW_MS));
      const aiAfter = (await S.cwMessages(st.convId)).filter((x) => S.isBotPublic(x) && x.id > st.restartMsgId);
      ev.publicAiMessagesAfterRestart = aiAfter.length;
      expect(aiAfter.length, 'no AI reply to the post-restart customer message').toBe(0);
      const c2 = await S.adminGet<any>(`conversations/${st.convId}`);
      ev.stateAfterPostRestartMessage = c2.state;
      expect(c2.state).toBe('HUMAN_ACTIVE');
      await shot(page!, 'step16-after-restart');
    });

    // Step 17: evidence. Sanitized API observations are written here; traces/report are finalized after the run.
    await L.run(17, 'Evidence saved: sanitized API observations, screenshots, traces, ledger', [], async (ev) => {
      const msgs = st.convId ? await S.cwMessages(st.convId) : [];
      const conv = (k: string) => (st.convId ? S.adminGet<any>(`conversations/${st.convId}`) : Promise.resolve(null));
      const observations = {
        conversationId: st.convId,
        customerMessageIds: { firstQuestion: st.firstMsgId, handoffRequest: st.handoffMsgId, humanReply: st.humanMsgId, followup: st.followupMsgId, afterRestart: st.restartMsgId },
        messages: msgs.map((m) => ({
          id: m.id,
          messageType: m.message_type,
          private: m.private,
          senderType: m.sender?.type ?? null,
          createdAt: m.created_at,
          contentPreview: (m.content ?? '').slice(0, 300),
        })),
        platformWebhooks: (await S.adminGet<any>('webhooks')).webhooks.filter((x: any) => x.chatwootConversationId === st.convId),
        platformConversation: await conv('state'),
        platformDiagnostics: await S.adminGet<any>('diagnostics'),
        consoleErrors: consoleErrors.slice(0, 50),
        variant: st.variant,
      };
      S.writeArtifact('api/observations.json', JSON.stringify(observations, null, 2));
      ev.observationsFile = 'artifacts/e2e/api/observations.json';
    });
  } finally {
    for (const c of contexts) await closeContext(c).catch(() => undefined);
    S.writeArtifact('api/run-state.json', JSON.stringify({ conversationId: st.convId ?? null, variant: st.variant }, null, 2));
    L.flush();
  }

  const failures = L.records.filter((r) => r.verdict !== 'PASS').map((r) => `step ${r.step} ${r.verdict}: ${r.reason ?? ''}`);
  expect(failures, 'golden flow steps not all PASS').toEqual([]);
});
