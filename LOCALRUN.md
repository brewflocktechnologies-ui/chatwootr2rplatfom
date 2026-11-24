# LOCALRUN: run and test the Acme Demo support platform on your PC

This guide walks you through starting the local platform and testing every main flow by hand:

1. Knowledge base (upload and status)
2. Customer, AI-first answers with sources
3. Explicit handoff to a human, and out-of-policy handoff
4. Human agent working in Chatwoot, and the customer seeing the reply
5. AI staying silent while a human owns the conversation
6. Resuming the AI (explicit only)

Everything runs on your machine. No cloud services and no hosted AI.

> **Status:** this is a demo build. The end-to-end golden-flow test is not fully passed yet (see [Known limits](#known-limits)). Use this guide to try the product; do not treat it as an acceptance sign-off.

---

## 1. Prerequisites

- **Docker Desktop** installed and running (Linux engine). Check the whale icon is steady.
- **Windows PowerShell 5.1** (built into Windows).
- About **8 GB free disk**. The first start downloads AI model weights (about 2.7 GB), so it can take 10 to 20 minutes.
- A **browser** (Chrome, Edge or Firefox).

Find the project folder: `E:\SAAS\PLATFORM`.

---

## 2. Start the platform

Double-click **`start-platform.bat`** in the project folder.

It starts, in order: Ollama (local AI model), Chatwoot (customer widget and human inbox), R2R (knowledge base), then the platform API and the admin/demo web. When it finishes you see a health table. Every row should say `OK`.

Wait until these pages load (if one fails, wait a minute and refresh, because the first start is slow):

| Page | URL |
|---|---|
| Admin console (service health) | http://localhost:4300/admin |
| Knowledge base | http://localhost:4300/admin/knowledge |
| Agent settings (read-only) | http://localhost:4300/admin/agent |
| Customer demo page | http://localhost:4300/demo |
| Chatwoot (human agent inbox) | http://localhost:3100 |

To check the health table at any time, open PowerShell in the project folder and run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status.ps1
```

---

## 3. Stop the platform

Double-click **`stop-platform.bat`**.

This stops the platform API, the admin/demo web and the platform database. **Your data is kept** (it lives in Docker volumes).

To also stop Chatwoot, R2R and Ollama:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\down.ps1 -IncludeUpstream
```

To erase **platform demo data only** (not Chatwoot or R2R data), use the typed-confirmation reset script:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\reset.ps1
```

---

## 4. Logins you need

Credentials are stored locally and are not printed here. Open these files in a text editor to read them:

| Who | Where the login is | Used for |
|---|---|---|
| Human support agent `alex@acme-demo.test` | `infra\chatwoot\.env.secrets` → `HUMAN_AGENT_EMAIL` and `HUMAN_AGENT_PASSWORD` | Chatwoot dashboard (step 5) |
| Chatwoot admin `admin@acme-demo.test` | `infra\chatwoot\.env.secrets` → `CHATWOOT_ADMIN_EMAIL` and `CHATWOOT_ADMIN_PASSWORD` | Chatwoot admin, if needed |
| Platform admin key | `infra\platform\.env` → `PLATFORM_ADMIN_API_KEY` | Only for the PowerShell commands in steps 6 and 8 |

The admin web (`/admin`) does **not** ask for a login. It is for local use only. Do not expose port 4300 on your network.

---

## 5. Test A: Knowledge base

**What you test:** the demo policy document is loaded, visible and marked ready; uploads work and are validated.

1. Open http://localhost:4300/admin/knowledge.
2. Expected: a row for **Acme Demo Support Policy (FICTIONAL)** with status **READY**.
   - If it shows **FAILED**, click **Retry** once and wait. If it still fails, see [Troubleshooting](#troubleshooting).
3. Upload a test file:
   - Create a small text file on your desktop, for example `returns-test.txt`, with a line like: `Returns are accepted within 14 days for unused items in original packaging.`
   - Click **Choose file**, pick it, then upload.
   - Expected: a new row appears with status moving to **READY** within about a minute.
4. Limits to check (these should be refused with a clear message):
   - A file over **2 MiB**.
   - A file type other than `.md`, `.txt` or `.pdf`, for example `.exe` or `.png`.
5. Open http://localhost:4300/admin/agent. Expected: the agent name, model `ollama/llama3.2:3b`, embedding model, knowledge scope and handoff policy (read-only).

Uploaded files are added to the knowledge base the AI searches. Upload only demo or fictional content.

---

## 6. Test B: Customer asks a question (AI-first)

**What you test:** the AI answers from the knowledge base, cites the source, and does not invent facts.

1. Open http://localhost:4300/demo in a **private (incognito) window**.
2. Click the chat launcher, or the **Start Conversation** button if shown, to open the Chatwoot widget.
3. Type: `What is your standard delivery time?` and send.
4. Expected within about 30 seconds: **one** reply from the support bot that says standard delivery is **3–5 business days after dispatch** and ends with a line `Source: Acme Demo Support Policy (FICTIONAL)`.
   - **Known limit:** the reply may not yet mention that dispatch takes 1–2 business days. This is a known gap, not a failure of this test.
5. Ask an out-of-policy question, for example: `Do you sell gift cards?`
   - Expected: the AI does **not** make up an answer. It hands the chat to a human. You should see a message that a human will help, and the conversation is handed to the agent inbox (see Test C).

---

## 7. Test C: Customer asks for a human (handoff)

**What you test:** an explicit request for a person hands the chat to the human inbox immediately, and the AI stops.

1. In the same customer widget window (or a new private window), start a new conversation.
2. Type: `I need to speak to a human agent, please.`
3. Expected:
   - The bot says a human will take over (no policy answer).
   - The conversation moves to the human inbox (open status) in Chatwoot.
   - A **private note** is added for the agent. Customers do not see private notes.
4. The private note should contain: the customer's intent, what the AI checked, the policy sources looked at (or "none"), the reason for handoff, and a suggested next step. It must not contain prompts or secrets.

Handoff triggers, for reference:

- Explicit words such as `human`, `person`, `agent`, `representative`, `speak to someone`, `live agent`.
- No answer found in the knowledge base.
- A system error while looking up the answer (the platform fails safe and does not guess).

---

## 8. Test D: Human agent flow in Chatwoot

**What you test:** a human agent sees the handed-off chat and replies, and the customer receives the reply in the same widget.

1. Open http://localhost:3100 in a **normal** browser window (not the private window used for the customer).
2. Sign in as `alex@acme-demo.test` with the password from `infra\chatwoot\.env.secrets` (see section 4).
   - If you land on a setup page, reload `http://localhost:3100/app/login` and sign in again.
3. Open **Conversations** (inbox: **Acme Demo Web**). Expected: the conversation from Test C is listed as open.
4. Open it. Expected: the **private note** from the AI (visible to agents only, grey background).
5. Type a reply, for example: `Hello, I'm Alex from support. I'll review this and help you.` and send it.
6. Switch to the **customer private window** (the widget from Test C). Expected: the reply appears in the same conversation, from the agent, within a few seconds.

---

## 9. Test E: AI stays silent after handoff

**What you test:** once a human owns the chat, the AI sends nothing further.

1. In the customer widget (same conversation as Test D), type: `Can you also check this for me?`
2. Expected: **no bot reply**. The message appears in the Chatwoot conversation for the human agent to see.
3. Type one more message: `Thanks.` Expected: still no bot reply.
4. Check the state in PowerShell (replace `123` with the conversation id shown in Chatwoot's URL):

```powershell
$k = (Select-String -Path infra\platform\.env -Pattern '^PLATFORM_ADMIN_API_KEY=').Line.Split('=',2)[1]
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/conversations/123 -Headers @{ 'X-Admin-Api-Key' = $k }
```

Expected `state`: `HUMAN_ACTIVE`.

---

## 10. Test F: Resume the AI (explicit, audited)

**What you test:** the AI does not come back automatically. Only an explicit operator action resumes it.

1. Confirm the conversation is still `HUMAN_ACTIVE` (Test E, step 4).
2. Resume it, replacing `123` with the conversation id:

```powershell
$k = (Select-String -Path infra\platform\.env -Pattern '^PLATFORM_ADMIN_API_KEY=').Line.Split('=',2)[1]
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/admin/conversations/123/resume -Headers @{ 'X-Admin-Api-Key' = $k }
```

3. Expected: `state` is `AI_ACTIVE` and the conversation returns to the bot queue in Chatwoot (status pending).
4. Send a policy question from the customer widget. Expected: the bot answers again.

Do not resume a conversation while a human is still replying to the customer.

---

## 11. Check what happened (optional)

Recent webhook events and their outcomes (sent, duplicate, not AI-active, and so on):

```powershell
$k = (Select-String -Path infra\platform\.env -Pattern '^PLATFORM_ADMIN_API_KEY=').Line.Split('=',2)[1]
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/webhooks -Headers @{ 'X-Admin-Api-Key' = $k }
```

Platform log (the last 200 lines, follow live):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\logs.ps1 -Project platform -Service platform-api
```

Stop following with `Ctrl+C`.

---

## 12. Troubleshooting

| Symptom | What to do |
|---|---|
| `start-platform.bat` says Docker is not running | Open Docker Desktop, wait until it is running, then run the bat file again. |
| A page at `localhost:4300` will not load | Wait 1–2 minutes after start; the first start is slow. Check `scripts\status.ps1`. |
| Knowledge row shows **FAILED** | Click **Retry**. If it fails again, run `scripts\logs.ps1 -Project platform -Service platform-api` and look for errors mentioning R2R. |
| Widget does not appear in `/demo` | Check the status table says Chatwoot is OK, then hard-refresh (Ctrl+F5). Use a private window. |
| Dashboard login at `localhost:3100` goes to a setup page | Reload `http://localhost:3100/app/login` and sign in again. |
| Bot does not answer a question | Wait 30 seconds. Check Test C: if the conversation is already with a human, the AI is correctly silent. |
| Model is slow on the first answer | The model loads on first use; the next answer is faster. |
| Anything else | Run `scripts\doctor.ps1` and `scripts\status.ps1`, then share the output. |

---

## Known limits

- **Golden flow not yet fully passed.** The end-to-end acceptance test is still being rerun. Earlier runs passed the handoff, the no-AI-after-handoff check and the restart check, but not the full human reply through the dashboard.
- **Answer wording:** the AI reply may omit the "dispatch takes 1–2 business days" detail.
- **Local only:** all services listen only on your own machine (`127.0.0.1`). Nothing is exposed to your network.
- **Demo data only:** the policy is fictional. Do not upload real customer or confidential documents.
- **R2R is an older release** (3.6.5) with open upstream security issues. Read `docs\security\r2r-risk-review.md` before any use beyond this demo.
- **Setup is already done** on this PC. You do not need to configure Chatwoot again to run these tests.

- **Human request during a reply:** if a customer asks for a person at the exact moment an answer is being posted, that one answer can still appear before the handoff. The next messages are handled correctly.
