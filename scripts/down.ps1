# Stops this repo's containers. Keeps every volume (no `-v`). Upstream projects are stopped only if you pass -IncludeUpstream.
# Usage: pwsh scripts/down.ps1 [-IncludeUpstream]
param([switch]$IncludeUpstream)
. (Join-Path $PSScriptRoot 'common.ps1')

Invoke-Compose platform @('stop')
if ($IncludeUpstream) {
    foreach ($p in 'r2r', 'chatwoot', 'ollama') { Invoke-Compose $p @('stop') }
} else {
    Write-Host 'Upstream Chatwoot/R2R/Ollama left running (use -IncludeUpstream to stop them).' -ForegroundColor Yellow
}
Write-Host 'Stopped. Volumes were preserved.' -ForegroundColor Green
