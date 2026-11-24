# Local machine inventory (Phase 0, 24 Nov 2026)

Read-only inspection. Nothing was stopped, pruned, removed or re-created that belonged to the user.

## Host
- Windows 11 Home (10.0.26300), 23.7 GB RAM, free disk: C 207.7 GB, D 175.8 GB, E 135.5 GB.
- GPU: NVIDIA GeForce RTX 5060 Laptop GPU (8151 MiB) and Intel UHD. Docker Desktop passes the NVIDIA GPU to containers (Ollama reported 100% GPU).
- Git 2.54.0.windows.1 available. Project folder is **not** a git repository yet.

## Docker Desktop
- Engine 29.9.2 running (started 24 Nov 2026 09:59). Context `desktop-linux`. Compose v5.6.1.
- Pre-existing containers (all **exited**, left untouched): `chat-platform-*` (compose project from `D:\BYTEFLOCK\saasplatform`), `allocation-postgres`, `sonarqube`, `oracle-free`. No running Chatwoot or R2R containers existed.
- Pre-existing volumes (left untouched): `chatwoot_postgres_data`, `chatwoot_redis_data`, `chatwoot_storage_data` (the user's Chatwoot data, from `E:\SAAS\CHATWOOT`), plus many unrelated volumes.
- Networks: `chat-platform_default`, `chatplatform-infrastructure_default`, `chatplatform-observability_default`, `chatplatform-sonarqube_default`, `infra_default`, bridge/host/none.

## Other user projects seen
- `E:\SAAS\CHATWOOT`: Chatwoot compose with `chatwoot/chatwoot:latest`, host ports `127.0.0.1:3000`, `5432`, `6379`, and a `.env` with live-looking configuration (values not recorded here). Not started, to avoid altering its data.
- `E:\SAAS\PLATFORM`: this project (only `bootstrap.md` existed at the start).

## Toolchain
- JDK: `java -version` → 27 (default on PATH). JDK 25.0.3 installed at `C:\Program Files\Java\jdk-25.0.3` (used for builds). Also jdk-17, jdk-21.0.11, jdk-11.
- Node 24.18.0 and npm 12.0.2 on host (not used for project tooling). pnpm 10.11.0 on host. The pnpm 12 shim via npx is broken on this host.
- Maven 3.9.16 at `C:\Program Files\maven\apache-maven-3.9.16\bin\mvn.cmd` (bootstrap only).
- Docker Desktop is the only container runtime used.

## Ports (checked 24 Nov 2026 before start)
- 3000, 3001, 4300, 5432, 6379, 7272, 8080, 11434 were free at check time. Ports 3100, 7272 and 11434 are now published by this project's containers (Docker Desktop). Port 3000 was kept free for the user's own Chatwoot.

## Ollama
- Not installed on the host. Runs as the pinned container `ollama/ollama:0.40.17` (project `saas-ollama`).
