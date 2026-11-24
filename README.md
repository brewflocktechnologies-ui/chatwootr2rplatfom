# Acme Demo: local AI-first, human-first support MVP

One tenant (`acme-demo`), one private knowledge base, one AI agent, one Chatwoot human inbox. The brief is `bootstrap.md`; the frozen API contract is `docs/architecture/api-contract.md`; the status of every gate is `docs/implementation-status.md`.

> Status: **in progress.** Nothing in this README is a pass until `docs/testing/golden-flow-report.md` says so.

## What runs locally

| Service | URL | Project (compose name) | Pinned |
|---|---|---|---|
| Chatwoot (native widget + agent inbox) | http://127.0.0.1:3100 | `infra/chatwoot` (`saas-chatwoot`) | v4.20.1 |
| R2R (knowledge + RAG) | http://127.0.0.1:7272 | `infra/r2r` (`saas-r2r`) | 3.6.5 |
| Ollama (local models) | http://127.0.0.1:11434 | `infra/ollama` (`saas-ollama`) | 0.40.17 |
| Platform API | http://127.0.0.1:8080 | `infra/platform` (`saas-platform`) | Spring Boot 4.2.0 / Java 25 |
| Admin and demo web | http://localhost:4300 | `infra/platform` | Next.js 16.4.6 |

Versions and image digests: `docs/research/version-matrix.md`. Ports: `docs/research/port-map.md`.

## Requirements
- Docker Desktop running (Linux engine, Compose v2).
- Windows PowerShell 5.1 or later.
- JDK 25 at `C:\Program Files\Java\jdk-25.0.3` for host builds (the Docker build uses a JDK 25 image).
- Node 26.13.1 and pnpm 12.23.0 run inside `node:26.13.1-bookworm-slim` (no host Node needed).
- About 8 GB of free disk for images and models. The first start downloads model weights (about 2.7 GB).

## Check the machine

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\doctor.ps1
```

Read-only. It never stops or removes anything.

## Start, status, stop

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\up.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\down.ps1
```

`down.ps1` keeps every volume. `reset.ps1` erases platform demo data only, after you type the confirmation text.

## Local secrets (one-time, git-ignored)

These files are generated locally and never committed: `infra/chatwoot/.env.secrets`, `infra/r2r/.env`, `infra/r2r/config/r2r.local.toml`, `infra/platform/.env`. The generation steps are recorded in `docs/research/local-machine-inventory.md`. Do not share these files.

## Chatwoot one-time setup (already done on this machine)

The account, website inbox, team, human agent and AgentBot were created through the Chatwoot API and one Rails runner command (the first-run onboarding route is gated in v4.20.1). `scripts/bootstrap-chatwoot.ps1` verifies the setup; it does not create it. See `docs/research/chatwoot-integration-contract.md`.

## Known deviations (see `docs/research/version-matrix.md`)
- Chatwoot's SafeFetch private-network switch is enabled for this local profile only (`SAFE_FETCH_ALLOW_PRIVATE_NETWORK=true`). The AgentBot webhook must reach `host.docker.internal`.
- R2R 3.6.5 is the latest tagged release and has open upstream security issues. See `docs/security/r2r-risk-review.md`. Use synthetic data only.

## Not yet available
- `scripts/golden-flow.ps1` and the Playwright golden flow (`apps/e2e`) are not implemented yet. Running it fails on purpose.
