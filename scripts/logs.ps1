# Follow logs. Usage: pwsh scripts/logs.ps1 -Project platform -Service platform-api
param(
    [Parameter(Mandatory)][ValidateSet('chatwoot', 'r2r', 'ollama', 'platform')][string]$Project,
    [string]$Service,
    [int]$Tail = 200
)
. (Join-Path $PSScriptRoot 'common.ps1')
$args = @('logs', '--tail', "$Tail", '-f')
if ($Service) { $args += $Service }
Invoke-Compose $Project $args
