# Version matrix (verified 24 Nov 2026, local machine)

Every row was checked against an official source or the running local system. "Image digest" is the local `RepoDigests` value.

| Area | Version used | Evidence | Notes |
|---|---|---|---|
| Chatwoot | **v4.20.1** | GitHub release `v4.20.1` (published 2026-11-20); tag commit `93cdb8737511a3569d3bebc3643199df4510f05f` | Image `chatwoot/chatwoot@sha256:49cf2b0dbbe5610417769bee15ae8a7d0474eab331ea8dbd9f3aae06d07b42cb`. Upstream compose at the tag still says `:latest`; our profile pins the tag. |
| Chatwoot Postgres | `pgvector/pgvector:pg16` | upstream compose at v4.20.1 | digest `sha256:484e838076e04939d27d3e176e91c86eea96236126023be8d3059761122e579f` |
| Chatwoot Redis | `redis:alpine` | upstream compose | digest `sha256:61d65002475565ac459faee56be20007561fe3cb6209a09cf967d25d3f3499cb` (floating tag; pin when re-testing) |
| R2R | **3.6.5** (sciphiai/r2r) | GitHub release `v3.6.5` (2025-06-06, latest tag as of research); Docker Hub tag `3.6.5` | Image `sciphiai/r2r@sha256:e7caf41aba36db5561a2579eeb3e4763c907ae4d4b241712a33e783fa381aa8a`. Upstream compose uses `:latest`; we use the tag. **Maintenance gate: see `docs/security/r2r-risk-review.md`.** |
| R2R Postgres | `pgvector/pgvector:pg16` | upstream compose at v3.6.5 | own volume `saas-r2r_postgres_data` |
| Ollama | **0.40.17** | Docker Hub tag `0.40.17` (stable; `0.40.18-rc0` exists but is a release candidate) | Image `ollama/ollama@sha256:ccc42bcd3d5fcf7ab0d532b300382154e1a4da56fc666bdb313b78ebdd912754` |
| Ollama model: generation | `llama3.2:3b` (ID `a80c4f17acd5`, 2.0 GB) | local `ollama list` | 100% GPU on RTX 5060 Laptop (8151 MiB). Chosen for modest VRAM/disk; R2R upstream sample used `llama3.1`. |
| Ollama model: embedding | `mxbai-embed-large` (ID `468836162de7`, 669 MB) | local `ollama list`; output dimension 1024 observed | Matches R2R's shipped Ollama config. |
| Java | **JDK 25.0.3** (host builds) | `C:\Program Files\Java\jdk-25.0.3` | The machine's default `java` is 27; builds must select 25 explicitly. |
| Spring Boot | **4.2.0** | Maven Central POM present (HTTP 200); Spring blog 2026-11-19 | System requirement 17–27 covers Java 25. |
| Spring Modulith | **2.2.0** | Maven Central BOM present (HTTP 200) | Boot 4.2 build compatibility per Spring release note. |
| Node.js | **26.13.1 LTS** (in container `node:26.13.1-bookworm-slim`) | nodejs.org dist index (v26.13.1, LTS "Lithium") | Host has Node 24.18.0 (not used for project tooling). Host Node 26 install needs a user-approved download; containers are used instead. |
| pnpm | **12.23.0** | npm `pnpm@12.23.0` | Run inside the Node 26 container. Host npx shim for pnpm 12 is broken on this machine. |
| Nx | **24.0.6** | npm `nx@24.0.6` | `@nx/next`, `@nx/js` aligned to 24.0.6. Nx Cloud not enabled. |
| Next.js | **16.4.6** | npm `next@16.4.6` | Strict TypeScript. |
| Playwright Test | **1.65.1** | npm `@playwright/test@1.65.1` | Browser binaries pinned when the e2e project is added. |
| Docker Desktop engine | **29.9.2** | `docker version` | Linux engine (`desktop-linux` context). |
| Docker Compose | **v5.6.1** | `docker compose version` | Compose v2 syntax used (`name:`, `depends_on.condition`). |
| Host Maven | 3.9.16 | `C:\Program Files\maven\apache-maven-3.9.16` | Only used to bootstrap the Maven Wrapper; builds use `mvnw`. |
| Windows | 11 Home 10.0.26300 | system | 23.7 GB RAM; GPU NVIDIA RTX 5060 Laptop (8 GB VRAM). |

## Deviations from the brief (recorded, not silent)

1. **Node 26 and pnpm 12 run in containers**, not on the host. Host Node is 24.18.0 and the host `npx pnpm@12` shim fails. Reason: avoid an unapproved binary download. Action for the user: install Node 26.13.1 on the host if you want host-side commands.
2. **Chatwoot and R2R run as pinned managed-local profiles** (`infra/chatwoot`, `infra/r2r`), not as the user's existing stacks. The user's `E:\SAAS\CHATWOOT` compose uses `chatwoot/chatwoot:latest` and volumes named `chatwoot_*`. Starting it could migrate live data, so it was not started.
3. **R2R orchestration = `simple`, ingestion = `r2r`** (no Hatchet, Unstructured or graph services). Both values are supported by the v3.6.5 code (`orchestration.py` lists `hatchet`, `simple`; `ingestion.py` lists `r2r`, `unstructured_local`, `unstructured_api`).
4. **Chatwoot SafeFetch private network enabled (local only):** `SAFE_FETCH_ALLOW_PRIVATE_NETWORK=true`, so AgentBot can call `host.docker.internal`. Without it, Chatwoot rejects the webhook URL. Recorded in `docs/security/threat-model.md`.
5. **Chatwoot account signup via API-only mode and a rails-runner super-admin**, because the first-run onboarding route is gated and returned a redirect. The same `AccountBuilder` code path is used.
