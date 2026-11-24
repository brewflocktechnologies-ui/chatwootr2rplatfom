# HO-08 formal rerun (main stack, 2026-11-24)

Verdict: **FAIL** for the formal criterion (2 of 3 trials). Trial 1 passed and exercised the discard path. Trials 2 and 3 did not establish the in-flight condition, so they say nothing about discard.

## Setup
- Main stack only. AgentBot `outgoing_url` was not changed. GET after the run: `http://host.docker.internal:8080/api/webhooks/chatwoot/agent-bot`.
- No fault injection. The proxy setup would have needed the AgentBot pointed at the throwaway platform, so it was not used.
- Method (widget timing, from the lead's instruction): fresh widget conversation, "What is your standard delivery time?", then "I need a human agent, please." about 300 ms later (client-side gap measured 320 ms).
- Spec: `apps/e2e/tests/ho08.spec.ts`. Ledger: `artifacts/e2e/api/ledger-ho08.json`. Trial records: `artifacts/e2e/api/ho08-trials.json`. Screenshots: `artifacts/e2e/ho08/trial-{1,2,3}-widget.png`. Container log: `artifacts/e2e/ho08/run-container.log`.
- Assertions per trial: zero bot answers after the human request; the question not answered (discarded or not sent); one private note; final state HUMAN_ACTIVE.

## Results

| Trial | Conv | Question msg | Human msg | Question outcome | Bot answer | Human outcome | Notes | State | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 83 | 498 | 501 | DISCARDED | none | HANDOFF_COMPLETED | 1 | HUMAN_ACTIVE | PASS |
| 2 | 84 | 504 | 508 | SENT | msg 507, 12:09:24.9Z | HANDOFF_COMPLETED | 1 | HUMAN_ACTIVE | FAIL (precondition not met) |
| 3 | 85 | 511 | 515 | SENT | msg 514, 12:10:15.3Z | HANDOFF_COMPLETED | 1 | HUMAN_ACTIVE | FAIL (precondition not met) |

Trials 2 and 3: the bot answer was posted at 12:09:24.9Z and 12:10:15.3Z. The human request was created in Chatwoot at 12:09:26Z and 12:10:16Z, i.e. after the answer. The answer finished about 1 s after the question, so no turn was in flight when the human request arrived. The answer was sent before the human request existed, so the human request was not in flight when the answer went out. The platform log shows `reply sent sources=1` before the human `webhook ... status=queued` line for both conversations.

## Interpretation
- The discard path is exercised only in trial 1 (`DISCARDED`), and that trial passed every assertion.
- Trials 2 and 3 fail the "question not answered" assertion. That is a timing artefact of the 300 ms method: the widget took about 2 s to create the human message in Chatwoot, while the answer came back in about 1 s. It is not evidence of a discard defect, and it is not a valid pass either.
- WebhookIntake handled the human request at intake in all three trials (`HANDOFF_COMPLETED`, one note, HUMAN_ACTIVE).

## Residual window (known limit, documented)
A Chatwoot POST that is already in flight when the handoff starts can still reach the customer. Trials 2 and 3 are an example of a reply that landed before the handoff, which is the same class of window.

## What would give a valid deterministic HO-08
Holding the in-flight turn for a known time needs a fault at R2R or Chatwoot on the platform that is serving the webhooks. That means either the throwaway platform with the AgentBot URL switched (the lead said not to change it), or a delay configured on the main platform (infra change, not permitted). Without one of those, the formal HO-08 criterion cannot be exercised deterministically on the main stack. The earlier deterministic run (fault suite, conversation 77, RAG delayed 20 s, answer SENT and a human request handed off afterwards) is still the evidence for the ordering behaviour in `docs/testing/fault-injection-report.md`, but it ran before WebhookIntake.
