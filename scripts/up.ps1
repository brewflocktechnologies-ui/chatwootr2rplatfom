# Starts the local stack. Respects already-running upstream services and never removes containers or volumes.
# Usage: pwsh scripts/up.ps1 [-SkipPlatform]
param([switch]$SkipPlatform)
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not (Test-DockerEngine)) { throw 'Docker engine is not running. Start Docker Desktop and retry.' }

Write-Step 'Ollama (local model runtime)'
Invoke-Compose ollama @('up', '-d')
& (Join-Path $PSScriptRoot 'model-pull.ps1')

Write-Step 'Chatwoot (pinned v4.20.1, managed-local)'
Invoke-Compose chatwoot @('up', '-d')

Write-Step 'R2R (pinned 3.6.5, managed-local)'
Invoke-Compose r2r @('up', '-d')

if (-not $SkipPlatform) {
    Write-Step 'Platform (db, API, admin/demo web)'
    Invoke-Compose platform @('up', '-d', '--build')
}

Write-Step 'Waiting for health'
& (Join-Path $PSScriptRoot 'status.ps1')
