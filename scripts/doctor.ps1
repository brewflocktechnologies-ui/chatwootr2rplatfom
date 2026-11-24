# Preflight for the developer machine. Read-only: never stops, prunes or modifies existing containers or volumes.
. (Join-Path $PSScriptRoot 'common.ps1')

$problems = 0
function Report([bool]$Ok, [string]$Label, [string]$Detail) {
    if (-not $Ok) { $script:problems++ }
    Write-Host ("{0} {1,-28} {2}" -f ($(if ($Ok) { 'OK  ' } else { 'WARN' })), $Label, $Detail)
}

Write-Step 'Docker Desktop'
$engine = Test-DockerEngine
Report $engine 'Docker engine' ($(if ($engine) { 'running' } else { 'NOT running: start Docker Desktop' }))
if ($engine) {
    $compose = (& docker compose version --short 2>$null)
    Report ($LASTEXITCODE -eq 0) 'Compose v2' "$compose"
}

Write-Step 'Toolchain (Node/pnpm run in a pinned container; see README)'
$jdk = 'C:\Program Files\Java\jdk-25.0.3\bin\java.exe'
Report (Test-Path $jdk) 'JDK 25 (host, for builds)' ($(if (Test-Path $jdk) { 'found' } else { 'missing: install JDK 25' }))
if ($engine) {
    $node = (& docker run --rm node:26.13.1-bookworm-slim node -v 2>$null)
    Report ($node -eq 'v26.13.1') 'Node 26.13.1 (container)' "$node"
}

Write-Step 'Ports (loopback)'
foreach ($p in 3100, 7272, 11434, 8080, 4300) {
    $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { Report $true "port $p" 'free'; continue }
    # Docker Desktop publishes container ports through its own processes; those are ours, not a conflict.
    $owner = (Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue).ProcessName
    $dockerOwned = ($owner -match 'docker|vpnkit')
    $detail = if ($dockerOwned) { "published by Docker Desktop ($owner)" } else { "held by '$owner' (not Docker: check before starting)" }
    Report $dockerOwned "port $p" $detail
}

Write-Step 'Local secrets present (values not read)'
foreach ($f in 'infra\chatwoot\.env.secrets', 'infra\r2r\.env', 'infra\platform\.env') {
    $path = Join-Path $script:RepoRoot $f
    Report (Test-Path $path) $f ($(if (Test-Path $path) { 'present' } else { 'missing: run the bootstrap steps in README' }))
}

if ($problems -gt 0) { Write-Host "`n$problems item(s) need attention." -ForegroundColor Yellow; exit 1 }
Write-Host "`nDoctor: all checks passed." -ForegroundColor Green
