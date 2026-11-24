# R2R integration contract (3.6.5, verified against the running profile)

Authoritative summary: `docs/architecture/api-contract.md`. Security review: `docs/security/r2r-risk-review.md`.

## Profile
- Image `sciphiai/r2r@sha256:e7caf41aba36db5561a2579eeb3e4763c907ae4d4b241712a33e783fa381aa8a`, compose project `saas-r2r`, host `127.0.0.1:7272`.
- Config: `infra/r2r/config/r2r.local.toml` (generated from `r2r.template.toml`, git-ignored). Mounted at `/app/config/r2r.toml`, `R2R_CONFIG_PATH` set.
- Env: `R2R_SECRET_KEY` (96 hex), `R2R_POSTGRES_*`, `R2R_PROJECT_NAME=acme_demo_r2r` (must be a SQL identifier: hyphens crash startup), `OLLAMA_API_BASE=http://host.docker.internal:11434`.
- Upstream compose at the tag uses `sciphiai/r2r:latest`, Hatchet, Unstructured and graph services. Our profile uses pinned image plus `orchestration=simple` and `ingestion=r2r`.

## Probes (executed)
| # | Probe | Result |
|---|---|---|
| R1 | `GET /v3/health` | `{"results":{"message":"ok"}}` |
| R2 | Admin login `POST /v3/users/login` (form) | 200, `results.access_token.token` |
| R3 | Unauthenticated `GET /v3/collections` | 401 |
| R4 | Register tenant identity `POST /v3/users` `{email,password}` | 200 for `acme-demo-svc@acme-demo.example.com`; `.local` TLD is rejected (422) |
| R5 | Tenant login | 200 |
| R6 | `POST /v3/collections` `{name,description}` | 200, collection `89af8bb6-fcee-46e9-a5da-3f01969b0323` |
| R7 | `POST /v3/documents` multipart (`file`, `metadata` JSON string, `collection_ids` JSON array string, `ingestion_mode=fast`) | 202, document `1a3e2ef2-25e1-5fbf-af88-360ac28049a8`. Needed `OLLAMA_API_BASE` (first attempt failed on embeddings). |
| R8 | `GET /v3/documents/{id}` | `ingestion_status: success` |
| R9 | `POST /v3/retrieval/search` `{query, search_mode:"basic", search_settings:{limit}}` | 200; 2 hits, top score 0.649, text includes title and content |
| R10 | `POST /v3/retrieval/rag` with `rag_generation_config.model = "ollama/llama3.2:3b"`, `include_web_search:false` | 200; grounded answer ("3–5 business days after dispatch") with 2 citations; generated locally |
| R11 | Negative isolation (unrelated user) | 0 hits; doc 404; collection 403; filtered search 500 (logged) |

## Policy for the platform
- Use only the tenant service identity. Never the admin token. Never a collection id from the browser.
- Evidence gate: at least one chunk and top score ≥ `AGENT_EVIDENCE_MIN_SCORE` (0.45 initial setting, to be tuned in the evaluation dataset). Otherwise hand off.
- Any non-200 or timeout from R2R → fail closed (no customer answer), bounded retry, then FAILED diagnostic.
- Document ingestion of demo policy only. No personal or customer data.

## Open items
- Evaluation dataset for the evidence threshold (`docs/testing/evaluation-dataset.md`): not built yet.
- Confirm R2R retrieval chunk fields used by the platform (`text`, `score`, `document_id`, `metadata.title`) from the backend's own tests.
