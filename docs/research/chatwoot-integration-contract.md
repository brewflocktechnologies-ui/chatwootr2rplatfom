# Chatwoot integration contract (v4.20.1, verified against the running profile)

Authoritative route/payload summary: `docs/architecture/api-contract.md`. This file records the probes that established it and what is still open.

## Setup state (local profile `saas-chatwoot`)
| Object | Value | How created |
|---|---|---|
| Account | id 4, "Acme Demo" | `AccountBuilder` via `rails runner` (first-run onboarding route is gated) |
| Super admin | `admin@acme-demo.test` (synthetic) | same |
| Website inbox | id 1, "Acme Demo Web", `Channel::WebWidget`, website_url `http://localhost:4300/demo` | `POST /api/v1/accounts/4/inboxes` |
| Team | id 1, "acme support" | `POST /teams` |
| Human agent | id 5, `alex@acme-demo.test`, confirmed, member of inbox 1 and team 1 | `POST /agents`, confirmed via `rails runner`, `POST /inbox_members` |
| AgentBot | id 1, "Acme Demo Support Agent", bot_type `webhook`, outgoing_url `http://host.docker.internal:8099/chatwoot/webhook` (probe recorder) | `POST /agent_bots` |
| Bot ↔ inbox | `POST /api/v1/accounts/4/inboxes/1/set_agent_bot` `{"agent_bot": 1}` → 200 | |

The outgoing_url must be switched to the platform (`http://host.docker.internal:8080/api/webhooks/chatwoot/agent-bot`) when the platform API owns the webhook. Stop the recorder then.

## Probe results

| # | Probe | Result |
|---|---|---|
| P1 | Chatwoot container → host HTTP (`host.docker.internal:8099`) | 200 |
| P2 | Customer message via widget API (`/api/v1/widget/config` then `/api/v1/widget/messages`) | 200; conversation 1, message 7 |
| P3 | AgentBot delivery of the customer message | `message_created`, `message_type: incoming`, `private: false`, signed (see below) |
| P4 | Signature check | `X-Chatwoot-Signature` = `sha256=HMAC_SHA256(bot_secret, "<X-Chatwoot-Timestamp>.<raw body>")`. **Valid** with the stored bot secret. Body-only HMAC does **not** match. |
| P5 | Bot reply via API | `POST .../conversations/1/messages` with bot `api_access_token` → 200; sender `{type: "agent_bot", name: "Acme Demo Support Agent"}` |
| P6 | Bot's own reply produces webhooks | yes (`message_type: outgoing`). Must be ignored for AI generation. |
| P7 | Status toggles | `toggle_status` `pending` and `open` → 200 with the bot token; admin token also works |
| P8 | Bot profile read | 401 "not authorized for bots" (expected; bots cannot read profile) |
| P9 | Conversation status on creation | `open` (not `pending`). Platform owns eligibility; see the lifecycle doc. |
| P10 | SafeFetch | Blocks private hosts by default: `Invalid webhook URL ... Hostname 'host.docker.internal' has no public...`. Fixed for local only with `SAFE_FETCH_ALLOW_PRIVATE_NETWORK=true`. |

Captured payloads (raw, git-ignored): `artifacts/probes/chatwoot-webhooks/`. The sanitized fixture for tests is produced by the backend work, without contact PII or secrets.

## Still open (must be verified before the golden flow)
1. **Private note** request shape (`POST .../messages` with `private: true`, sender identity, response). Not yet probed.
2. **Human reply events** (agent `outgoing` `private: false`, sender type `user`): need a real human reply captured via the UI.
3. **`conversation_opened` / `conversation_updated`** payloads when the platform sets `open` (for handoff event handling).
4. Whether a bot token can read a conversation (`GET .../conversations/1` with bot token). The admin token works.
5. Human agent sign-in in the Chatwoot dashboard UI (API sign-in works: `POST /auth/sign_in` → 200 with access-token headers).
