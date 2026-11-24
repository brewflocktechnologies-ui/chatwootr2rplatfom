# platform-api (backend) — verification record

Scope: `apps/platform-api/` only. Stack: JDK 25 (`eclipse-temurin:25`), Spring Boot 4.2.0, Spring Modulith 2.2.0, Flyway, PostgreSQL 16 (pgvector image), Maven Wrapper (3.9.16).

## Run

```
cd apps/platform-api
./mvnw -B verify                      # 59 tests incl. Testcontainers PostgreSQL and Modulith verification
./mvnw -B -DskipTests package         # target/platform-api-0.1.0.jar
docker build -t saas/platform-api:local .
```

Env names are the frozen set in `docs/architecture/api-contract.md`, plus `CHATWOOT_AGENT_BOT_ID` (see Gaps). The app binds `127.0.0.1:8080` by default; containers set `SERVER_ADDRESS=0.0.0.0` and publish only on the host loopback.

## Verified Chatwoot contracts (v4.20.1, account 4, inbox 1)

Probed 24 Nov 2026 against the running stack.

- AgentBot signature: `X-Chatwoot-Signature: sha256=HMAC_SHA256(bot_secret, "<X-Chatwoot-Timestamp>.<raw body>")`. Verified on the captured delivery `002-*.json` (`match true`). Implementation verifies the raw bytes.
- **Private note (verified, 200).** Used the bot token (`api_access_token` header).
  Request: `POST /api/v1/accounts/4/conversations/1/messages`, header `api_access_token: <bot token>`, body `{"content":"...","message_type":"outgoing","private":true}`.
  Response 200 keys: `id, content, inbox_id, conversation_id, message_type, content_type, status, content_attributes, created_at, private, source_id, sender`. Observed `private: true`, `message_type: 1`, `sender.type: agent_bot`. Admin token gives the same 200. Probe notes ids 15 and 16 were added to conversation 1.
- Bot reply: `POST .../messages` with `{"content","message_type":"outgoing","private":false}` → 200, sender `agent_bot` (live run: message 18).
- Status: `POST .../toggle_status {"status":"open"|"pending"}` → 200 with bot token, `{"payload":{"success":true,"conversation_id":1,"current_status":...}}`.
- Conversation read: `GET .../conversations/{id}` → 200 with admin token. **Bot token gets 200 here, but 401 on `GET .../conversations/1/messages`.** Message listing (used for the note-marker retry check) therefore uses the admin token.
- Human public reply payload (`message_type: outgoing`, `private: false`, `sender.type: user`) is **UNVERIFIED**: no captured sample exists. The code moves ownership to HUMAN_ACTIVE on that shape; only the private-note variant (`021-*.json`, sender `user`, private true) was observed.

## Verified R2R 3.6.5 contracts

- `POST /v3/users/login` (form) → `results.access_token.token`.
- `POST /v3/retrieval/rag` body includes `search_settings.filters.collection_ids.$overlap` (tenant collection), `include_web_search:false`, `rag_generation_config.model=ollama/llama3.2:3b`, and `task_prompt` (see below). Response: `results.generated_answer`, `results.search_results.chunk_search_results[]` with `score`, `document_id`, `metadata.title`. `results.citations` was empty in every call, so source names come from chunk metadata.
- Document upload (`POST /v3/documents`, multipart, `ingestion_mode=fast`) and status (`GET /v3/documents/{id}`) are implemented per the contract but **not exercised live** (the policy document is already ingested; see Gaps).

### Evidence threshold (measured, 4 queries, tenant identity)

| query | top score |
|---|---|
| What is your standard delivery time? | 0.649 |
| Can I return a product after 30 days? | 0.725 |
| Do you ship to Mars? | 0.437 |
| What is the capital of France? | 0.265 |

`AGENT_EVIDENCE_MIN_SCORE` default **0.50**: separates in-domain (≥0.55) from out-of-domain (≤0.44) on this sample. Four queries is a small sample, not a calibration.

### Answer quality finding and mitigation

Without a grounding prompt, llama3.2:3b answered the delivery question incorrectly ("4-7 business days from the day it is placed"). The adapter now sends a versioned `task_prompt` (`GROUNDED_TASK_PROMPT`, grounded-v1). With it the answer is "Our standard delivery time is 3-5 business days after dispatch." The "dispatch takes 1–2 business days" clause from golden step 7 is **not** in the answer; a partial match. Re-check on each model change.

## Test results (run 24 Nov 2026)

`./mvnw -B -ntp package` → **59 tests, 0 failures, 0 errors; BUILD SUCCESS.**

- Unit: signature verifier (valid, tampered body, wrong secret, stale timestamp, malformed headers), `ChatwootEvent` classification, human-request matcher, evidence policy, state machine, handoff note, knowledge validation (path traversal, extension, size, PDF magic, UTF-8/NUL, sha256).
- `ModularityTest`: `ApplicationModules.verify()` passes.
- Integration (Testcontainers `pgvector/pgvector:pg16`): webhook dedupe (second delivery → `duplicate`, one row), bad/stale signature → 401 with no row, bot reply ignored, admin key required, public widget config has no secret, conversation lock serialises, version increments, claim is exclusive, grounded send once, human request hands off before retrieval, no AI after handoff, no-evidence and R2R-failure hand off (3 retries), timed-out private note found by marker (no second note), redelivery never double-sends, late answer discarded on ownership change, uncertain send is not retried (`SEND_UNKNOWN`), explicit audited resume.

## Live smoke run (host JVM, real Chatwoot v4.20.1 and R2R 3.6.5, Ollama)

Platform DB: container `saas-platform-db` (pgvector pg16, host `127.0.0.1:55432`, volume `saas-platform-db-data`). Replay tool signs the sanitized fixture with the real bot secret.

| step | result |
|---|---|
| customer "What is your standard delivery time?" (msg 7), signed replay | 202 accepted; webhook PROCESSED `SENT`; exactly one new `agent_bot` reply: "Our standard delivery time is 3-5 business days after dispatch. Source: Acme Demo Support Policy (FICTIONAL)" |
| "I need to speak to a human agent, please." (msg 8) | 202; `HANDED_OFF`; one private note (id 19, handoff ref `acme-demo/conversation-1/generation-0`); state `HUMAN_ACTIVE`; Chatwoot toggled open; no R2R call for this turn |
| "Can you also check this for me?" (msg 9) | `NOT_AI_ACTIVE`; no customer-visible message |
| redelivery of msg 7 | 202 `duplicate`; no new reply, no new note |
| platform process restarted (new pid), then msg 11 | ownership still `HUMAN_ACTIVE`; `NOT_AI_ACTIVE`; no reply |

Golden-flow steps not run here: steps 10–12 need a human agent in the Chatwoot UI (not done; the human reply path is unverified), step 3 (admin knowledge list) and the widget steps belong to the frontend and lead.

Docker image: `saas/platform-api:local`, id `sha256:164e0c5c4922b997cbb2ed714fdf25b8ccae8ae75320b260de8937dfb5093d10`, amd64. A container started with the same env reported `/actuator/health` UP and "Started" (smoke container removed).

## Gaps and risks

- **agentBotId / CHATWOOT_AGENT_BOT_ID**: `GET /api/admin/tenant` needs `agentBotId` (number), but the frozen env list has no bot id. I added `CHATWOOT_AGENT_BOT_ID` (already present in `infra/chatwoot/.env.secrets`). The lead must add it to the frozen contract, or the tenant shape cannot be met.
- **Ollama**: no platform env var exists for it, so diagnostics reports `ollama` as `DEGRADED` with a detail string, not a real probe.
- **Chatwoot "AI-eligible" check**: the new conversation in this inbox is `open`, so eligibility is `status in {open,pending} AND unassigned`, plus local ownership. A human reply that does not assign the conversation is caught by the local HUMAN_ACTIVE transition only if it arrives as an outgoing `sender.type=user` event (unverified shape).
- **Knowledge documents**: the policy was ingested by probes, not through the platform, so `knowledge_source` is empty in the platform DB. Upload through `POST /api/admin/knowledge/documents` is needed for the admin list to show it. Retry stores the file bytes in the platform DB (≤2 MiB) to allow re-ingest.
- **Handoff on R2R failure**: after 3 bounded retries the platform hands off (contract: "R2R error → handoff") rather than sending nothing. This is an interpretation; the conversation opens for a human.
- **Admin Flyway**: migrations run automatically at startup (Flyway, validated versioned SQL); no separate migration command.
- **Widget public URL**: `/api/public/widget-config` returns `CHATWOOT_BASE_URL`, which is `host.docker.internal` from containers; the browser needs a browser-reachable URL, which the contract does not define.
- **Logging**: customer text is stored in `webhook_inbox.customer_text` until the turn completes (cleared on DONE/FAILED) and is never logged.
- **R2R security**: retrieval scoping relies on the tenant service identity's collection filter. Not re-verified in this session beyond the probe-level isolation results recorded in `artifacts/probes`. Record in `docs/security/r2r-risk-review.md` is the lead's.
