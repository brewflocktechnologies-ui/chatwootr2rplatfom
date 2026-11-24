# Conversation lifecycle (authoritative for the platform)

Platform-owned state lives in `conversation_control` (one row per Chatwoot conversation). Chatwoot holds messages, status and assignment. The AI never sends a customer message unless the platform state says `AI_ACTIVE` **and** the Chatwoot conversation is still in an eligible state at the moment of sending.

## States
- `AI_ACTIVE`: the AI may answer customer messages (evidence permitting).
- `HANDOFF_PENDING`: a handoff has started. No AI turn may send. Waiting for Chatwoot to confirm the private note and the `open` transition.
- `HUMAN_ACTIVE`: a human owns the conversation. Customer messages are recorded only. No R2R call for a customer reply, no AI reply.
- `RESOLVED`: closed by policy. Reopen is explicit only.

## Transitions
| From | To | Trigger | Required side effects |
|---|---|---|---|
| (new) | `AI_ACTIVE` | first customer `message_created` for the conversation | create row, version 1 |
| `AI_ACTIVE` | `AI_ACTIVE` | answer sent after all send guards pass | version +1, `agent_execution` recorded |
| `AI_ACTIVE` | `HANDOFF_PENDING` | explicit human request (deterministic match), or no usable evidence, or R2R/Chatwoot terminal failure on an answer | version +1 before any further AI work |
| `HANDOFF_PENDING` | `HUMAN_ACTIVE` | Chatwoot confirms `open` **and** the private note exists | `handoff_record` status `CONFIRMED` |
| `HANDOFF_PENDING` | `HANDOFF_PENDING` | Chatwoot timeout or error | bounded retry; no second note if the first one exists |
| `HUMAN_ACTIVE` | `HUMAN_ACTIVE` | any customer or human message | customer messages recorded, no AI reply |
| `HUMAN_ACTIVE` | `AI_ACTIVE` | explicit `POST /api/admin/conversations/{id}/resume` by an operator | audited; Chatwoot set to `pending`; never automatic |
| any | `RESOLVED` | Chatwoot `resolved` (policy) | no AI sends after this |

## Send guards (all must pass immediately before every customer-facing AI message)
1. Local state is `AI_ACTIVE` and the `version` read at the start of the turn is unchanged.
2. The turn's execution has not been cancelled (handoff or human reply after start → discard the late R2R/LLM result).
3. Chatwoot conversation status is re-read. The send proceeds only if it is still in the AI-eligible state the platform set (`pending`). If the status is `open` because the platform set it for a handoff, the send is blocked. If the status cannot be read, do not send.
4. The message is never sent twice: outgoing sends carry a platform idempotency key recorded before the call. A retry after a timeout checks the recorded key first.

## Human messages are authoritative
- Outgoing messages from humans (`message_type: outgoing`, sender type user) never start an AI turn.
- A human reply moves the conversation to `HUMAN_ACTIVE` if it was not already there.
- The AI never follows a human message with an unsolicited answer.

## Deterministic human-request match
Case-insensitive, word-boundary match on: `human`, `person`, `agent`, `representative`, `real person`, `speak to someone`, `talk to someone`, `live agent`. Runs before RAG. An LLM may supplement but never replaces this rule.

## Webhook ordering and concurrency
- Dedupe key in `webhook_inbox`: `event + ":" + message.id` (unique). A redelivered webhook is acknowledged and not re-executed.
- Turns for one conversation run serially: the worker takes a row lock (`SELECT ... FOR UPDATE SKIP LOCKED` on `conversation_control`) for the duration of a turn.
- Bot's own outgoing messages and private notes are recorded but never trigger a turn.

## Local validation evidence (24 Nov 2026)
- Chatwoot AgentBot payload signature verified (`docs/research/chatwoot-integration-contract.md`, probe P4).
- Status transitions `pending`/`open` verified with the bot token (probe P7).
- Chatwoot creates new conversations as `open` in this inbox (probe P9). Therefore the platform must set `pending` for AI-owned conversations before the first AI send. This is an open verification item for the golden flow.
