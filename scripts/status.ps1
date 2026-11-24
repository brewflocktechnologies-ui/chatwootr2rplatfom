# Reports health of every local service. Read-only. Usage: pwsh scripts/status.ps1
. (Join-Path $PSScriptRoot 'common.ps1')

$checks = @(
    @{ Name = 'Docker engine';      Ok = { Test-DockerEngine } }
    @{ Name = 'Chatwoot (v4.20.1)'; Url = 'http://127.0.0.1:3100/api';                   Expect = 200 }
    @{ Name = 'R2R (3.6.5)';        Url = 'http://127.0.0.1:7272/v3/health';             Expect = 200 }
    @{ Name = 'Ollama';             Url = 'http://127.0.0.1:11434/api/tags';             Expect = 200 }
    @{ Name = 'Platform API';       Url = 'http://127.0.0.1:8080/actuator/health/readiness'; Expect = 200 }
    @{ Name = 'Admin/demo web';     Url = 'http://127.0.0.1:4300/api/health';            Expect = 200 }
)

$failed = 0
foreach ($c in $checks) {
    if ($c.ContainsKey('Ok')) {
        $ok = & $c.Ok
        $detail = if ($ok) { 'reachable' } else { 'NOT reachable' }
    } else {
        $code = Get-HttpStatus $c.Url
        $ok = ($code -eq $c.Expect)
        $detail = "HTTP $code"
    }
    if (-not $ok) { $failed++ }
    $mark = if ($ok) { 'OK  ' } else { 'FAIL' }
    Write-Host ("{0} {1,-20} {2}" -f $mark, $c.Name, $detail)
}

Write-Step 'Compose projects (this repo only)'
foreach ($p in $script:Projects.Keys) {
    $dir = $script:Projects[$p]
    if (Test-Path $dir) { Push-Location $dir; try { & docker compose ps --format 'table {{.Service}}\t{{.Status}}' 2>$null } finally { Pop-Location } }
}

if ($failed -gt 0) { Write-Host "`n$failed check(s) failed." -ForegroundColor Yellow; exit 1 }
Write-Host "`nAll checks passed." -ForegroundColor Green
