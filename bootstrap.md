# Bootstrap: Local AI-First, Human-First Support MVP

**Document type:** Executable build brief for Claude Code  
**Research baseline:** 24 November 2026  
**Starting point:** Empty project folder; Docker Desktop is already running on the developer machine  
**Target:** One local demo tenant, one private knowledge base, one AI Agent, one Chatwoot human-support inbox, and a fully demonstrated AI-to-human handoff  
**Execution mode:** Research → verify → design → build → run → test → demonstrate → report

---

## 0. Instructions to the lead Claude Code agent

You are the **Lead Engineer, Architect, Integrator and Delivery Owner** for this MVP. Your task is to create a working, reproducible, end-to-end local platform from this empty folder. Do not stop after writing a plan or scaffolding a repository. Implement it, run it, diagnose failures, fix them, and prove the golden flow works.

Start by reading this entire file. Then:

1. Inspect the developer machine and Docker Desktop without stopping, deleting, pruning, or modifying existing user containers or volumes.
2. Use specialist Claude Code subagents for independent research, architecture/security review, backend, frontend, infrastructure and independent QA tasks. Create project-level subagent definitions under `.claude/agents/` and a concise `CLAUDE.md` with the project guardrails.
3. Research the actual versions and APIs installed or selected. Prefer official documentation and source code at the exact pinned tags. Do not invent endpoints, payload fields, environment variables, Compose service names, or configuration options.
4. Write the research report and architecture/ADRs before substantial implementation. Resolve the critical integration contracts with executable probes against the running local Chatwoot and R2R instances.
5. Build the smallest complete product that demonstrates the golden flow in Section 9.
6. Run unit, integration, architecture, security, frontend, and browser E2E tests. Use real local Chatwoot, R2R and local model inference for the final golden flow. Do not count mocks as E2E evidence.
7. Leave the repository in a state where a developer can follow `README.md` and start/stop/verify the stack using documented commands.
8. Produce a final evidence-based acceptance report. Every criterion must be marked `PASS`, `FAIL`, or `BLOCKED`, with command/output, screenshot, trace or API evidence. Never claim a test passed if it was not executed.

### Working style

- Do not ask the user questions for decisions that can be resolved through research and a conservative MVP default. Ask only when a required secret, account action, destructive action, or genuine product decision cannot be safely inferred.
- The user has explicitly chosen JDK 25, Spring Boot 4+, Spring Modulith, Nx, Docker Desktop and a one-tenant MVP. Preserve these decisions.
- No cloud deployment, Kubernetes, cloud databases, hosted inference APIs, SaaS observability vendors or paid hosted dependencies. The application must run locally. Initial downloads of Docker images, npm packages and local model weights may require internet access; runtime inference and application services must stay local.
- Keep Chatwoot and R2R as independently replaceable upstream products. Integrate through documented APIs. Do not fork them or write directly to their databases for MVP functionality.
- Do not build a replacement Chatwoot inbox or customer-chat widget. Use Chatwoot's native website widget and native agent inbox.
- Keep the custom platform as a modular monolith. Do not create microservices for individual modules.
- Do not add Kafka, NATS, RabbitMQ, Redis or another broker for the custom platform. Chatwoot may require its own Redis; that is an upstream dependency, not a platform messaging architecture.
- Keep implementation small. The goal is a product evaluation MVP, not a production SaaS platform.
- Prefer PowerShell scripts for Windows developer workflows. Provide equivalent `pnpm`/Docker commands where practical.
- Avoid broad dependency upgrades after the stack is pinned. Update deliberately, test, and record the change.

### Definition of done

The MVP is done only when a customer can open the local demo page, send a question, receive an answer grounded in the demo tenant's own R2R knowledge base with source attribution, explicitly request a human, see the conversation enter Chatwoot's human inbox with a useful private handoff summary, receive a real human agent reply in the customer widget, and observe that the AI does not send further automatic replies while the human owns the conversation.

---

## 1. Product scope and explicit assumptions

### 1.1 Product being evaluated

Build a local proof of the product concept:

> **AI-agent-first, human-first customer support:** the AI handles answerable questions using the tenant's own approved knowledge; a human can take over at any time; the AI stops speaking when a human owns the conversation; the human receives the context collected by the AI.

### 1.2 One-tenant MVP

Use exactly one seeded demo tenant:

- Tenant display name: `Acme Demo`.
- Tenant key: `acme-demo`.
- One Chatwoot account/workspace.
- One Chatwoot website inbox.
- One Chatwoot AgentBot connected to that inbox.
- One Chatwoot human-support team and at least one human agent.
- One R2R user/identity and one tenant-owned knowledge collection (or the closest supported, verified R2R isolation construct).
- One AI agent configuration: `Acme Demo Support Agent`.
- One customer demo website page embedding the native Chatwoot widget.

This is **one tenant at runtime**, not a multi-tenant SaaS release. However, include `tenant_id` in platform-owned domain records and enforce tenant scoping in service interfaces from the beginning. Never accept a tenant ID supplied by an untrusted browser and use it as the sole authorization check. Seed one fixed tenant context on the server.

### 1.3 In scope

1. Local Dockerized custom platform API, admin UI and platform database.
2. Local Chatwoot and R2R integration, preferably reusing the user's already-running instances after checking their exact versions/configuration. Supply a reproducible managed-local option for clean machines if it can be added safely.
3. Local model inference through Ollama (or an equally local model runtime supported by the exact pinned R2R release). No hosted LLM, embedding or reranking service.
4. Upload/list/ingestion-status of a small set of tenant documents.
5. Tenant-scoped document ingestion and retrieval through R2R API.
6. Agent response generation based on R2R retrieval/RAG, with citations/source names where the upstream response supplies them.
7. Human-request and safety/low-evidence handoff paths.
8. Human handoff to Chatwoot's native inbox, including a private context summary.
9. Human reply through Chatwoot and delivery to the customer through the native widget.
10. Durable webhook deduplication, basic retries, conversation ownership state, logs and diagnostics.
11. Playwright E2E proving the real local lifecycle.

### 1.4 Out of scope

Do not build any of the following for this MVP:

- Cloud deployment, Kubernetes, Helm, Terraform, multi-region, autoscaling or production HA.
- Self-service tenant signup, tenant billing, subscription plans or usage billing.
- Multiple tenant UI or tenant provisioning workflows.
- Custom agent inbox, custom chat widget, omnichannel connector implementations or WhatsApp integration.
- Autonomous refunds, payments, order changes, account deletion or other consequential write actions.
- Multi-agent business workflows, agent marketplace, fine-tuning, training pipelines or complex planning agents.
- External web search, internet research by the customer-facing agent or external actions.
- Kafka, NATS, RabbitMQ, Redis for platform queues, a standalone vector DB or another custom microservice.
- A new authentication provider or production-grade user/role-management product. Admin functions must nevertheless be protected for local evaluation.

### 1.5 Fictional demo knowledge

Create and commit a clearly labelled fictional file such as `docs/demo-tenant/acme-support-policy.md`. Use deterministic facts that make testing possible:

- Standard orders are dispatched within 1–2 business days.
- Standard delivery takes 3–5 business days after dispatch.
- A return request may be submitted within 14 calendar days after delivery for unused items in original packaging.
- Approved refunds are processed within 5–7 business days after the returned item is received and approved.
- Damaged or missing-item claims require a human support agent to review.
- Customers can request a human agent at any time.

The AI must not invent policy facts that are absent from this file. This content is fictional evaluation data, not a real business policy.

---

## 2. Research baseline and version policy

The following versions are the research baseline as of **24 November 2026**. Re-check them at execution time using official release pages and the developer machine. Record exact versions, image tags, commit SHAs and image digests in `docs/research/version-matrix.md`.

| Area | MVP baseline | Rule |
|---|---|---|
| Chatwoot | `v4.20.1` | User-selected tag. Verify the running container image/tag/commit. Do not silently upgrade. |
| R2R | `v3.6.5` is the latest tagged release found in this research | Its latest tag is dated 6 June 2025, so this is a maintenance/security review gate, not an automatic approval. Re-check releases and compare reported security issues to the exact image/commit. |
| JDK | Java 25 | Required. Check `java -version` and Docker build/runtime base images. |
| Spring Boot | `4.2.0` | Current stable version found in this research. Official system requirements state Java 17–27 compatibility, so Java 25 is supported. |
| Spring Modulith | `2.2.0` | GA released 19 November 2026; release announcement says it builds against Spring Boot 4.2 / Spring Framework 7.1. Use its BOM. |
| Nx | `24.0.5` | Current Nx major at research time. Keep all `@nx/*` package versions aligned with Nx. Do not enable Nx Cloud. |
| Node.js | `26.x` Active LTS | Node 26 entered LTS in October 2026. Use the current patched 26.x release, not an Alpha/Current line. |
| pnpm | `12.x` | Use Corepack or the official installation method, pin exact package-manager version in `package.json` and `pnpm-lock.yaml`. |
| Web UI | Latest stable Next.js 16.x supported by Nx 24 | Verify Nx compatibility before selecting; pin exact version. Use TypeScript strict mode. |
| Browser E2E | Latest Playwright Test compatible with Node 26 | Pin version and browser binaries; save traces/screenshots/reports. |
| Local LLM runtime | Ollama | Run locally in Docker or use the user's existing local Ollama endpoint. Do not call hosted inference APIs. |
| Database | PostgreSQL, a stable version supported by the selected Flyway/Spring Boot release | Platform DB is separate from Chatwoot and R2R databases. Never access upstream tables directly. |

### Required version verification

Before implementation:

- Confirm Chatwoot release: <https://github.com/chatwoot/chatwoot/releases/tag/v4.20.1>.
- Confirm R2R tags and release dates: <https://github.com/SciPhi-AI/R2R/releases>.
- Confirm Spring Boot requirements and current stable: <https://docs.spring.io/spring-boot/system-requirements.html>.
- Confirm Spring Modulith 2.2.0 and its Boot 4.2 build compatibility: <https://spring.io/blog/2026/11/19/spring-modulith-2-2-ga-2-1-3-and-2-0-10-released>.
- Confirm Nx 24 and package compatibility: <https://nx.dev/changelog> and <https://nx.dev/docs/technologies/react/next/introduction>.
- Confirm Node active LTS and pnpm compatibility: <https://nodejs.org/en/about/previous-releases> and <https://pnpm.io/installation>.
- Confirm supported Playwright Node versions: <https://playwright.dev/docs/intro>.

If the latest stable patch release differs from the baseline, use a newer patch only after proving compatibility and documenting the reason. Do not change the selected major versions without a written ADR and explicit user approval.

### R2R security/maintenance gate — mandatory

The R2R release page still showed `v3.6.5` as latest tagged release during this research, while public issues include reports concerning authentication/JWT, cross-user access and SQL-injection behavior (for example issues #2290, #2292, #2297, #2309 and #2311). These are **public reports, not proof that every issue affects the pinned release or that every report is confirmed**. The implementation agent must:

1. Check each report's current status, affected commits/tags, reproducer and any fix/mitigation.
2. Inspect the exact R2R source/image used locally, its configuration defaults and security policy.
3. Set a unique strong `R2R_SECRET_KEY`; fail startup if the selected release permits an unsafe default/empty key.
4. Enable R2R authentication and use a least-privilege service identity where supported.
5. Verify collection/document authorization with negative tests; never trust only a caller-supplied metadata filter.
6. Bind R2R to loopback/local development networking only. Do not expose it to the LAN or internet.
7. Do not load personal, customer, production or confidential business documents into the MVP.
8. Record any unresolved issue in `docs/security/r2r-risk-review.md`. If an issue can compromise local tenant isolation or authentication and has no mitigation, stop before loading even demo-sensitive data and report the blocker.

No source-level changes to Chatwoot or R2R are allowed in the MVP unless a reproducible blocker makes them unavoidable. Any proposed fork/patch needs an ADR and a small, isolated patch with upstream reference.

---

## 3. Target architecture and non-negotiable boundaries

### 3.1 Logical architecture

```text
                         LOCAL DEVELOPER MACHINE
┌─────────────────────────────────────────────────────────────────────────────┐
│ Browser                                                                     │
│  ├── Customer demo page (Next.js) ── Chatwoot native website widget         │
│  └── Admin / knowledge console (Next.js)                                    │
│                                      │                                      │
│                                      ▼                                      │
│                         Platform API (Spring Boot 4)                         │
│                         Modular monolith + Modulith                          │
│                           │                │                                 │
│                     Chatwoot API       R2R REST API                          │
│                           │                │                                 │
│                           ▼                ▼                                 │
│                    Chatwoot v4.20.1     R2R v3.x                             │
│                    ├── human inbox      ├── tenant knowledge collection      │
│                    ├── agent/team       ├── ingestion                         │
│                    ├── conversation     ├── hybrid retrieval                  │
│                    └── customer widget  └── RAG + source references            │
│                                             │                                 │
│                                             ▼                                 │
│                                      Local Ollama                             │
│                                                                             │
│ Platform PostgreSQL stores only platform-owned state, mappings, webhook     │
│ inbox/idempotency, agent ownership and audit records.                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Ownership boundaries

| Concern | System of record |
|---|---|
| Customer contact, Chatwoot conversation, messages, inbox, agent, team, assignment and widget | Chatwoot |
| Tenant knowledge documents, chunks, retrieval indexes, RAG retrieval response and source metadata | R2R |
| Demo tenant mapping and configuration | Platform PostgreSQL |
| Webhook event deduplication and processing status | Platform PostgreSQL |
| AI conversation ownership, execution state, handoff audit and summaries | Platform PostgreSQL; customer-visible messages still go through Chatwoot |
| UI presentation | Next.js |
| AI orchestration, policy and handoff decision | Spring Boot platform API |
| Model inference | Local Ollama through R2R's supported provider configuration |

Never read or write Chatwoot or R2R internal tables. Use their documented REST APIs and webhooks only.

### 3.3 Local services

The intended logical services are:

- `admin-web`: Next.js admin and customer demo pages.
- `platform-api`: Java 25 / Spring Boot 4.2 / Spring Modulith 2.2 modular monolith.
- `platform-db`: PostgreSQL for platform-owned records only.
- `chatwoot`: existing local Chatwoot or pinned managed-local deployment.
- `r2r`: existing local R2R or pinned managed-local deployment.
- `ollama`: local model runtime.
- Existing Chatwoot/R2R dependencies remain as required by their upstream Compose definitions (for example their own PostgreSQL/Redis/services).

Do not create one shared database for Chatwoot, R2R and the platform. Use separate credentials, volumes and database ownership.

### 3.4 Reuse the user's already-running upstream services safely

At startup, inspect Docker Desktop:

- `docker context show`
- `docker version`
- `docker compose version`
- `docker ps --format ...`
- `docker compose ls`
- local port usage for expected Chatwoot/R2R/Ollama/API/UI ports.

Identify the user's running Chatwoot/R2R containers, image tags, published ports, health state, Compose project, configuration and R2R model provider. Do **not** call `docker compose down`, `docker rm`, `docker volume rm`, `docker system prune`, or replace existing containers/volumes.

Preferred development mode: connect the custom platform to the already-running local Chatwoot and R2R instances using configurable base URLs. On Docker Desktop, `host.docker.internal` may be used from app containers when the upstream services publish ports on the host. Verify this connection with actual HTTP probes; do not assume it works.

If the existing R2R is not configured for local model inference, do not silently change its persistent configuration. Create an isolated managed-local R2R/Ollama profile on alternate ports, or provide a safe documented configuration procedure with a backup. Likewise, if Chatwoot's version is not the selected tag, report the difference and either support the existing version after API verification or offer a separate pinned profile. Never overwrite the user's existing data.

The repository must include a reproducible clean-machine path. Keep upstream Compose files/configuration in clearly separated folders, pinned to tags/commits, and start/stop them with scripts that use separate Compose project names and persistent volumes. A single documented command such as `pnpm local:up` should start the MVP dependencies and platform, but the scripts must detect and respect already-running upstream services.

---

## 4. Technical implementation requirements

### 4.1 Nx workspace

Create an Nx integrated monorepo from this empty folder.

Target layout (adjust only with a short ADR):

```text
.
├── apps/
│   ├── admin-web/                  # Next.js: admin console + customer demo page
│   ├── platform-api/               # Spring Boot 4 / Maven wrapper
│   └── e2e/                        # Playwright browser E2E
├── libs/
│   └── web-api-client/             # Generated/typed API client if useful; avoid premature libraries
├── docs/
│   ├── architecture/
│   ├── research/
│   ├── security/
│   ├── testing/
│   └── adr/
├── infra/
│   ├── platform/                   # Custom platform Docker Compose and Dockerfiles
│   ├── chatwoot/                   # Pinned, separated upstream local configuration if needed
│   ├── r2r/                        # Pinned, separated upstream local configuration if needed
│   └── ollama/                     # Local inference configuration/model bootstrap
├── scripts/                        # PowerShell-first local scripts
├── docs/demo-tenant/               # Fictional seed knowledge
├── .claude/agents/                 # Specialist Claude Code subagents
├── .env.example
├── .gitignore
├── CLAUDE.md
├── README.md
├── bootstrap.md
├── nx.json
├── package.json
└── pnpm-workspace.yaml
```

Requirements:

- Use Nx 24.x and keep Nx plugin versions aligned.
- Use Node 26 Active LTS, pnpm 12 and a committed lockfile.
- Use the official Nx generator/plugin for Next.js after verifying version compatibility. Keep frontend as a single Next.js app with routes such as `/admin`, `/admin/knowledge`, `/admin/agent`, and `/demo`.
- Use strict TypeScript. Keep runtime configuration server-side where possible; never put Chatwoot API tokens, R2R tokens, admin secrets or webhook secrets into `NEXT_PUBLIC_*` variables.
- Define explicit Nx targets for `lint`, `typecheck`, `test`, `build`, `e2e`, `verify`, `local:up`, `local:down`, `local:status`, `local:logs`, `local:reset` (confirmation required) and `local:golden-flow`.
- Enable local Nx task caching where safe. Do not configure Nx Cloud or remote caching.
- Do not add `@nx/maven` merely for appearance: its current Nx documentation marks it experimental. Use Nx `run-commands` targets to invoke the committed Maven Wrapper (`./mvnw` / `mvnw.cmd`) and document this decision. Re-evaluate only if the plugin becomes stable and materially helps.
- Ensure `nx show projects` and `nx graph` work. Java project must be represented in Nx project graph via project configuration and targets even if Maven plugin is not used.

### 4.2 Backend: JDK 25 + Spring Boot 4 + Spring Modulith

Use:

- Java 25 toolchain and Docker build/runtime images based on a JDK/JRE 25 distribution.
- Spring Boot 4.2.0 parent/BOM.
- Spring Modulith 2.2.0 BOM and the required Modulith starters.
- Maven Wrapper committed to the repo so a host Maven installation is not required.
- PostgreSQL JDBC/JPA as needed; choose the simplest data access approach that fits the model.
- Flyway for platform schema migrations. Migrations must be explicit and versioned. Do not use Hibernate `ddl-auto=create`, `update` or startup schema mutation. Prefer `validate` where ORM is used. Provide an explicit migration task/command that runs before startup.
- Spring Security for local admin/API protection, with secrets supplied via ignored `.env` files/environment variables.
- Spring Boot Actuator health/readiness endpoints with no secrets or internal credentials exposed.
- Spring `RestClient` or another Spring Boot 4.2-supported HTTP client for Chatwoot and R2R, with explicit connect/read timeouts and error mapping. Do not base new integration code on deprecated `RestTemplate` APIs.
- JUnit 5 / Spring Boot 4-compatible tests, Testcontainers for platform PostgreSQL integration tests where practical, and Spring Modulith module verification tests.

Suggested Modulith modules (keep them cohesive):

```text
com.acme.support
  ├── tenant              # fixed demo tenant context and integration mappings
  ├── chatwoot            # Chatwoot API client, webhook DTOs, adapter
  ├── knowledge           # R2R client adapter, document ingestion, collection scope
  ├── agent               # turn orchestration, prompt, evidence and escalation policy
  ├── conversation        # platform conversation ownership and lifecycle
  ├── handoff             # handoff summary, transition and audit
  ├── webhook             # validation, deduplication, durable event processing
  ├── admin               # protected admin/diagnostic APIs
  └── shared              # only genuinely shared primitives; avoid a dumping ground
```

Use Spring Modulith's application-module annotations/verification and enforce allowed module dependencies. Do not split these into deployable services.

#### Platform data model minimum

Use Flyway migrations to create a small platform schema. Exact columns are an implementation decision, but it must cover:

- `tenant` — seeded one-row tenant (`acme-demo`).
- `tenant_integrations` — Chatwoot account/inbox/AgentBot identifiers and R2R user/collection identifiers; secrets remain in environment/secret configuration, not plain text in DB.
- `knowledge_source` — uploaded source file metadata, R2R document ID, tenant ID, ingestion status, checksum and timestamps.
- `webhook_inbox` — provider event key, event type, conversation/message IDs, received payload (redacted or minimum necessary), status, attempts, timestamps and error details.
- `conversation_control` — tenant ID, Chatwoot conversation ID, owner/state, version, last processed message ID, current processing lease/lock metadata and timestamps.
- `handoff_record` — reason, summary, citations/source names, status, timestamps and Chatwoot note/message ID.
- `agent_execution` or equivalent — correlation ID, conversation/message IDs, state, timing, failure reason and model/retrieval metadata (no chain-of-thought storage).

Add unique constraints and indexes for webhook deduplication and conversation ownership. Store only the minimum conversation data required. Do not duplicate full Chatwoot transcripts or place all customer messages into R2R.

#### Reliable event processing

Use a simple PostgreSQL-backed inbox/worker pattern for this single-node MVP; no broker:

1. Validate incoming webhook request.
2. Extract provider event and message identifiers from the verified payload.
3. Create a deduplicated `webhook_inbox` record transactionally. If already processed/queued, return a success acknowledgement without starting a second execution.
4. A bounded background worker claims queued work using a safe row-lock/lease strategy; use PostgreSQL features only after integration testing.
5. Filter strictly for genuine incoming customer messages. Ignore bot-generated and human-agent outgoing messages for AI answer generation.
6. Verify tenant/inbox mapping and conversation ownership before retrieval/generation.
7. Process one conversation turn at a time. Use row-level lock/optimistic version/lease so two duplicate or concurrent webhook deliveries cannot create competing AI replies.
8. Immediately before sending a customer-facing AI message, re-check that the platform still owns the conversation and confirm the Chatwoot conversation is still in the AI-eligible status. If uncertain, do not send.
9. Retry transient upstream/network errors with bounded exponential backoff; persist terminal failure and make it visible in diagnostics. Do not retry validation/auth failures indefinitely.
10. Ensure the same incoming message cannot create multiple customer replies after a retry or restart.

Do not store or expose model chain-of-thought. Record concise reason codes, retrieval source IDs, timings and outcome only.

### 4.3 Chatwoot integration

Use Chatwoot's documented AgentBot integration, not an improvised generic webhook if AgentBot is available.

Verified product behavior from Chatwoot's AgentBot guide:

- An AgentBot connects to an inbox.
- New conversations assigned to the AgentBot enter `pending` so the bot can triage them.
- The bot receives events including `widget_triggered`, `message_created` and `message_updated`.
- The bot posts replies through Chatwoot's Create Message API.
- Setting the conversation status to `open` hands the conversation to a human agent.
- A human can move it back to `pending` to return it to the bot queue.

The guide also notes that `conversation_status_changed` is not supported when using AgentBot APIs. Do not make human-takeover detection depend exclusively on this event.

Required implementation and probes:

1. Inspect Chatwoot API docs and the exact v4.20.1 source for AgentBot registration, inbox association, create-message, update-conversation, private-note behavior, webhook payloads, sender/message types and authentication.
2. Capture sanitized real webhook payloads for customer messages, bot replies and human replies from the local instance. Keep them as fixture examples in `apps/platform-api/src/test/resources` or `docs/research/fixtures`; remove contact PII, tokens and secrets.
3. Verify the exact request path, body and response of the Create Message and Update Conversation APIs against the running instance. Do not code from memory.
4. Configure an AgentBot URL that is reachable from the Chatwoot container. Verify container-to-container/host connectivity with a real callback.
5. Verify webhook authenticity. Use Chatwoot's documented signature mechanism if it applies to AgentBot events. If AgentBot events do not provide a documented signature, use a high-entropy local callback secret and the most restrictive feasible Docker network/ingress path. Document this as local-dev-only; do not pretend an unsigned callback is cryptographically authenticated.
6. Use a Chatwoot API token only server-side. Do not expose it to Next.js browser code.
7. Detect the sender and message direction from verified payload fields. Only customer incoming messages can trigger the agent.
8. For bot replies use Chatwoot API identity and message metadata correctly; prevent the bot from recursively processing its own outgoing reply.
9. For handoff, transition the platform state first to `HANDOFF_PENDING`, stop any new AI turn, then add a private note/context summary and update the Chatwoot conversation to `open`. Mark `HUMAN_ACTIVE` only after Chatwoot confirms the transfer; make the operation retryable/idempotent.
10. Before every AI send, use both local ownership and a fresh Chatwoot conversation-state check as a final safety barrier. An open conversation or human reply means the AI must not send an automatic customer message.
11. When a human reply arrives, recognize it as an outgoing agent message, retain human ownership and never echo/generate another AI answer.
12. Add an explicit protected `Resume AI` action for the MVP only if it can safely set the Chatwoot conversation back to `pending` and reset local ownership. Never auto-resume after a human response.

Create a setup checklist for one Chatwoot account, website inbox, AgentBot, human team and human agent. If AgentBot registration is not supported through the available public API, document the manual one-time UI setup and provide a verification command/script. Do not modify Chatwoot source to automate setup.

### 4.4 R2R and local inference integration

Use R2R as the knowledge engine through its REST API. Never connect to its PostgreSQL directly.

Research and verify the selected version's:

- Authentication flow/service token approach.
- User/collection creation and ownership semantics.
- Document ingestion API and supported file formats.
- Ingestion status/list API.
- Retrieval search API and response fields for score, text, document ID and metadata.
- RAG API and source/citation format.
- Health endpoint and OpenAPI document.
- Local Ollama configuration and model/embedding requirements.

Use the exact API version and payload schema discovered from the selected release's OpenAPI/API docs and live local endpoint. Build an adapter in the backend so R2R can be replaced later. Do not hard-code unverified request examples from an older README.

#### Local model requirements

- Use Ollama locally, either as a managed Docker container or an existing local Ollama service.
- Configure R2R's LLM, embedding and any reranking/summary model that is actually required to use local endpoints/models. Use the pinned R2R release's Ollama example configuration as a starting reference; verify and adjust the hostname for the Docker network (`ollama` service name versus `host.docker.internal`).
- Pick a modest model based on the developer machine's available RAM/CPU/GPU. Record exact model names and quantization. Avoid pulling a huge model without checking resources and obtaining confirmation if storage/download cost is material.
- Pull the selected generation and embedding models once; add an idempotent bootstrap script and a health check proving both can be invoked.
- Verify that document ingestion, embedding, retrieval and generation all complete without an external model API key.
- Disable R2R internet/web-search tools for the customer-support MVP. Customer answers must rely on the tenant's own knowledge base only.

#### Tenant knowledge isolation

- Provision one R2R identity/scope and one collection for `acme-demo` using the exact supported authorization model.
- All ingestion and retrieval requests must be scoped server-side to that collection/scope. Do not accept collection IDs from the browser.
- Add a negative test proving a document outside the tenant's collection cannot be retrieved. If only metadata filtering is available, do not call it a security boundary; document the limitation and apply a separate authorization control.
- Upload only the fictional demo policy at first. Admin UI may accept a small set of local files (at minimum PDF, TXT and Markdown if the pinned R2R supports them); validate size and MIME/extension, calculate a checksum and show ingestion status.
- Store platform-side document metadata and R2R document IDs, not document chunk data.

#### RAG answer policy

The first version should use a predictable retrieve-and-answer sequence rather than R2R's full autonomous research/agent API:

1. Receive customer message.
2. Check explicit human request and handoff policy before RAG.
3. Ask R2R for retrieval/RAG only within the demo tenant's knowledge scope.
4. Require usable retrieved evidence and source metadata.
5. Generate a concise, user-facing answer grounded only in retrieved evidence.
6. Include one or more human-readable source references from actual R2R response metadata when available.
7. If evidence is missing, conflicting, failed, or below a threshold justified by evaluation, do not fabricate an answer. Ask one clarifying question or hand off; for the golden-flow unsupported query, hand off.

Do not present retrieval scores as calibrated confidence unless validated. Record the retrieval threshold and why it was selected in the RAG evaluation notes.

### 4.5 Conversation ownership and handoff state machine

Implement a persistent state machine. Suggested states:

```text
AI_ACTIVE
  ├── explicit human request / unsafe action / insufficient evidence ──> HANDOFF_PENDING
  ├── valid supported answer ──> AI_ACTIVE
  └── resolved/closed via policy ──> RESOLVED

HANDOFF_PENDING
  ├── Chatwoot transfer + private summary confirmed ──> HUMAN_ACTIVE
  └── retryable Chatwoot failure ──> HANDOFF_PENDING (retry/audit)

HUMAN_ACTIVE
  ├── human messages ──> HUMAN_ACTIVE
  ├── customer messages ──> HUMAN_ACTIVE (no auto AI reply)
  └── explicit approved Resume AI action ──> AI_ACTIVE

RESOLVED
  └── explicit reopen policy/action ──> AI_ACTIVE or HUMAN_ACTIVE (never infer silently)
```

Mandatory safety rules:

- The customer can request a human at any time using an explicit phrase such as “human”, “person”, “agent” or “representative”. Use deterministic matching for this test path; an LLM may supplement but must not be the only detection mechanism.
- Once handoff starts, stop queued and in-flight AI execution. If a model response returns after ownership changes, discard it.
- Before a customer-facing AI send, re-check ownership/version/status. Never send on an ambiguous state.
- A human's message is authoritative. AI never follows a human message with an unsolicited answer.
- Human ownership persists after browser refresh, API restart and duplicate webhook delivery.
- No automatic AI resume. Resume must be explicit and audited.
- Ensure handoff transition is safe if the webhook is delivered multiple times or the Chatwoot API times out after accepting the request.

#### Private handoff summary

Send a private/internal note into the Chatwoot conversation (after verifying the private-note API shape) with:

- Customer's issue and intent.
- Brief context gathered in the conversation.
- What the AI checked/did.
- Knowledge sources actually retrieved.
- Why the AI handed off.
- Suggested next step for the human agent.

Never label a source as retrieved if it was not. Never include chain-of-thought, raw prompts, credentials or hidden policy text.

### 4.6 Admin UI and customer demo page

Use one Next.js application to keep the MVP small.

Minimum pages:

- `/admin`: service health cards (platform API, Chatwoot, R2R and local model), one-tenant overview and quick links.
- `/admin/knowledge`: upload/list files, show R2R ingestion status, retry a failed ingestion if safe.
- `/admin/agent`: display configured agent, model, knowledge scope and current handoff policy; keep advanced prompt editing out of MVP.
- `/demo`: customer-facing sample page embedding Chatwoot's native website widget.
- `/admin/conversations` may link to Chatwoot rather than duplicating the inbox.

Use a clear, polished but simple UI. Prioritize functioning flows, observable status and error states over visual complexity.

Protect admin APIs. If using a local API key, proxy it server-side from Next.js and never expose it in browser JavaScript. Provide explicit local-only warning. Use env-driven Chatwoot website token/base URL and avoid committing secrets.

For the customer page, use Chatwoot's supported widget install script and configuration exactly as documented for the pinned version. Confirm the widget loads from a different local origin and that its domain/website-token configuration accepts the demo host.

### 4.7 Docker Desktop and local operations

The platform must be dockerized and repeatable on the developer machine.

- Build the custom Spring Boot API and Next.js app as Docker images using multi-stage Dockerfiles (JDK 25 and Node 26 respectively).
- Use Docker Compose; no Kubernetes, Helm, cloud deployment or remote infrastructure.
- Use named volumes for platform DB and local model data. Do not mount user home directories or existing Chatwoot/R2R volumes unnecessarily.
- Add health checks and correct startup dependencies. `depends_on` alone is not a readiness check; use health checks / `service_healthy` where Compose supports it.
- Publish only required developer-facing ports. Keep databases, Redis, R2R admin, Ollama and internal endpoints private/loopback where possible. Do not accidentally expose secrets or management ports to the LAN.
- Use strong random local secrets in ignored `.env`; `.env.example` contains placeholders only.
- Log to stdout/stderr so `docker compose logs` works. Include correlation IDs across webhook, agent execution, R2R request and Chatwoot send.
- Provide `scripts/doctor.ps1`, `scripts/up.ps1`, `scripts/down.ps1`, `scripts/status.ps1`, `scripts/logs.ps1`, `scripts/model-pull.ps1`, `scripts/bootstrap-chatwoot.ps1`, `scripts/bootstrap-r2r.ps1`, `scripts/golden-flow.ps1` and a guarded reset script.
- Do not automatically delete volumes on `down`. Destructive reset must require a typed confirmation containing the project name and explain that platform demo data will be erased. Never delete upstream user's volumes.
- `docker compose config` must pass before startup.
- Add a managed-local Compose profile for upstream services only after checking and pinning official upstream manifests. Keep their DBs and volumes separate. If using upstream Compose files, preserve upstream configuration and record exact source tag/commit.

Default host ports should be selected after collision checks. Suggested starting values: Chatwoot `3000`, R2R `7272`, Ollama `11434`, platform API `8080`, admin/demo web `4300`. Make all configurable through `.env` and document any detected conflicts. These are suggestions, not permission to stop a process already using a port.

---

## 5. Multi-agent workflow for Claude Code

Use **Claude Code custom subagents** defined in `.claude/agents/*.md`. Subagents have isolated context and should return concise findings, tests and evidence to the lead. Prefer stable subagent delegation over experimental Agent Teams features. Use experimental Teams only if the installed Claude Code version supports them and only when it reduces coordination risk.

### 5.1 Required specialists

Create at least these project-level agents. Follow the current Claude Code subagent file schema and verify it against official docs before writing frontmatter. Set narrowly scoped tools/permissions appropriate to each role.

| Agent | Primary assignment | Owns/writes |
|---|---|---|
| `upstream-researcher` | Inspect exact Chatwoot and R2R versions, API contracts, webhook payloads, Docker setup and release/security status. Return verified request/response examples. No implementation. | `docs/research/**` only |
| `solution-architect` | Define boundaries, data model, module map, event lifecycle, ADRs, acceptance design and local-compose strategy. Challenge unnecessary scope. | `docs/architecture/**`, `docs/adr/**` only |
| `security-reviewer` | Independently review R2R auth/config, webhook authenticity, secret handling, tenant/collection isolation, prompt injection, local exposure and handoff race risks. Review code later. | `docs/security/**` and review reports only |
| `backend-engineer` | Implement Java 25/Spring Boot 4.2/Spring Modulith 2.2 modules, migrations, Chatwoot/R2R adapters, webhook processing, handoff and unit/integration tests. | `apps/platform-api/**` only; request lead approval for shared contracts |
| `frontend-engineer` | Implement Nx/Next.js admin, knowledge upload/status and customer demo page with native widget. | `apps/admin-web/**` and its project config only |
| `devops-engineer` | Inspect Docker Desktop, build local Compose/Dockerfiles/scripts, ports, health checks, persistent volumes and diagnostics. | `infra/**`, `scripts/**` only |
| `qa-e2e-engineer` | Design independent tests, implement Playwright golden flow, run against actual local services, produce trace/screenshots and acceptance evidence. | `apps/e2e/**`, `docs/testing/**`, `artifacts/**` only |

The lead agent owns root `package.json`, `pnpm-workspace.yaml`, `nx.json`, root `project.json`, `.gitignore`, `.env.example`, `CLAUDE.md`, `README.md`, and integration/merge decisions. Avoid concurrent edits to root config files.

### 5.2 Delegation and merge rules

- Start with parallel research tasks because Chatwoot, R2R, Nx/Spring and local Docker inspection are independent.
- Research agents must return: sources, exact versions, source paths/line references, API paths, example payloads, assumptions, unresolved questions and confidence. A README example alone is insufficient.
- The lead synthesizes findings and resolves architecture conflicts before implementation.
- Parallelize backend, frontend and infrastructure only after their interfaces/ports/env contracts have been agreed in an architecture contract.
- Do not let two agents modify the same files at the same time.
- Every implementation agent must report files changed, commands executed, test results and remaining risks.
- QA/security agents must independently review the implementation; they must not merely repeat the builder's claims.
- Lead is responsible for running full Nx/Compose/E2E integration after merging each workstream.
- Keep subagent outputs concise; ask for evidence and results, not long speculative essays.

### 5.3 Mandatory lead prompts to subagents

Use tasks equivalent to:

**Researcher — Chatwoot:** “Inspect the exact running Chatwoot version and v4.20.1 source/docs. Verify AgentBot lifecycle, webhook signature/authenticity, customer incoming vs bot/human outgoing payloads, message creation including private notes, conversation update to open/pending, widget embed and human inbox. Capture sanitized real payloads and API probes. No code changes.”

**Researcher — R2R:** “Inspect exact running R2R tag/image/commit and release. Verify auth, users, collection ownership, document upload/status, search/RAG endpoints, citation response, local Ollama configuration and Docker compose dependencies. Review public security reports #2290/#2292/#2297/#2309/#2311 against the exact version and report status/mitigations. No code changes.”

**Researcher — stack:** “Verify Node/Nx/pnpm/Next compatibility, JDK25 + Spring Boot4.2 + Modulith2.2, Java/Maven/Nx integration. Recommend stable versions and Nx targets. No code changes.”

**Security reviewer:** “Threat model a single-tenant local AI support MVP. Review webhook replay/forgery, ownership race, R2R auth and collection boundary, prompt injection in documents, secret exposure, CORS/CSRF, local port exposure, sensitive logs, file upload and human override. Return fail-closed controls and tests.”

**Independent QA:** “Given acceptance criteria and running stack, execute the golden flow without relying on the builder's test claims. Verify with actual browser/API/DB observations. Capture trace/screenshots and mark all unverified outcomes clearly.”

---

## 6. Phased execution plan and approval gates

Do work in order. Keep each gate visible in `docs/implementation-status.md`.

### Phase 0 — Machine and existing-service reconnaissance

Tasks:

- Confirm Windows/PowerShell version, Docker Desktop engine status and Compose plugin version.
- Confirm JDK 25, Node 26 LTS, pnpm 12 and Git availability.
- Inspect running containers/projects, images, volumes, ports, networks and health states.
- Probe Chatwoot, R2R and Ollama endpoints from host and from a temporary container where relevant.
- Record exact images/tags/digests, configuration modes and whether inference is local or hosted.
- Check available memory, disk and GPU support before choosing local models.
- Detect port collisions and never stop existing processes.

Deliverables: `docs/research/local-machine-inventory.md`, `docs/research/version-matrix.md`, `docs/research/port-map.md`.

**Gate 0:** Local services identified; non-destructive execution plan approved by lead; no port/data collision unresolved.

### Phase 1 — Upstream research and API verification

Tasks:

- Chatwoot AgentBot API/probes/payload capture.
- R2R API/auth/collection/ingestion/retrieval/RAG probes.
- Ollama generation and embedding probes.
- R2R issue/version security review.
- Confirm that the actual local setup supports the desired lifecycle.

Deliverables: `docs/research/chatwoot-integration-contract.md`, `docs/research/r2r-integration-contract.md`, `docs/security/r2r-risk-review.md`, sanitized test fixtures.

**Gate 1:** The lead can demonstrate (a) Chatwoot sends an AgentBot customer message event, (b) the agent identity can post a message, (c) the conversation can be moved to human/open, (d) R2R can ingest and retrieve the demo document, and (e) all model operations use local inference. If any one fails, resolve or document a blocker before coding the full app.

### Phase 2 — Architecture, ADRs and contract freeze

Write:

- `docs/architecture/target-architecture.md`
- `docs/architecture/module-boundaries.md`
- `docs/architecture/conversation-lifecycle.md`
- `docs/architecture/data-model.md`
- `docs/architecture/local-runtime.md`
- `docs/adr/ADR-001-upstream-composition.md`
- `docs/adr/ADR-002-agentbot-integration.md`
- `docs/adr/ADR-003-r2r-and-local-model.md`
- `docs/adr/ADR-004-modular-monolith-and-event-processing.md`
- `docs/adr/ADR-005-one-tenant-isolation.md`
- `docs/adr/ADR-006-human-authoritative-handoff.md`

Freeze: API route contracts, environment variable names, ports, tenant seed, state machine, source attribution format, ownership rules and Compose networking.

**Gate 2:** No implementation agent starts parallel coding until the lead and independent reviewer agree that the contracts are coherent and the scope remains MVP-sized.

### Phase 3 — Empty-folder bootstrap and Nx foundation

Tasks:

- Initialize Git and Nx integrated workspace in the current empty folder.
- Set Node/pnpm/Nx pins and lockfile.
- Generate Next.js app with compatible Nx plugin.
- Create Java Maven wrapper project through Spring Initializr/official Spring tooling or equivalent verified generator, pin Boot4.2.0 and Modulith2.2.0.
- Configure Java 25 toolchain and Dockerfiles.
- Create `.claude/agents` and `CLAUDE.md`.
- Add formatting/lint/typecheck/test/build targets and root scripts.
- Verify the workspace builds before adding business logic.

**Gate 3:** `pnpm install --frozen-lockfile`, `nx show projects`, `nx graph`, Next build, Maven wrapper compile/test and Docker image builds pass.

### Phase 4 — Platform skeleton and service health

Tasks:

- Create platform DB and Flyway migrations.
- Seed `acme-demo` tenant and integration mappings through safe local bootstrap.
- Implement `/actuator/health` and protected `/api/admin/diagnostics`.
- Implement Chatwoot and R2R adapters with health/readiness checks.
- Build admin UI service-health page.
- Build scripts that start only required services and keep upstream projects/volumes isolated.

**Gate 4:** All configured services report healthy; API can reach Chatwoot and R2R; no secret is exposed in browser or logs; restart preserves platform state.

### Phase 5 — Tenant knowledge base

Tasks:

- Implement admin file upload and validation.
- Ingest the fictional policy file into the tenant's R2R collection using API.
- Persist metadata and R2R document ID.
- Show ingestion status and retry-safe failures.
- Implement retrieval probe and cite/source rendering.
- Add negative tenant-scope test.

**Gate 5:** Demo policy ingestion succeeds; known-answer query retrieves the expected source; unrelated/out-of-scope query does not result in an invented policy; cross-scope access test passes.

### Phase 6 — AI Agent and Chatwoot webhook loop

Tasks:

- Implement AgentBot webhook endpoint and security validation.
- Persist and deduplicate incoming webhook events.
- Process one incoming customer message per conversation turn.
- Add simple deterministic request-human rule.
- Add R2R retrieval/RAG and evidence policy.
- Post AI answer via Chatwoot API only after ownership/status check.
- Ignore bot and human outgoing events.
- Add correlation IDs, retries and idempotency tests.

**Gate 6:** Customer messages trigger exactly one answer; bot's own message does not cause recursion; duplicate webhook/retry does not duplicate the answer; answer cites the demo source.

### Phase 7 — Human handoff and takeover

Tasks:

- Implement persistent state machine and ownership lock/lease.
- Implement human-request handoff.
- Write the private handoff note with summary and source list.
- Change Chatwoot conversation to `open` and verify it appears in human inbox.
- Observe human agent reply and mark conversation `HUMAN_ACTIVE`.
- Ensure no AI answer is sent after handoff or after human reply.
- Implement explicit resume only if safe and tested; otherwise document it as manual-only.

**Gate 7:** Real human agent can take over and reply through Chatwoot; customer sees reply; AI remains silent while human owns conversation. No race, duplicate or late AI send is observed.

### Phase 8 — E2E, resilience and independent quality review

Tasks:

- Complete Playwright golden flow.
- Run unit, integration, module-boundary, frontend, browser, security and static checks.
- Exercise service restart, webhook redelivery, R2R timeout, Chatwoot timeout, no-evidence query and human takeover while AI work is in flight.
- Review all logs for secrets/PII leakage.
- Run a small local load sanity check (not a 10k scale test).
- Ask an independent QA/security subagent to review and reproduce key acceptance tests.

**Gate 8:** All mandatory MVP acceptance tests pass, or each remaining failure is clearly marked and the MVP is not labelled accepted. Save trace, screenshot, API and test reports.

### Phase 9 — Developer handover

Tasks:

- Complete README and local runbook.
- Record all versions, image digests, local model choices, ports, setup steps, account/inbox/AgentBot configuration, environment variables and reset behavior.
- Provide a one-command verification workflow.
- Provide a troubleshooting guide for common failures.

**Gate 9:** A developer who did not build the project can start it and reproduce the golden flow by following README instructions, without cloud credentials.

---

## 7. Guardrails and failure handling

### 7.1 Security and privacy

- All runtime services are local. No cloud LLM, external embeddings, hosted vector store, SaaS tracing or customer data telemetry.
- Never commit `.env`, API tokens, passwords, Chatwoot website tokens if treated as environment config, R2R service tokens, JWT secrets or model credentials.
- `.env.example` contains nonfunctional placeholders only. Add secret scanning to local checks if practical.
- Use distinct, strong generated secrets for platform admin, Chatwoot integration, webhook callback and R2R. Do not reuse a default or hard-coded key.
- Only server-to-server calls hold integration credentials.
- Validate inbound webhook signatures/authenticity according to the verified upstream contract; timestamp/replay protection where supported.
- Validate request size, content type, upload type and maximum file size. Store uploads safely; do not allow arbitrary path traversal or executable content.
- Treat knowledge documents and retrieved passages as untrusted data. Prompt-injection text inside a document cannot override system policy, call tools, reveal secrets or request a state change.
- Customer-facing agent has no autonomous write tools in this MVP.
- Log IDs, timings, statuses and error codes; avoid raw conversation transcript, full uploaded file contents, secret headers and prompts in ordinary logs.
- Protect admin operations and bind services to local/loopback networks wherever possible.
- Do not use real customer data or credentials.

### 7.2 Reliability

- Webhook handler returns promptly after durable event acceptance; don't keep Chatwoot waiting for slow model generation.
- Idempotent event ingestion and outbound send handling.
- Bounded retries with backoff; no infinite retry loop.
- A failed event has visible terminal status and a safe retry path.
- Conversation events serialize by conversation ID.
- AI ownership change cancels/discards pending generations.
- Chatwoot/R2R unavailable must fail safely; do not send guessed answers.
- Persist local ownership and handoff state across restart.
- Provide a dead-letter/failed event view in diagnostics (a full queue UI is out of scope).

### 7.3 RAG and AI quality

- Never claim the AI is grounded unless source evidence is present.
- Use only the demo tenant's R2R collection.
- Show genuine sources, do not fabricate document titles/page numbers/links.
- For no evidence or conflicting evidence, decline to invent and offer handoff.
- Human requests override all other agent policies.
- Customer-facing prompt should identify retrieved text as untrusted evidence, not instructions.
- No browsing or external web-search tools for the support agent.
- No chain-of-thought collection or display.
- Keep prompts/configuration in version-controlled templates with clear version identifiers.
- Maintain a tiny deterministic evaluation dataset with expected facts and expected handoff cases.

### 7.4 Engineering quality

- TypeScript strict mode and lint.
- Java compile with warnings treated seriously; use formatter and static analysis appropriate to Maven/Spring Boot 4.
- No `@SuppressWarnings`/eslint disables without documented reason.
- No placeholder methods or fake success responses.
- No in-memory-only workflow state for handoff/idempotency.
- No direct upstream database queries.
- No hidden dependency on host-installed Maven or Node beyond documented Node/pnpm/JDK; containers run the app.
- Every new integration endpoint has unit and integration coverage.
- Architecture module verification runs in build.

---

## 8. Acceptance criteria

Use IDs below in test names and final report.

### Environment and reproducibility

- **ENV-01:** Developer machine has Docker Desktop engine and Compose v2 available; scripts detect unavailable engine clearly.
- **ENV-02:** Java build uses JDK 25; backend Docker image uses Java 25.
- **ENV-03:** Node 26 LTS, pnpm 12 and Nx 24 are pinned and reported by `pnpm doctor`/version script.
- **ENV-04:** `pnpm install --frozen-lockfile` succeeds from a clean checkout.
- **ENV-05:** `docker compose config` succeeds for all managed Compose files.
- **ENV-06:** `pnpm local:up` starts required services without deleting/modifying existing Chatwoot/R2R data.
- **ENV-07:** `pnpm local:status` accurately reports health and configured URLs.
- **ENV-08:** `pnpm local:down` does not delete persistent volumes.

### Tenant and knowledge

- **KB-01:** Exactly one seeded tenant `acme-demo` is active.
- **KB-02:** Admin can upload the fictional policy document and see ingestion status.
- **KB-03:** R2R stores the document in the tenant's own verified collection/scope.
- **KB-04:** A query about standard delivery retrieves the correct policy chunk/source.
- **KB-05:** The AI answer includes accurate source attribution from real R2R response data.
- **KB-06:** Unsupported questions do not produce invented business policy.
- **KB-07:** A cross-scope/unauthorized retrieval attempt is denied or returns no tenant data; test evidence is saved.

### AI and Chatwoot integration

- **AI-01:** Chatwoot AgentBot is connected to the demo website inbox.
- **AI-02:** New widget conversations enter the verified AI-pending state/status.
- **AI-03:** A customer incoming message produces exactly one webhook event record and one agent execution.
- **AI-04:** AI response is posted as the correct bot identity through Chatwoot API.
- **AI-05:** AI-created outgoing message does not trigger an AI recursion loop.
- **AI-06:** Non-customer/human outgoing messages are never treated as customer prompts.
- **AI-07:** R2R inference/embedding uses local Ollama; network/proxy/log evidence shows no hosted LLM API dependency.

### Human handoff

- **HO-01:** Explicit customer request for a human immediately starts handoff.
- **HO-02:** AI ownership moves to `HANDOFF_PENDING` before any further AI send can occur.
- **HO-03:** Chatwoot conversation is updated to `open` using verified API contract.
- **HO-04:** Conversation is visible in Chatwoot human inbox.
- **HO-05:** A private handoff note contains intent, context, steps taken, genuine sources, reason and next action.
- **HO-06:** Human agent can reply through Chatwoot UI and customer receives the reply in the widget.
- **HO-07:** After handoff/human takeover, further customer messages do not trigger automatic AI replies.
- **HO-08:** A late AI generation result is discarded if ownership changed before send.
- **HO-09:** Duplicate handoff webhook/retry does not create multiple handoff notes or conflicting status transitions.
- **HO-10:** AI does not resume automatically; any resume is explicit and audited.

### Resilience and security

- **RS-01:** Replaying the same webhook does not duplicate a customer-facing response.
- **RS-02:** Restarting platform API preserves conversation ownership and webhook processing outcome.
- **RS-03:** R2R timeout/failure does not cause a fabricated customer answer.
- **RS-04:** Chatwoot API timeout/retry is safe and does not duplicate bot messages/handoff notes.
- **SEC-01:** No secrets in Git, browser bundle, screenshots, reports or logs.
- **SEC-02:** R2R uses a unique non-default strong secret/auth mode and stays local-only.
- **SEC-03:** Webhook verification/restriction is tested and documented.
- **SEC-04:** Prompt injection in a test document cannot override instructions or trigger actions.
- **SEC-05:** Upload path traversal/unsupported file/oversized file tests are rejected.

### Quality gates

- **Q-01:** Nx project graph resolves.
- **Q-02:** Frontend lint, typecheck, unit tests and production build pass.
- **Q-03:** Backend Maven `verify` passes.
- **Q-04:** Spring Modulith module-structure verification passes.
- **Q-05:** API integration tests with PostgreSQL pass.
- **Q-06:** Playwright browser golden flow passes against real local services.
- **Q-07:** Independent QA subagent reproduces the main flow.
- **Q-08:** No known critical/high blocker in the local-MVP threat model is left undocumented.

---

## 9. Golden flow — primary MVP acceptance demonstration

The golden flow must be executable repeatedly from a clean/known local state. Use real local services and the actual Chatwoot widget/inbox. Do not use stubs for the final run.

### Preconditions

- Docker Desktop running.
- Chatwoot reachable and configured with one account, one website inbox, one AgentBot and one human agent/team.
- R2R reachable, authenticated and configured for local Ollama inference.
- Ollama model and embedding model downloaded and responding.
- `acme-demo` tenant and its R2R collection provisioned.
- Fictional policy document ingested and ready.
- Platform API and Next.js container healthy.
- E2E human-agent credentials present in ignored local test environment (or manual human UI step is documented).

### Steps

1. Run `pnpm local:doctor` and `pnpm local:status`. Save results.
2. Run `pnpm local:up` if any custom platform service is stopped. Verify Chatwoot, R2R, Ollama, platform API and admin web are healthy.
3. Open `http://localhost:4300/admin/knowledge`. Confirm `acme-demo` and the policy document are visible as ingested.
4. Open `http://localhost:4300/demo` in a clean browser context. Wait for the native Chatwoot launcher to appear and open it.
5. Start a new conversation as a customer and send: **“What is your standard delivery time?”**
6. Verify the platform receives a Chatwoot AgentBot event; one event is durably stored; R2R retrieves the relevant policy; local model generates the answer; Chatwoot displays exactly one bot reply.
7. Verify the reply says standard delivery is **3–5 business days after dispatch** (dispatch itself takes **1–2 business days**) and includes the real policy source reference. Do not accept a generic answer with no source.
8. Send: **“I need to speak to a human agent, please.”**
9. Verify the platform immediately transitions to `HANDOFF_PENDING`, stops any pending AI turn, creates one private handoff note and updates the Chatwoot conversation to `open`.
10. Open Chatwoot's native agent dashboard in a second browser context and sign in as the human agent. Locate the conversation in the human inbox. Verify the summary and source context are visible as a private note, not a customer-visible AI message.
11. As the human agent, send: **“Hello, I’m Alex from support. I’ll review this and help you.”**
12. Verify the customer receives the human reply in the same widget conversation.
13. Send another customer message while the human owns the conversation: **“Can you also check this for me?”**
14. Verify that no automatic AI reply appears, ownership remains `HUMAN_ACTIVE`, and the platform records the customer event without invoking R2R/generating a customer-facing AI answer.
15. Replay the original customer webhook fixture or repeat the same webhook delivery. Verify no duplicate bot answer or duplicate handoff note is created.
16. Restart only the custom platform API container. Verify ownership remains human and the next customer message still receives no AI answer.
17. Save Playwright HTML report, trace, screenshots, backend logs/correlation IDs and sanitized database/API evidence.

### Golden-flow pass condition

All 17 steps complete with observed evidence. The AI answer is grounded in the tenant's own knowledge; human handoff is visible in Chatwoot; the actual human reply reaches the customer; and the AI stays silent after takeover. If the human reply is simulated only through a backend API rather than the Chatwoot UI, label that run **partial** and do not call it the complete golden flow.

### Secondary scenarios

Also test:

- Customer asks something absent from the policy: the AI does not invent a fact and offers/initiates handoff according to configured policy.
- Customer asks for a human before asking a knowledge question: immediate handoff.
- Duplicate webhook delivery.
- R2R temporarily unavailable.
- Chatwoot temporarily unavailable.
- Model request still running while a human takes over.
- Malicious instruction embedded in a knowledge document.
- Platform API restart while a message is queued.

---

## 10. Required project documentation and artifacts

By the end, the repository must contain at least:

```text
README.md
CLAUDE.md
bootstrap.md
.env.example

.claude/agents/
  upstream-researcher.md
  solution-architect.md
  security-reviewer.md
  backend-engineer.md
  frontend-engineer.md
  devops-engineer.md
  qa-e2e-engineer.md

docs/
  implementation-status.md
  research/
    version-matrix.md
    local-machine-inventory.md
    port-map.md
    chatwoot-integration-contract.md
    r2r-integration-contract.md
  architecture/
    target-architecture.md
    module-boundaries.md
    conversation-lifecycle.md
    data-model.md
    local-runtime.md
  adr/
    ADR-001-upstream-composition.md
    ADR-002-agentbot-integration.md
    ADR-003-r2r-and-local-model.md
    ADR-004-modular-monolith-and-event-processing.md
    ADR-005-one-tenant-isolation.md
    ADR-006-human-authoritative-handoff.md
  security/
    r2r-risk-review.md
    threat-model.md
    security-test-results.md
  testing/
    test-strategy.md
    golden-flow-report.md
    evaluation-dataset.md

docs/demo-tenant/
  acme-support-policy.md

infra/
  platform/
  chatwoot/          # only if managed-local upstream mode is included
  r2r/               # only if managed-local upstream mode is included
  ollama/

scripts/
  doctor.ps1
  up.ps1
  down.ps1
  status.ps1
  logs.ps1
  model-pull.ps1
  bootstrap-chatwoot.ps1
  bootstrap-r2r.ps1
  golden-flow.ps1
  reset.ps1           # destructive, confirmation required

artifacts/
  e2e/                # ignored or sanitized test output
```

Do not commit test reports containing secrets, real customer PII or local absolute paths. Keep artifacts reproducible and sanitized.

---

## 11. Developer commands and expected experience

Use these as desired command names. Implement them as Nx targets or root package scripts that call the correct PowerShell/Docker/Maven commands.

```powershell
# Check developer machine and local dependencies
pnpm local:doctor
pnpm local:status

# Install dependencies and verify Nx graph
pnpm install --frozen-lockfile
pnpm nx show projects
pnpm nx graph

# Start local platform and any required managed-local dependencies
pnpm local:up

# See logs and diagnostics
pnpm local:logs
pnpm local:status

# Run quality gates
pnpm nx run-many -t lint typecheck test build
pnpm nx run platform-api:verify
pnpm nx run e2e:golden-flow

# Run the full golden flow with report artifacts
pnpm local:golden-flow

# Stop services but preserve volumes/data
pnpm local:down

# Destructive reset is separate and must require a typed confirmation
pnpm local:reset
```

The final `README.md` must show the exact URLs, setup requirements, manual Chatwoot one-time configuration (if needed), local model download, expected first-start time caveat for model pulls, troubleshooting and reset semantics. Do not claim that a command exists until it has been implemented and executed successfully.

---

## 12. Final delivery report format

At the end, write `docs/testing/golden-flow-report.md` and respond with a concise handover containing:

1. **What was built** — apps/modules and upstream integration boundaries.
2. **Exact versions** — Nx, Node, pnpm, JDK, Spring Boot, Spring Modulith, Chatwoot, R2R, Ollama and model names, plus image digests where available.
3. **How to run** — exact start/stop/status/golden-flow commands and local URLs.
4. **Acceptance matrix** — each ENV/KB/AI/HO/RS/SEC/Q ID marked PASS/FAIL/BLOCKED with evidence references.
5. **Golden-flow evidence** — Playwright report, trace, screenshots, Chatwoot conversation ID (local demo only), R2R document/collection IDs and sanitized correlation IDs.
6. **Known limitations** — especially R2R security/maintenance review, local inference quality/resource needs and any manually configured Chatwoot settings.
7. **Next recommended milestone** — only after MVP acceptance; do not silently expand scope now.

A green build is not equivalent to an end-to-end working product. A mocked integration test is not equivalent to the golden flow. An AI response that is not traceable to the tenant's own knowledge is not a passing RAG test. A status change without a real human reply in the widget is not a passing handoff test.

---

## 13. Official research references

Use these as starting points and verify the exact version/branch before relying on them.

### Chatwoot

- Chatwoot v4.20.1 release: <https://github.com/chatwoot/chatwoot/releases/tag/v4.20.1>
- Chatwoot v4.20.1 source: <https://github.com/chatwoot/chatwoot/tree/v4.20.1>
- AgentBot lifecycle and handoff: <https://www.chatwoot.com/hc/user-guide/articles/1677497472-how-to-use-agent-bots>
- Webhook events and behavior: <https://www.chatwoot.com/hc/user-guide/articles/1677693021-how-to-use-webhooks>
- Developer API docs: <https://developers.chatwoot.com/>
- Self-hosted deployment: <https://www.chatwoot.com/deploy>

### R2R

- R2R repository: <https://github.com/SciPhi-AI/R2R>
- R2R releases: <https://github.com/SciPhi-AI/R2R/releases>
- Full local Ollama sample config: <https://github.com/SciPhi-AI/R2R/blob/v3.6.5/py/core/configs/full_ollama.toml> (verify the path exists at the selected tag; otherwise inspect the pinned source tree and use its equivalent).
- R2R Ollama sample config: <https://github.com/SciPhi-AI/R2R/blob/v3.6.5/py/core/configs/ollama.toml> (verify against the selected tag).
- R2R full Docker Compose manifest: <https://github.com/SciPhi-AI/R2R/blob/v3.6.5/compose.full.yaml> (inspect profiles and dependencies before reusing).
- R2R security policy: <https://github.com/SciPhi-AI/R2R/security>
- R2R issues (review relevant security reports and their current status): <https://github.com/SciPhi-AI/R2R/issues>
- R2R documentation: <https://r2r-docs.sciphi.ai/>

### Nx, frontend and package manager

- Nx changelog/version history: <https://nx.dev/changelog>
- Nx + Next.js: <https://nx.dev/docs/technologies/react/next/introduction>
- Nx + Java: <https://nx.dev/docs/technologies/java/introduction>
- Nx + Maven (currently documented as experimental; avoid depending on it): <https://nx.dev/docs/technologies/java/maven/introduction>
- Node release lines: <https://nodejs.org/en/about/previous-releases>
- pnpm installation and Node compatibility: <https://pnpm.io/installation>
- Playwright Test: <https://playwright.dev/docs/intro>

### Java/Spring and Docker

- Spring Boot system requirements: <https://docs.spring.io/spring-boot/system-requirements.html>
- Spring Boot 4.2.0 release: <https://spring.io/blog/2026/11/19/spring-boot-4-2-0-available-now/>
- Spring Modulith 2.2 GA release and Boot 4.2 compatibility: <https://spring.io/blog/2026/11/19/spring-modulith-2-2-ga-2-1-3-and-2-0-10-released/>
- Spring Modulith reference: <https://docs.spring.io/spring-modulith/reference/>
- Docker Compose documentation: <https://docs.docker.com/compose/>
- Docker Compose production/deployment patterns: <https://docs.docker.com/compose/how-tos/production/>

---

## 14. First command to Claude Code

After placing this file in the empty project folder and opening Claude Code in that folder, issue:

> Read `bootstrap.md` completely and execute it as the build contract. Start with Phase 0 reconnaissance and parallelize independent research using project-level Claude Code subagents. Do not modify or delete my existing Docker containers, images, volumes or data. Verify the actual Chatwoot and R2R instances and exact API contracts before coding. Use JDK 25, Spring Boot 4.2.x, Spring Modulith 2.2.x, Nx 24.x, Node 26 LTS, pnpm 12 and a compatible stable Next.js. Keep the entire runtime local and dockerized, use Ollama for local inference, and build exactly the one-tenant Acme Demo MVP. Continue through implementation, real browser E2E and the golden-flow acceptance report. Stop only at a defined approval gate, when a required secret/action is unavailable, or when a verified security/integration blocker makes proceeding unsafe. Never mark unexecuted tests as passed.
