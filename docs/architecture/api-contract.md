# Platform API contract (MVP freeze candidate)

Status: **verified inputs** for implementation. Every upstream call below was probed against the running pinned
profiles (see `docs/research/*` once written, and `artifacts/probes/` locally). Do not change a route or env name
without updating this file and telling the lead.

## Ports and URLs (host side, loopback only)

| Service | URL | Notes |
|---|---|---|
| Chatwoot (pinned v4.20.1) | `http://127.0.0.1:3100` | managed-local profile `infra/chatwoot`, Compose project `saas-chatwoot` |
| R2R (pinned 3.6.5) | `http://127.0.0.1:7272` | managed-local profile `infra/r2r`, project `saas-r2r` |
| Ollama (0.40.17) | `http://127.0.0.1:11434` | project `saas-ollama`, models `llama3.2:3b`, `mxbai-embed-large` |
| Platform API | `http://127.0.0.1:8080` | Spring Boot 4.2.0 / Java 25 |
| Admin/demo web | `http://localhost:4300` | Next.js 16.4.x |

Containers reach host services at `http://host.docker.internal:<port>` (verified from the Chatwoot container).

## Verified upstream contracts

### Chatwoot (account 4, inbox 1, website inbox "Acme Demo Web", team 1, agent bot 1)
- AgentBot delivery: `POST <outgoing_url>` JSON. Headers: `X-Chatwoot-Delivery` (uuid), `X-Chatwoot-Timestamp` (unix seconds), `X-Chatwoot-Signature: sha256=<hex>`.
  Signature = `HMAC-SHA256(secret = agent bot secret, message = "<timestamp>.<raw body>")`. **Verified** on a captured payload (bot secret stored in `infra/chatwoot/.env.secrets`).
- Event observed for a customer message: `event = "message_created"`, `message_type = "incoming"`, `private = false`, `content_type = "text"`, `sender` = contact object (no `type` field), `conversation.status` = `open` at creation time (see note below), `account.id`, `inbox.id`, `conversation.id`, message `id`.
- Bot's own reply produces further `message_created` deliveries with `message_type = "outgoing"` and `sender.type = "agent_bot"` (must be ignored for AI generation).
- SafeFetch requires `SAFE_FETCH_ALLOW_PRIVATE_NETWORK=true` for private-host `outgoing_url` (local-only deviation; see `docs/security/threat-model.md`).
- Bot replies: `POST /api/v1/accounts/4/conversations/{id}/messages` with header `api_access_token: <bot access_token>` and JSON `{"content": "...", "message_type": "outgoing", "private": false}` → 200, sender `agent_bot`.
- Status: `POST /api/v1/accounts/4/conversations/{id}/toggle_status` with `{"status": "pending" | "open" | "resolved"}` → 200 with the bot token **and** with the admin token.
- Admin read: `GET /api/v1/accounts/4/conversations/{id}` with header `api_access_token: <admin token>` (returns `status`, `meta`, ...). Bot profile calls are refused (401 "not authorized for bots").
- Widget (customer side, native website widget): `POST /api/v1/widget/config` with `{"website_token": "<token>", "locale": "en"}` → `website_channel_config.auth_token` (JWT). Customer messages: `POST /api/v1/widget/messages` with header `X-Auth-Token: <jwt>` and body `{"website_token": "<token>", "message": {"content": "...", "echo_id": "...", "referer_url": "..."}}` → 200.
- Private handoff note and human-reply shapes: **to be captured in Phase 1 probes** (not yet verified; do not implement from memory).
- Token and secret handling: the admin and bot API tokens and the bot webhook secret are server-side only (env / secrets file). The website token is public by design but is still served by the platform, not hard-coded in the browser bundle.
- Note: new conversations in this inbox were observed with status `open` (not `pending`). The platform therefore owns AI eligibility itself (`conversation_control`) and must not rely on Chatwoot status alone. Verify again before the golden flow.

### R2R 3.6.5 (auth on, bcrypt crypto, ollama, simple orchestration, r2r ingestion)
- `POST /v3/users/login` form `username`, `password` → `results.access_token.token` (JWT, 60 min).
- Tenant service identity: `POST /v3/users` (admin token) `{email, password}`. Email domain must not be a reserved TLD (`.local` is rejected; `acme-demo.example.com` works).
- `POST /v3/collections` `{name, description}` → `results.id`.
- Ingest: `POST /v3/documents` multipart: `file`, `metadata` (JSON string), `collection_ids` (JSON array string), `ingestion_mode=fast` → 202 with `results.document_id` (synchronous in fast mode).
- Document status: `GET /v3/documents/{id}` → `results.ingestion_status` (`success` observed).
- Retrieval: `POST /v3/retrieval/search` `{query, search_mode:"basic", search_settings:{limit}}` → `results.chunk_search_results[]` (fields: `score`, `document_id`, `text`).
- RAG: `POST /v3/retrieval/rag` `{query, search_mode, search_settings, rag_generation_config:{model:"ollama/llama3.2:3b", ...}, include_web_search:false}` → answer + citations (observed grounded answer with 2 chunks).
- Isolation (observed): an unrelated user gets `0` search hits, `404` for the tenant document, `403` for the tenant collection. A filtered outsider search returned **HTTP 500** (not a data leak; logged as a finding in `docs/security/r2r-risk-review.md`).
- R2R secrets: `R2R_SECRET_KEY` set (bcrypt provider, refuses to start without it). Admin credentials are bootstrap-only; the platform never uses the admin token.

### Ollama (local only)
- Generation `llama3.2:3b` (100% GPU on RTX 5060 8 GB). Embedding `mxbai-embed-large` (1024 dims). Both verified with local HTTP.

## Platform API routes (MVP)

Public (no auth):
- `GET /actuator/health` (liveness/readiness; no secrets).
- `GET /api/public/widget-config` → `{ "websiteToken": "...", "chatwootBaseUrl": "http://localhost:3100" }` (for the demo page only).
- `POST /api/webhooks/chatwoot/agent-bot` (AgentBot deliveries). Must verify `X-Chatwoot-Signature` over `timestamp.body` with `CHATWOOT_BOT_SECRET`, reject stale timestamps (> 5 min), dedupe, and return `202` quickly after durable insert.

Admin (requires header `X-Admin-Api-Key`; Next.js server proxies it, never exposed to the browser):
- `GET /api/admin/diagnostics` → service health (Chatwoot, R2R, Ollama), tenant summary, counts by state, recent failed webhooks.
- `GET /api/admin/tenant` → `{ tenantKey, displayName, chatwootAccountId, inboxId, teamId, agentBotId, r2rCollectionId }` (no secrets).
- `GET /api/admin/agent` → `{ name, model, embeddingModel, knowledgeScope, handoffPolicy }`.
- `GET /api/admin/knowledge/documents` → list with ingestion status.
- `POST /api/admin/knowledge/documents` (multipart `file`; only `.md`, `.txt`, `.pdf`; max 2 MiB; sha256 stored).
- `POST /api/admin/knowledge/documents/{id}/retry` (only when `FAILED`; idempotent).
- `GET /api/admin/conversations/{chatwootConversationId}` → ownership `state`, `version`, `lastEventAt`, handoff record.
- `POST /api/admin/conversations/{chatwootConversationId}/resume` → explicit, audited resume (`HUMAN_ACTIVE`/`HANDOFF_PENDING` → `AI_ACTIVE`, Chatwoot `pending`). Never automatic.
- `GET /api/admin/webhooks?status=FAILED|QUEUED|PROCESSED` → webhook inbox view.

## Conversation ownership (platform-owned; Chatwoot is the message system of record)

States: `AI_ACTIVE`, `HANDOFF_PENDING`, `HUMAN_ACTIVE`, `RESOLVED`. Transitions and safety rules: see
`docs/architecture/conversation-lifecycle.md` (authoritative) and brief §4.5.

Customer message processing order (one turn per conversation, serialized by a row lock on `conversation_control`):
1. Verify signature and deduplicate (`webhook_inbox` unique key: `event + message.id`).
2. Ignore everything except `message_created` with `message_type = incoming` and `private = false`.
3. If state is not `AI_ACTIVE` → record the event, **no R2R call, no customer reply**.
4. Deterministic human-request check (`human`, `person`, `agent`, `representative`, ...) → handoff before RAG.
5. Retrieve via R2R (tenant collection only). No usable evidence → handoff (no invented answer).
6. Immediately before sending: re-read local state and version; re-check Chatwoot status. Ambiguous → do not send.

## Environment variables (platform-api; names are frozen)

`PLATFORM_DB_URL`, `PLATFORM_DB_USER`, `PLATFORM_DB_PASSWORD`, `TENANT_KEY=acme-demo`,
`CHATWOOT_BASE_URL`, `CHATWOOT_ACCOUNT_ID`, `CHATWOOT_INBOX_ID`, `CHATWOOT_TEAM_ID`, `CHATWOOT_API_TOKEN` (admin token, server-side),
`CHATWOOT_BOT_ACCESS_TOKEN`, `CHATWOOT_BOT_SECRET`, `CHATWOOT_WEBSITE_TOKEN`,
`R2R_BASE_URL`, `R2R_SERVICE_EMAIL`, `R2R_SERVICE_PASSWORD`, `R2R_COLLECTION_ID`,
`PLATFORM_ADMIN_API_KEY` (≥ 32 random chars), `AGENT_EVIDENCE_MIN_SCORE` (default set after evaluation), `AI_MODEL` = `ollama/llama3.2:3b`.

## Response shapes (frozen 24 Nov 2026, requested by frontend-engineer)

All admin responses are JSON objects, never bare arrays, unless noted. Unknown fields are ignored by clients; missing required fields are a server bug.

- `GET /api/admin/diagnostics` → `{ "services": [ { "name": "chatwoot"|"r2r"|"ollama"|"platform-db", "state": "UP"|"DOWN"|"DEGRADED", "detail": string | null } ], "tenant": { "tenantKey": string }, "conversationCounts": { "AI_ACTIVE": n, "HANDOFF_PENDING": n, "HUMAN_ACTIVE": n, "RESOLVED": n }, "failedWebhooks": n }`
- `GET /api/admin/tenant` → `{ "tenantKey": string, "displayName": string, "chatwootAccountId": number, "inboxId": number, "teamId": number, "agentBotId": number, "r2rCollectionId": string }`
- `GET /api/admin/agent` → `{ "name": string, "model": string, "embeddingModel": string, "knowledgeScope": string, "handoffPolicy": string }` (all strings; `handoffPolicy` is a short human sentence, not structured)
- `GET /api/admin/knowledge/documents` → `{ "documents": [ { "id": string, "filename": string, "status": "PENDING"|"INGESTING"|"READY"|"FAILED", "errorCode": string | null, "sha256": string, "sizeBytes": number, "updatedAt": string } ] }`
- `POST /api/admin/knowledge/documents` → `201 { "document": <same item as above> }`
- `POST /api/admin/knowledge/documents/{id}/retry` → `202 { "document": <same item as above> }`
- Errors (all routes): `{ "error": { "code": string, "message": string } }` with the HTTP status.

## Frozen additions (backend report, 24 Nov 2026)

- Env: `CHATWOOT_AGENT_BOT_ID` (number, the AgentBot id) is added to the platform env list. `GET /api/admin/tenant.agentBotId` reads it.
- `GET /api/admin/diagnostics` → `services[]` also includes `"ollama"`. Ollama is probed at `OLLAMA_BASE_URL/api/tags`; `state` is `UP` on HTTP 200, else `DOWN`. `DEGRADED` is reserved for partial failure and is not used for Ollama.
- `POST /api/admin/knowledge/documents`: an identical file (same sha256 already stored for the tenant) returns `200 { "document": <existing item>, "duplicate": true }`. A new file returns `201`.
- `GET /api/admin/webhooks?status=...` → `{ "webhooks": [ { "id", "eventKey", "eventType", "status", "attempts", "lastError": string | null, "receivedAt" } ] }`.
- `GET /api/admin/conversations/{id}` → plain object: `{ "chatwootConversationId": number, "state": "AI_ACTIVE"|"HANDOFF_PENDING"|"HUMAN_ACTIVE"|"RESOLVED", "version": number, "lastEventAt": string | null, "handoff": { "status": string, "reason": string, "createdAt": string } | null }`.
- `POST /api/admin/conversations/{id}/resume` → plain object: `{ "chatwootConversationId": number, "state": "AI_ACTIVE", "version": number }`.
- `/api/health` is NOT a platform route. The admin web serves its own `/api/health`; the platform readiness route is `/actuator/health/readiness`.
