# Implementation status (gates)

Last updated: 24 Nov 2026. Evidence is referenced by file, not pasted. A gate is PASS only when its evidence exists.

## Phase gates

| Gate | Requirement (bootstrap §6) | Status | Evidence |
|---|---|---|---|
| Gate 0 | Local services identified; no port or data collision unresolved | **PASS** | `docs/research/local-machine-inventory.md`, `docs/research/port-map.md`; doctor passes |
| Gate 1 | (a) Chatwoot sends AgentBot customer event; (b) agent identity posts; (c) conversation moved to open; (d) R2R ingests and retrieves demo doc; (e) local model ops only | **PARTIAL**: a, b, c PASS at API level; d PASS (ingest, retrieve, RAG); e PASS. Human inbox UI view still to verify. | `docs/research/chatwoot-integration-contract.md` (P1–P10), `docs/research/r2r-integration-contract.md` (R1–R11) |
| Gate 2 | Architecture contract frozen and reviewed | **PARTIAL**: `api-contract.md`, `conversation-lifecycle.md` written. ADRs and independent review not done. | `docs/architecture/` |
| Gate 3 | Nx workspace, Next build, Maven build, Docker images build | **IN PROGRESS** (backend and frontend builders running) | `apps/` |
| Gate 4 | Platform skeleton and service health | **IN PROGRESS** | `scripts/status.ps1` |
| Gate 5 | Demo policy ingestion; known-answer retrieval; unsupported query no invented policy; cross-scope denied | **PARTIAL**: ingestion, known-answer retrieval and cross-scope denial PASS on the R2R API. Unsupported-query behaviour is enforced by the platform (not built yet). | `docs/research/r2r-integration-contract.md` |
| Gate 6 | Customer message → exactly one answer; no recursion; duplicate webhook no second answer; cites source | **NOT STARTED** (platform webhook worker not verified) | |
| Gate 7 | Human takeover via Chatwoot UI; AI silent after takeover | **NOT STARTED** | |
| Gate 8 | Golden flow and independent QA | **NOT STARTED** | |
| Gate 9 | Developer handover | **NOT STARTED** | |

## Acceptance matrix (bootstrap §8)

Only items with executed evidence are marked. Everything else is `NOT RUN`.

| ID | Status | Evidence |
|---|---|---|
| ENV-01 | PASS | doctor and status scripts (Docker engine reachable; clear message when down) |
| ENV-02 | NOT RUN | JDK 25 backend image pending |
| ENV-03 | PARTIAL | Node 26.13.1, pnpm 12.23.0, Nx 24.0.6 pinned; `pnpm --version` verified in container. Nx check pending. |
| ENV-04 | NOT RUN | `pnpm install --frozen-lockfile` pending |
| ENV-05 | PARTIAL | `docker compose config` passes for `infra/chatwoot`, `infra/r2r`, `infra/ollama`, `infra/platform` |
| ENV-06 | NOT RUN | `local:up` not run as a single command yet |
| ENV-07 | PASS | `scripts/status.ps1` reports health and URLs |
| ENV-08 | NOT RUN | `down.ps1` not yet executed (designed to keep volumes) |
| KB-01 | PASS | exactly one tenant: `acme-demo` in the contract; R2R collection `acme-demo-knowledge` |
| KB-02 | PARTIAL | ingestion succeeds through the R2R API; admin upload UI not verified yet |
| KB-03 | PASS | document in the tenant collection, owned by the tenant service identity |
| KB-04 | PASS | "standard delivery time" returns the correct policy chunk (score 0.649) |
| KB-05 | PARTIAL | RAG answer cites 2 real R2R chunks; platform response formatting not verified yet |
| KB-06 | NOT RUN | platform evidence threshold and handoff not built yet |
| KB-07 | PASS | unrelated user gets 0 hits, 404 on document, 403 on collection (`docs/security/r2r-risk-review.md`) |
| AI-01 | PARTIAL | AgentBot connected to inbox 1 (`set_agent_bot` 200) |
| AI-02 | NOT RUN | new conversations observed as `open`, not `pending` (open item) |
| AI-03 | NOT RUN | platform webhook processing not built |
| AI-04 | PARTIAL | bot identity posts (`agent_bot` sender) verified by API |
| AI-05 | NOT RUN | recursion guard not built |
| AI-06 | NOT RUN | |
| AI-07 | PASS | generation and embedding run on local Ollama; no hosted API key in R2R config or env |
| HO-01 to HO-10 | NOT RUN | handoff not built. Status toggle to `open` verified by API only (P7). |
| RS-01 to RS-04 | NOT RUN | |
| SEC-01 | PARTIAL | secrets git-ignored; no secret in browser code (not yet built). Not yet scanned. |
| SEC-02 | PASS | R2R `R2R_SECRET_KEY` set (96 hex), bcrypt provider, default admin password rejected, loopback-only |
| SEC-03 | PARTIAL | HMAC verified on a real payload (P4); platform verification not built |
| SEC-04 | NOT RUN | prompt injection test not written |
| SEC-05 | NOT RUN | upload tests not written |
| Q-01 to Q-08 | NOT RUN | |

## Open items (blocking the golden flow)
1. Verify the private-note request/response shape in Chatwoot v4.20.1 (not probed yet).
2. Confirm the human agent's reply is delivered to the widget and captured as a webhook (needs UI run).
3. Decide and verify how AI-owned conversations are kept `pending` (new conversations arrive `open`).
4. Evidence threshold (`AGENT_EVIDENCE_MIN_SCORE`) needs the evaluation dataset.
5. Node 26 on the host is not installed (containers used). Install with user approval if host commands are wanted.

## Known limits accepted (24 Nov 2026)

- **In-flight answer window (HO-08 residual).** A human request that arrives after the AI answer's Chatwoot POST has already started can still let that single answer reach the customer. Intake cannot cancel an HTTP call in progress. Every other case is covered: the answer is discarded when a human request arrives during the R2R call, or before the send. Accepted by the product owner as a documented limit. Revisit with a post-send check if the demo moves beyond evaluation.
