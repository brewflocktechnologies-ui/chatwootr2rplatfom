# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A local, single-tenant (`acme-demo`) AI-first customer-support MVP. The AI answers from a tenant knowledge base and hands off to a human in Chatwoot's inbox. The build contract is `bootstrap.md`; the frozen API and env contract is `docs/architecture/api-contract.md`; the ownership state machine is `docs/architecture/conversation-lifecycle.md`. Read those before changing behaviour.

## Non-negotiables

- One tenant only. The server supplies the tenant. Never trust a tenant or collection ID from the browser.
- Everything runs locally. No hosted LLM, embedding, vector DB or SaaS telemetry at runtime.
- Chatwoot and R2R are separate upstream products, integrated only through their documented REST APIs and webhooks. Never touch their databases. No forks.
- Modular monolith on PostgreSQL. No broker (no Kafka, NATS, RabbitMQ or Redis for platform queues).
- Never stop, prune or remove Docker containers or volumes that this project does not own. Our Compose projects are `saas-chatwoot`, `saas-r2r`, `saas-ollama` and `saas-platform`. `E:\SAAS\CHATWOOT` is the user's own stack; don't start it, and don't touch its `chatwoot_*` volumes.
- Secrets live only in git-ignored files: `infra/chatwoot/.env.secrets`, `infra/r2r/.env`, `infra/r2r/config/r2r.local.toml`, `infra/platform/.env`. Never print, commit, or put them in `NEXT_PUBLIC_*`, screenshots, reports or logs.
- Customer AI answers come only from R2R tenant retrieval. No evidence, or any R2R/Chatwoot failure, means handoff, never an invented answer.
- Don't mark a test as passed unless it was executed. Mocks are not E2E evidence.

## Commands

Host environment: Windows, PowerShell, Docker Desktop. Host Node is 24 and the host pnpm 12 shim is broken, so **Node and pnpm run inside the pinned container**:

```powershell
docker run --rm -v E:\SAAS\PLATFORM:/workspace -w /workspace node:26.13.1-bookworm-slim sh -c "npm i -g pnpm@12.23.0 && pnpm install --frozen-lockfile"
```

Admin web (Nx targets; run them in the same container as above, with `pnpm nx run admin-web:<target>`):

- `lint`, `typecheck`, `test`, `build`, `verify`
- Single test file (from `apps/admin-web` inside the container): `pnpm exec vitest run --config vitest.config.mts src/lib/chatwoot.test.ts`
- Delete `apps/admin-web/.next` before building the Docker image. Windows bind-mount symlinks break the image build.

Platform API (Spring Boot 4.2.0, Java 25; the JDK is `C:\Program Files\Java\jdk-25.0.3`, not the default `java` 27):

```powershell
cd apps\platform-api
.\mvnw.cmd -B verify                              # all tests, incl. Testcontainers PostgreSQL and Modulith verification
.\mvnw.cmd -B -Dtest=ConversationSnapshotTest test   # single test class
```

Golden-flow E2E (Playwright 1.65.1; needs the full stack running): `pnpm nx run e2e:golden-flow`, which runs `apps/e2e/scripts/run-golden-flow.mjs`.

Stack lifecycle (PowerShell, from repo root):

- `scripts\doctor.ps1`: read-only machine check
- `scripts\up.ps1`, `scripts\down.ps1` (keeps volumes), `scripts\down.ps1 -IncludeUpstream`
- `scripts\status.ps1`: health table
- `scripts\logs.ps1 -Project platform -Service platform-api`
- `scripts\bootstrap-r2r.ps1`, `scripts\bootstrap-chatwoot.ps1` (verify-only)
- `scripts\reset.ps1`: erases platform demo data only, after typed confirmation

Double-click `start-platform.bat` and `stop-platform.bat` for the same up and down actions. Operator steps are in `LOCALRUN.md`.

## Architecture (big picture)

**Inbound customer message path.** Chatwoot's AgentBot posts `message_created` to `POST /api/webhooks/chatwoot/agent-bot`. The endpoint verifies `X-Chatwoot-Signature` (HMAC-SHA256 over `timestamp.raw body`, keyed with the bot secret), rejects stale timestamps, and inserts into `webhook_inbox`. The unique dedupe key is event plus message ID, so redelivery is a no-op. A worker claims rows under a per-conversation lock. Bot-authored and human-authored messages are recorded but never start an AI turn.

**One AI turn.** The turn checks platform ownership first. If the state is not `AI_ACTIVE`, it stops with no R2R call and no reply. Then it checks the human-request phrases, which hand off before any retrieval. It then calls R2R RAG for the tenant collection only and applies the evidence gate. Immediately before sending, it re-reads ownership and version and the Chatwoot conversation state. A late result after an ownership change is discarded. The send uses an idempotency record, so a timeout retry never sends twice.

**Ownership.** `conversation_control` in platform PostgreSQL is the source of truth for AI eligibility. Chatwoot holds messages, assignment and status. The inbox auto-assigns our AgentBot and sets conversations to `pending` (or `open`), so eligibility means "status is pending or open, and assignee is unassigned or our bot". A human assignment or another bot makes the conversation ineligible.

**Handoff.** The platform moves to `HANDOFF_PENDING`, posts one private note (`private: true`), toggles the Chatwoot conversation to `open`, then moves to `HUMAN_ACTIVE`. Resuming the AI is an explicit audited admin action, never automatic.

**Knowledge.** Uploads go to R2R under the tenant service identity, never the R2R admin token. A 409 duplicate is adopted by matching sha256, title or source. The evidence gate is `AGENT_EVIDENCE_MIN_SCORE` (currently 0.45 in env, measured on a few queries only).

**Platform modules.** Spring Modulith modules under `com.acme.support`: `tenant`, `chatwoot`, `knowledge`, `conversation`, `handoff`, `agent`, `webhook`, `admin`, `shared`. `ModularityTest` enforces their boundaries.

**Admin and demo web.** Next.js 16. Route handlers under `/api/admin/*` proxy to the platform with `X-Admin-Api-Key` added server-side, so the browser never sees the key. `/api/public/widget-config` returns the website token and the public Chatwoot URL. `/demo` loads Chatwoot's native widget SDK from that URL.

**Local endpoints.** Browser-facing: Chatwoot `:3100`, admin web `:4300`, platform `:8080`. Containers reach host services via `host.docker.internal` (Chatwoot `:3100`, R2R `:7272`, Ollama `:11434`). Every port is bound to `127.0.0.1`.

## Environment gotchas

- The Chatwoot webhook needs `SAFE_FETCH_ALLOW_PRIVATE_NETWORK=true` in the local profile. Chatwoot rejects private-host webhook URLs without it.
- R2R's `R2R_PROJECT_NAME` must be a SQL identifier. Hyphens crash startup.
- R2R's Ollama endpoint comes from `OLLAMA_API_BASE`, not the config file.
- Chatwoot's health check must use `127.0.0.1`, not `localhost`, because BusyBox `wget` resolves to IPv6 first.
- Chatwoot's first-run onboarding is gated. If `/app/login` sends you to "Finish Setup", clear `CHATWOOT_INSTALLATION_ONBOARDING` in Redis on the local instance.

## Conventions

- `bootstrap.md` is the build contract. Any change to a route, env name or state transition goes into `docs/architecture/api-contract.md` or `conversation-lifecycle.md` first.
- Operator scripts are PowerShell-first. Anything destructive must be named that way and require typed confirmation.
- Logs go to stdout with correlation IDs (webhook delivery ID, conversation ID, R2R request ID). Never log message bodies, tokens or secrets.
- Do not commit unless asked. Nothing in this repo is in git history yet.
