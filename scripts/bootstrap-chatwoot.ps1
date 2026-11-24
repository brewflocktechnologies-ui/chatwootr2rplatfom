# VERIFY-ONLY check of the one-time Chatwoot setup for the acme-demo tenant. Writes nothing.
# Creating the account/inbox/bot/team/agent was a one-time step (see README and docs/research/chatwoot-integration-contract.md).
. (Join-Path $PSScriptRoot 'common.ps1')

$secretsPath = Join-Path $script:RepoRoot 'infra\chatwoot\.env.secrets'
if (-not (Test-Path $secretsPath)) { throw "Missing $secretsPath (local secrets). See README." }
$cfg = @{}
Get-Content $secretsPath | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object { $k, $v = $_ -split '=', 2; $cfg[$k] = $v }

$base = 'http://127.0.0.1:3100/api/v1/accounts/{0}' -f $cfg['CHATWOOT_ACCOUNT_ID']
$headers = @{ api_access_token = $cfg['CHATWOOT_API_TOKEN'] }
$fail = 0
function Check([bool]$Ok, [string]$Label) {
    if (-not $Ok) { $script:fail++ }
    Write-Host ("{0} {1}" -f ($(if ($Ok) { 'OK  ' } else { 'FAIL' })), $Label)
}

Write-Step 'Chatwoot tenant wiring (acme-demo)'
try { $inbox = Invoke-RestMethod -Uri "$base/inboxes/$($cfg['CHATWOOT_INBOX_ID'])" -Headers $headers -TimeoutSec 10 }
catch { Write-Host "Chatwoot not reachable: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
Check ($inbox.channel_type -eq 'Channel::WebWidget') "inbox $($inbox.id) is a website widget ($($inbox.name))"
Check ($inbox.website_token -eq $cfg['CHATWOOT_WEBSITE_TOKEN']) 'website token matches platform config'

$bots = Invoke-RestMethod -Uri "$base/agent_bots" -Headers $headers -TimeoutSec 10
$bot = $bots | Where-Object { $_.id -eq [int]$cfg['CHATWOOT_AGENT_BOT_ID'] }
Check ([bool]$bot) "AgentBot $($cfg['CHATWOOT_AGENT_BOT_ID']) exists"
Check ($bot -and $bot.outgoing_url -like '*/api/webhooks/chatwoot/agent-bot*') 'AgentBot outgoing_url points at the platform webhook (set after the platform API is up)'

$members = Invoke-RestMethod -Uri "$base/inbox_members/$($cfg['CHATWOOT_INBOX_ID'])" -Headers $headers -TimeoutSec 10
Check (@($members.payload).Count -ge 1) 'at least one human agent is a member of the inbox'

$team = Invoke-RestMethod -Uri "$base/teams/$($cfg['CHATWOOT_TEAM_ID'])" -Headers $headers -TimeoutSec 10
Check ([bool]$team) "team $($cfg['CHATWOOT_TEAM_ID']) exists"

if ($fail -gt 0) { Write-Host "`n$fail check(s) failed. Fix the Chatwoot setup, then re-run." -ForegroundColor Yellow; exit 1 }
Write-Host "`nChatwoot setup verified." -ForegroundColor Green
