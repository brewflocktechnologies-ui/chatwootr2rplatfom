# R2R 3.6.5 risk review (MVP gate, brief §2)

Scope: exact image `sciphiai/r2r@sha256:e7caf41aba36db5561a2579eeb3e4763c907ae4d4b241712a33e783fa381aa8a` (tag 3.6.5), source tag `v3.6.5`.
Method: upstream issues read via GitHub API on 24 Nov 2026; affected code read at tag `v3.6.5`; behaviour probed on the local profile.
Public reports are **not** proof that every report is confirmed. Each row states what was actually verified.

## Upstream issue status (all five are OPEN at review time)

| Issue | Summary (from issue) | Applies to our use? | Evidence / mitigation | Residual |
|---|---|---|---|---|
| [#2311](https://github.com/SciPhi-AI/R2R/issues/2311) | Hard-coded JWT signing key fallback allows impersonation when auth is on | **Conditional, mitigated** | Read at `v3.6.5`: `py/core/providers/crypto/bcrypt.py` ("No fallback defaults: fail if not provided") and `nacl.py` (falls back to a published key when `R2R_SECRET_KEY` is unset). We set `[crypto] provider = "bcrypt"` **and** a 96-hex `R2R_SECRET_KEY`. | Residual: the unused `DEFAULT_BCRYPT_SECRET_KEY` constant remains in source. Re-check if the crypto provider is ever changed. |
| [#2292](https://github.com/SciPhi-AI/R2R/issues/2292) | Cross-user conversation IDOR | Not used | The platform never calls R2R conversation endpoints. Tenant document and collection isolation was tested (see below). | Conversation IDOR itself is **not tested**. Keep the platform off conversation endpoints. |
| [#2290](https://github.com/SciPhi-AI/R2R/issues/2290) | SQL injection in vector index create/delete | Not called | Platform never calls index endpoints. R2R port is loopback only. | Not exercised. Do not expose R2R beyond loopback. Upgrade or patch required before any non-local use. |
| [#2309](https://github.com/SciPhi-AI/R2R/issues/2309) | Stacked SQL injection via unquoted index name | Not called | Same as #2290. | Same as #2290. |
| [#2297](https://github.com/SciPhi-AI/R2R/issues/2297) | Request for a private disclosure channel (unpatched auth issue) | Informational | Issue body only asks how to report; the upstream security advisory address reportedly bounces (issue #2292 comment). | No technical content to verify. Use GitHub security advisories if you report upstream. |

## Verified hardening on the local profile

| Control | Test | Result |
|---|---|---|
| Authentication enforced | unauthenticated `GET /v3/collections` | 401 |
| Unauthenticated registration refused | `POST /v3/users` without token | 401 |
| Default admin password removed | login with `change_me_immediately` (the shipped default in `r2r_with_auth.toml`) | 401 (generated admin password in use) |
| Secret key set, bcrypt provider | R2R started with `R2R_SECRET_KEY` set; config pins `[crypto] provider = "bcrypt"` | started OK |
| Loopback only | `netstat` for 7272 and LAN IP `192.168.0.113:7272` | `127.0.0.1:7272` only; LAN unreachable |
| No hosted provider keys | `infra/r2r/.env` contains no OpenAI/Anthropic/etc keys; config uses `ollama/*` and `litellm` to `host.docker.internal:11434` | local only |
| Web search disabled | `[agent] rag_tools = ["search_file_knowledge"]`, `include_web_search: false` on RAG calls | enforced by config and request |
| Platform identity is not admin | platform will use the tenant service user only (created by bootstrap) | to be verified by backend tests |

## Tenant isolation tests (executed 24 Nov 2026)

Tenant owner `acme-demo-svc@acme-demo.example.com`, collection `acme-demo-knowledge`, document "Acme Demo Support Policy (FICTIONAL)".

| Test | Expected | Observed |
|---|---|---|
| Tenant owner search "standard delivery time" | relevant policy chunk returned | 2 hits, top score 0.649, correct document |
| Unrelated user search | no tenant chunks | 0 hits (HTTP 200) |
| Unrelated user `GET /v3/documents/{id}` | denied | 404 |
| Unrelated user `GET /v3/collections/{id}` | denied | 403 |
| Unrelated user search with explicit `collection_ids` filter | no tenant chunks | **HTTP 500**, no chunks returned. Not a data leak, but an error-handling defect to report upstream. |

Conclusion: isolation holds for the tested read paths. R2R owner scoping is the primary boundary. The platform will still send only the tenant collection id from server config and never accept a collection id from the browser (bootstrap §4.4).

## Maintenance gate

- The latest R2R GitHub release is `v3.6.5` (2025-06-06, about 17 months old at review). No security release exists for these open issues.
- **Decision for the MVP:** proceed on the local-only profile, with synthetic data only. Do not load personal, customer or confidential documents. Re-review if R2R publishes a fix.
- Blocker check (brief §2 item 8): none of the open issues is confirmed to break tenant isolation on this tag when mitigated as above. The filtered-search 500 is recorded and does not expose data. **No unmitigated authentication or isolation blocker was found.**

## Items not yet tested (must be closed before any non-demo use)
1. Full negative test for #2292 (conversation IDOR) if conversation endpoints are ever used.
2. Whether non-admin users can reach index create/delete endpoints (#2290/#2309). Test only on the local profile, never against shared infrastructure.
3. Prompt injection inside an uploaded document (SEC-04). Planned in the platform test suite.
