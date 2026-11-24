# Port map (loopback only)

| Port (host) | Service | Owner | Bind | Notes |
|---|---|---|---|---|
| 3100 | Chatwoot v4.20.1 (`rails`) | `saas-chatwoot` | 127.0.0.1 | 3000 is kept free for the user's own Chatwoot stack. Container port 3000. |
| 7272 | R2R 3.6.5 | `saas-r2r` | 127.0.0.1 | R2R admin is API-only here; the R2R dashboard is not started. |
| 11434 | Ollama 0.40.17 | `saas-ollama` | 127.0.0.1 | Models: `llama3.2:3b`, `mxbai-embed-large`. |
| 8080 | Platform API (Spring Boot) | `saas-platform` | 127.0.0.1 | Container port 8080. Health: `/actuator/health/readiness`. |
| 4300 | Admin + demo web (Next.js) | `saas-platform` | 127.0.0.1 | Container port 3000. |
| — | Chatwoot Postgres, Redis, Sidekiq | `saas-chatwoot` | none | Not published to the host. |
| — | R2R Postgres | `saas-r2r` | none | Not published to the host. |
| — | Platform Postgres | `saas-platform` | none | Not published to the host. |
| 8099 | Research recorder (`scripts/research/chatwoot-webhook-recorder.mjs`) | host process | 127.0.0.1 | Temporary probe for capturing AgentBot payloads. Stop it once the platform API owns the webhook URL. |

Container-to-host: containers use `host.docker.internal` (Docker Desktop host gateway) for Chatwoot `:3100`, R2R `:7272` and Ollama `:11434`. Verified from the Chatwoot container.

Conflicts: none unresolved. Ports 3000 (user's Chatwoot), 5432 and 6379 (user's Chatwoot Postgres/Redis) are deliberately not used by this project.
