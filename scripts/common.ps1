# Shared helpers for the local PowerShell workflow. Dot-source this file. Never print secret values.
$ErrorActionPreference = 'Stop'
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

$script:Projects = [ordered]@{
    chatwoot = Join-Path $script:RepoRoot 'infra\chatwoot'   # compose project saas-chatwoot (pinned v4.20.1)
    r2r      = Join-Path $script:RepoRoot 'infra\r2r'        # compose project saas-r2r (pinned 3.6.5)
    ollama   = Join-Path $script:RepoRoot 'infra\ollama'     # compose project saas-ollama (0.40.17)
    platform = Join-Path $script:RepoRoot 'infra\platform'   # compose project saas-platform (db, api, admin-web)
}

function Write-Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }

function Test-DockerEngine {
    $null = & docker version --format '{{.Server.Version}}' 2>$null
    return ($LASTEXITCODE -eq 0)
}

function Invoke-Compose {
    param([string]$Project, [string[]]$Arguments)
    $dir = $script:Projects[$Project]
    if (-not (Test-Path $dir)) { throw "Unknown compose project '$Project' ($dir missing)." }
    Push-Location $dir
    try { & docker compose @Arguments; if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed in $Project (exit $LASTEXITCODE)." } }
    finally { Pop-Location }
}

function Get-HttpStatus([string]$Url, [int]$TimeoutSec = 5) {
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}
