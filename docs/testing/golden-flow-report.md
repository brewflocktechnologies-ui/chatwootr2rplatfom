# Golden flow report (bootstrap section 9), rerun on the current stack

Date: 2026-11-24. Tenant: acme-demo. Stack: real local Chatwoot v4.20.1, platform-api (current image with the backend review fixes and admin proxy write guard), admin web, R2R 3.6.5, Ollama. No stubs.

## Result

- **Golden flow (steps 1-17): all PASS.** Fresh conversation **58**. The human reply (step 11) was sent through the real Chatwoot dashboard UI (no API send).
- **Step 7 PASS**: the answer includes the 1-2 business day dispatch clause, the 3-5 business day delivery window, and a source. This is the first run where the clause appeared; it was missing in every earlier run.
- **Negative controls: 3 of 3 PASS.** Each hands off in its own fresh conversation, with no invented policy.
- **Returns control (104): PASS on re-check (conversation 59).** The first run (conversation 47) FAILED on an over-strict assertion of mine (`unused items`). The reply itself was the returns sentence with the source, which is the lead's expectation. I corrected the assertion and re-ran that control alone.
- **Delivery 10-run check: 10 of 10 PASS**, each in its own fresh conversation (48 to 57), each with exactly one bot reply, the 3-5 day window, a source, and the dispatch clause.
- **Overall run label: PASS for the scope above. PARTIAL for coverage**, because several backend review items are NOT RUN (listed below).

## Golden flow step verdicts (conversation 58)

| # | Step | Verdict | Evidence |
|---|---|---|---|
| 1 | Doctor and status, saved | PASS | `artifacts/e2e/step01-local-status.txt`, `step01-local-doctor.txt` (driver step 1, exit 0) |
| 2 | Services healthy | PASS | Driver step 2: Chatwoot, R2R, Ollama, platform readiness, admin web all 200. `artifacts/e2e/driver-summary.json` |
| 3 | Admin knowledge shows acme-demo and policy READY | PASS | Row `acme-support-policy.md READY`. `artifacts/e2e/screenshots/step03-admin-knowledge-status.png` |
| 4 | Demo page opens native widget, clean context | PASS | `artifacts/e2e/screenshots/step04-widget-open.png` |
| 5 | Customer asks "What is your standard delivery time?" | PASS | Conversation 58, customer message 371. `api/ledger.json` |
| 6 | One webhook record, exactly one bot reply in widget | PASS | 1 record, 1 public bot reply (sender `agent_bot`). `screenshots/step06-bot-reply-visible.png` |
| 7 | Answer: 3-5 business days after dispatch (1-2 business days), source | PASS | Answer: "Standard orders are dispatched within 1-2 business days of being placed. Standard delivery takes 3-5 business days after dispatch. Customers therefore receive a standard order in roughly 4-7 business days from the day it is placed. A return request may be submitted within 14 calendar days after delivery. Source: Acme Demo Support Policy (FICTIONAL)". The answer also carries the returns sentence, which the question did not ask for. |
| 8 | "I need to speak to a human agent, please." | PASS | Message 375 |
| 9 | Handoff: HUMAN_ACTIVE, one private note, open, no AI message | PASS | Ownership AI_ACTIVE then HUMAN_ACTIVE (HANDOFF_PENDING not observed by polling). Chatwoot open. One private note. 0 public bot messages after the request. `api/observations.json` |
| 10 | Human agent signs in to the Chatwoot dashboard and sees the private note | PASS | Sign-in via the real dashboard (email field `email_address`). Note visible. `screenshots/step10-human-dashboard-note.png`. Trace `traces/human-agent.zip` (starts after sign-in) |
| 11 | Human agent sends "Hello, I'm Alex from support. I'll review this and help you." | PASS (**UI**) | Sent with the "Send (CTRL + ↵)" button. Message 378: `message_type=1`, `private=false`, `sender.type=user`. Platform: `HUMAN_REPLY` / `HUMAN_REPLY_RECORDED`. No bot reply after it. `screenshots/step11-human-reply-sent.png` |
| 12 | Customer receives the human reply in the widget | PASS | `screenshots/step12-human-reply-in-widget.png` |
| 13 | Customer: "Can you also check this for me?" | PASS | Message 379 |
| 14 | No AI reply; HUMAN_ACTIVE; recorded without AI | PASS | Webhook PROCESSED, outcome `NOT_AI_ACTIVE`. No public bot message in a 45 s quiet window |
| 15 | Replay the same webhook; no duplicate reply or note | PASS | Signed replay returned 202. Before and after: bot messages 1/1, private notes 1/1, webhook rows 1/1. Platform log: `status=duplicate`. This check is meaningful because a bot reply exists. |
| 16 | Restart platform-api only; ownership stays human; no AI reply | PASS | `docker compose restart platform-api` (infra/platform) exit 0, readiness 200 at 10:12:16Z. HUMAN_ACTIVE before and after. Webhook rows 50/50. Post-restart message `NOT_AI_ACTIVE`, no bot reply. `control/restart-done` |
| 17 | Save evidence | PASS | See artifacts below |

Golden flow run window (UTC): 10:05:26Z to 10:14:05Z. Driver exit code 0. Ledger: `artifacts/e2e/api/ledger.json`.

## Negative controls and returns control

Fresh conversation per control (own browser context, own widget contact). Ledger: `artifacts/e2e/runs/run-12-full-controls-and-golden/api/ledger-controls.json` (full run). The top-level `artifacts/e2e/api/ledger-controls.json` now holds only the re-run of control 104.

| Step | Question | Conversation | Verdict | Evidence |
|---|---|---|---|---|
| N1 (101) | What is your refund policy for opened electronics? | 44 | PASS: handed off. State HUMAN_ACTIVE, 1 private note, 0 bot replies, webhook HANDED_OFF | `screenshots/control-n101.png` |
| N2 (102) | Do you sell gift cards? | 45 | PASS: handed off (same checks) | `screenshots/control-n102.png` |
| N3 (103) | Can I change my delivery address after dispatch? | 46 | PASS: handed off (same checks) | `screenshots/control-n103.png` |
| R (104), first run | What is your return policy? | 47 | FAIL on my over-strict assertion (`unused items`). Reply was "A return request may be submitted within 14 calendar days after delivery. Source: Acme Demo Support Policy (FICTIONAL)", which meets the lead's expectation. | `runs/run-12-full-controls-and-golden/api/ledger-controls.json` |
| R (104), re-run | What is your return policy? | 59 | PASS: exactly one reply, 14 calendar days, source, no delivery or refund text | `api/ledger-controls.json` (top-level), `screenshots/control-r104.png`, `E:\SAAS\PLATFORM\artifacts\e2e-control104-rerun.log` |

## Delivery 10-run check ("What is your standard delivery time?")

Each run in its own fresh conversation. Per-run data: `artifacts/e2e/api/delivery-10-runs.json`.

| Run | Conversation | Exactly 1 bot reply | 3-5 business days | Source | 1-2 business days dispatch clause |
|---|---|---|---|---|---|
| 1 | 48 | yes | yes | yes | yes |
| 2 | 49 | yes | yes | yes | yes |
| 3 | 50 | yes | yes | yes | yes |
| 4 | 51 | yes | yes | yes | yes |
| 5 | 52 | yes | yes | yes | yes |
| 6 | 53 | yes | yes | yes | yes |
| 7 | 54 | yes | yes | yes | yes |
| 8 | 55 | yes | yes | yes | yes |
| 9 | 56 | yes | yes | yes | yes |
| 10 | 57 | yes | yes | yes | yes |

Screenshots: `artifacts/e2e/screenshots/control-d1.png` to `control-d10.png`. Summary step 211 PASS.

Observation: every delivery answer also includes the returns sentence and the full policy paragraph, not only the delivery sentences. Answers are longer than the question needs.

## NOT RUN (not exercised in this run)

- **Admin proxy write guard**: no test covers it. Not in the golden flow scope.
- **Durable SENDING marker** and **unknown-send handoff**: need fault injection (Chatwoot timeout or send failure). Not run.
- **Queued-human check**: no deterministic UI path. Not run.
- **Duplicate-note check** on a duplicate handoff webhook: the replay in step 15 covers the replayed customer message (no duplicate note). A duplicate handoff delivery was not separately sent.
- **HO-08** (late AI result discarded): not run.
- **KB-06** (unsupported question must not invent policy): covered only by negative controls N1 to N3 (no invented policy in bot replies). Not a separate KB-06 test.
- **AI-07** (Ollama-only inference, network evidence): not run.

## Acceptance IDs (this run)

- PASS: HO-01, HO-03, HO-05, HO-06 (UI reply, received in widget), HO-07, HO-09, HO-10 (observational), AI-04, AI-06, RS-01, RS-02 (row count only).
- PASS on controls: KB-06-like behaviour for N1 to N3 (handoff, no invented facts). Not a formal KB-06 run.
- PARTIAL: HO-02 (HANDOFF_PENDING not observed), HO-04 (conversation opened from the dashboard; inbox listing not asserted), AI-02, AI-03 (webhook record only), AI-05, KB-04, KB-05 (answer checked; R2R raw response not inspected).
- NOT RUN: HO-08, AI-07, KB-06 (formal), plus the backend review items above.

## Harness notes

- The full run's driver reported exit 0 even though control 104 had failed. The controls spec did not fail its Playwright test on a ledger FAIL. Fixed: the controls spec now fails if any ledger step is not PASS. Ledger verdicts are the source of truth.
- Control 104 was re-run alone with `GF_CONTROL_ONLY=104` (new conversation 59). The first run's conversation (47) is kept as evidence.
- Runtime redaction ran on the full run before the archive (JWT-like strings, auth headers, credential values). Log: `artifacts/e2e/runs/run-12-full-controls-and-golden/redaction-log.txt`, and `artifacts/e2e/redaction-log.txt` for the top-level run.
- Post-run scan (this report): 761 text files including archives and extracted trace zips, plus 20 zip archives under `artifacts/e2e`: 0 hits for credential values, JWT-like strings, or auth header values.
- `artifacts/e2e/screenshots/step10-human-login-blocked.png` is a leftover from an earlier failed sign-in. It is not from this run.

## Artifacts (absolute paths)

- HTML report (golden run and controls): `E:\SAAS\PLATFORM\artifacts\e2e\html-report\index.html`
- Ledger, golden flow: `E:\SAAS\PLATFORM\artifacts\e2e\api\ledger.json`
- Ledger, controls (re-run of 104): `E:\SAAS\PLATFORM\artifacts\e2e\api\ledger-controls.json`
- Delivery 10-run data: `E:\SAAS\PLATFORM\artifacts\e2e\api\delivery-10-runs.json`
- Sanitized observations (golden): `E:\SAAS\PLATFORM\artifacts\e2e\api\observations.json`
- Platform log (run window): `E:\SAAS\PLATFORM\artifacts\e2e\api\platform-api.log`
- Traces (redacted): `E:\SAAS\PLATFORM\artifacts\e2e\traces\customer.zip`, `admin.zip`, `human-agent.zip`
- Screenshots: `E:\SAAS\PLATFORM\artifacts\e2e\screenshots\`
- Driver summary, container log: `E:\SAAS\PLATFORM\artifacts\e2e\driver-summary.json`, `run-container.log`
- Re-run of control 104: `E:\SAAS\PLATFORM\artifacts\e2e-control104-rerun.log`

Archives (all kept):
- `E:\SAAS\PLATFORM\artifacts\e2e\runs\run-12-full-controls-and-golden\` (this full run: golden + controls + delivery; full controls ledger; previous report as `golden-flow-report-previous.md`)
- `E:\SAAS\PLATFORM\artifacts\e2e\runs\run-11-previous-full-run\` (prior golden run, conversation 12)
- `E:\SAAS\PLATFORM\artifacts\e2e\runs\run-07-pre-rerun\` to `run-10-overbroad-redaction\` (earlier iterations)
- `E:\SAAS\PLATFORM\artifacts\e2e\run-01-...` to `run-06-...` (earlier iterations)

Harness: `E:\SAAS\PLATFORM\apps\e2e\` (`tests\golden-flow.spec.ts`, `tests\controls.spec.ts`, `tests\support.ts`, `playwright.config.ts`, `scripts\run-golden-flow.mjs`, `project.json`).

## Commands

- `node apps/e2e/scripts/run-golden-flow.mjs` (full run, 10:05:26Z start). Runs `scripts/status.ps1`, `scripts/doctor.ps1`, the container run, and `docker compose restart platform-api` in `infra/platform` (exit 0).
- Container (full run and re-run): `docker run --rm --network host -v E:/SAAS/PLATFORM:/work -v saas-e2e-node-modules:/work/apps/e2e/node_modules -v saas-e2e-ms-playwright:/ms-playwright -v saas-e2e-pnpm-store:/pnpm-store -e npm_config_store_dir=/pnpm-store -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright [-e GF_CONTROL_ONLY=104] -w /work/apps/e2e node:26.13.1-bookworm-slim sh -c 'npm i -g pnpm@12.23.0 && pnpm install && pnpm exec playwright install --with-deps chromium && pnpm exec playwright test [tests/controls.spec.ts]'`
- Read-only checks: health endpoints; `GET /api/admin/*`; Chatwoot `GET /api/v1/accounts/4/*`; `/app/login` (200); `docker logs --since`.
- Test-side writes (intended): widget messages and dashboard reply (UI); one signed webhook replay (step 15); one restart request (step 16).

Constraints: no container stopped or removed except the platform-api restart. No edits under `infra/`, `scripts/`, `apps/platform-api`, `apps/admin-web`, or `docs/architecture/`. Nothing committed. `reset.ps1` not run.
