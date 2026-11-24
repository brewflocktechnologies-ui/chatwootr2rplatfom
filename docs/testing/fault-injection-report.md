# Fault-injection report (live, 2026-11-24)

Run: fault driver `apps/e2e/scripts/run-faults.mjs`, Playwright suite `apps/e2e/tests/faults.spec.ts` (GF_FAULTS=1), ledger `artifacts/e2e/api/ledger-faults.json`. Run window 11:03:46Z to 11:08:40Z.

Setup: fault proxy (`apps/e2e/tools/fault-proxy.mjs`): Chatwoot 127.0.0.1:3900 -> 3100, R2R 127.0.0.1:7373 -> 7272, control 3999. Throwaway platform, compose project `saas-e2e-faults`, port 8081, same image `saas-platform/platform-api:local`, secrets from `infra/platform/.env` via env_file, own DB volume. The AgentBot `outgoing_url` was switched to the throwaway during the run, restored in `finally`, and verified by GET.

Customer messages came from the real widget (admin web /demo to Chatwoot 3100). Platform state and Chatwoot messages were read through APIs. No state was changed to make a step pass.

## Results

| Test | Verdict | Conversation | Key observed values | Evidence |
|---|---|---|---|---|
| 1. Admin write guard, cross-site POST /api/admin/conversations/1/resume | PASS | n/a (main admin web) | 403 `forbidden_origin` | `artifacts/e2e/api/ledger-faults.json` step 1 |
| 2. Same-origin POST to the same route is not 403 | PASS | n/a | 409 `CONFLICT` ("resume requires HANDOFF_PENDING or HUMAN_ACTIVE, current state is AI_ACTIVE"). State unchanged: AI_ACTIVE before and after | step 2 |
| 3. GET tenant unchanged | PASS | n/a | identical before and after (tenantKey acme-demo, agentBotId 1, r2rCollectionId 89af8bb6-...) | step 3 |
| 4. Unknown-send handoff (HO, RS): public bot POST forwarded upstream, response withheld | PASS | 75 (customer message 453) | 1 public bot message (no duplicate). Note: "The AI reply attempt has an unknown outcome ... An AI reply may or may not have been delivered to the customer". Webhook row PROCESSED, outcome HANDED_OFF. Ownership HUMAN_ACTIVE | step 4; proxy entry seq 9 (hang, upstream 200 after 11.3 s) in `artifacts/e2e/faults/proxy-log.jsonl` |
| 5. Redelivery of the same webhook after the unknown send | PASS | 75 | HTTP 202. Before and after: bot 1/1, private notes 1/1, webhook rows 1/1 | step 5 |
| 6. HO-02: HANDOFF_PENDING observed during a 15 s toggle_status delay, then HUMAN_ACTIVE | PASS | 76 | observed states [HANDOFF_PENDING, HUMAN_ACTIVE]; HANDOFF_PENDING first seen at 6 ms; HUMAN_ACTIVE at 10.6 s (platform read timeout is 10 s, so the retry went through); 1 private note; Chatwoot status open. Proxy entry seq 15 (delay, 15.0 s) | step 6 |
| 7. HO-08: late RAG answer discarded after a human request during the in-flight turn | **FAIL** | 77 (first customer message 464, human request 467) | Assertion: `expect(bots.length, 'no bot answer reaches the customer').toBe(0)`. Observed: 1 public bot answer (message 468, "Standard orders are dispatched within 1-2 business days ... 3-5 business days after ...") posted when the RAG response returned (proxy status 200 after 20.6 s). Outcome for message 464: **SENT**. Outcome for the human request 467: HANDED_OFF (processed only after the first turn). Final state HUMAN_ACTIVE | step 7 |
| 8. Queued human reply: a human message lands during the 8 s GET messages (pre-send check) delay | PASS | 78 (customer 471, human 474) | human message sender type `user`; customer message outcome **HUMAN_TOOK_OVER**; human message outcome HUMAN_REPLY_RECORDED; 0 public bot messages after the human message; final state HUMAN_ACTIVE | step 8; proxy entry seq 28 (delay 8.0 s) |
| 9. AI-07 live turn completes through R2R | PASS (turn) | 79 | bot reply 176 characters; proxy logged 8 RAG calls during the run. Connection analysis below | step 9 |

## Test 7 analysis (for the backend)

The send guard checks ownership at send time. The human request (467) was queued behind the in-flight turn for conversation 77 (per-conversation serialization), so at the moment the late RAG answer returned, the platform still saw AI_ACTIVE and no discard was applied. The human request was handled after the first turn had already sent. Expected: the late answer is discarded, which requires the pending human request to be recognised before send, or the in-flight turn to observe the inbound human message. Not fixed here.

## AI-07 (Ollama-only inference)

Sampled every 2 s over the whole run: `artifacts/e2e/faults/ai07-samples.jsonl` (324 samples, 0 sample errors). Classified ESTABLISHED remote addresses from `/proc/net/tcp` and `tcp6`:
- throwaway platform container: 172.27.0.x (its own Docker network, DB), 192.168.65.254 (Docker Desktop host gateway, used for host.docker.internal: ports 3900 and 7373), 172.27.0.1 (bridge).
- main platform container: 172.26.0.x, 192.168.65.254 (ports 7272 and 3100), 172.26.0.1.
- R2R container: 172.25.0.x (its Postgres), 192.168.65.254:11434 (Ollama via host.docker.internal), 172.25.0.1.
- Ollama container: 172.24.0.1 and loopback only.
- Public addresses observed: none.

Caveat: this is a 2-second sample of each container's TCP table, not a packet capture. Short-lived connections between samples would be missed.

Config names (names only; values never printed), `artifacts/e2e/faults/ai07-config-names.json`:
- `infra/platform/.env`: the only name matching a provider-like pattern is `PLATFORM_ADMIN_API_KEY` (our own admin key, false positive). No hosted endpoint names.
- `infra/r2r/.env` and `infra/r2r/config/r2r.local.toml`: no provider-like key names, no hosted endpoint names.
- R2R container: 19 env var names, none provider-like.
- Main platform container: 33 env var names, only `PLATFORM_ADMIN_API_KEY` matched.
- Throwaway platform container: 0 names. The config step ran after the throwaway was torn down, so this entry is not valid evidence. Its env comes from the same `infra/platform/.env` as the main platform.
- `infra/ollama/.env` is absent.

Verdict: PASS for the observed window (no non-local remote address). Caveat as above.

## Harness notes

- Early step 4 to 9 failures ("fetch failed") were caused by the test container (host networking) not reaching the proxy control on 127.0.0.1. Fixed by using `host.docker.internal` for the proxy control and the throwaway platform URLs in the spec. These are not product findings.
- Step 6's proxy-entry field is `null` in the ledger because the check ran at 10.6 s, before the delayed toggle finished at 15 s. The proxy log has the entry (seq 15).
- The AgentBot URL was on the throwaway for the duration of the run only. Its value during the run: `http://host.docker.internal:8081/api/webhooks/chatwoot/agent-bot`.

## Restore and teardown (verified)

- AgentBot restored by the driver and verified by GET (`GET /api/v1/accounts/4/agent_bots/1`):
  `{"id":1,"name":"Acme Demo Support Agent","outgoing_url":"http://host.docker.internal:8080/api/webhooks/chatwoot/agent-bot"}`
  Record: `artifacts/e2e/faults/teardown.json` (`bot-url-restored`, restored true).
- Throwaway project removed with `docker compose -p saas-e2e-faults ... down -v` (exit 0; volume `saas-e2e-faults_faults_db` and network `saas-e2e-faults_default` removed). Post-check: no containers, volumes, or networks matching `saas-e2e-faults`. Record: `teardown.json` (`throwaway-removal-check`, removed true).
- Proxy process stopped (`proxy-stopped`).

## Artifacts

- Ledger: `E:\SAAS\PLATFORM\artifacts\e2e\api\ledger-faults.json`
- Proxy injection log: `E:\SAAS\PLATFORM\artifacts\e2e\faults\proxy-log.jsonl`
- Connection samples and config names: `E:\SAAS\PLATFORM\artifacts\e2e\faults\ai07-samples.jsonl`, `ai07-config-names.json`
- Teardown record: `E:\SAAS\PLATFORM\artifacts\e2e\faults\teardown.json`
- Driver and container logs: `E:\SAAS\PLATFORM\artifacts\e2e\faults\driver-stdout.log`, `run-container.log`
- Playwright HTML for this suite: `E:\SAAS\PLATFORM\artifacts\e2e\faults\html-report\index.html`
- Bot URL before the run: `E:\SAAS\PLATFORM\artifacts\e2e\faults\bot-original-url.json`
- Harness: `E:\SAAS\PLATFORM\apps\e2e\tools\fault-proxy.mjs`, `apps\e2e\faults\docker-compose.yml`, `apps\e2e\scripts\run-faults.mjs`, `apps\e2e\tests\faults.spec.ts`
