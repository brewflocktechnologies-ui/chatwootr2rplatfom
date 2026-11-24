# Idempotent R2R bootstrap for the acme-demo tenant: ensures the tenant service identity and its collection exist.
# Uses the R2R ADMIN only to create the service identity (never used by the platform at runtime).
# Usage: pwsh scripts/bootstrap-r2r.ps1   (requires infra/r2r/.env and infra/platform/.env)
. (Join-Path $PSScriptRoot 'common.ps1')

function Read-EnvFile([string]$Path) {
    $h = @{}
    Get-Content $Path | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object { $k, $v = $_ -split '=', 2; $h[$k] = $v }
    return $h
}

$r2rEnv = Read-EnvFile (Join-Path $script:RepoRoot 'infra\r2r\.env')
$platEnv = Read-EnvFile (Join-Path $script:RepoRoot 'infra\platform\.env')
$base = 'http://127.0.0.1:7272'

function Get-Token([string]$Email, [string]$Password) {
    $body = "username=$([uri]::EscapeDataString($Email))&password=$([uri]::EscapeDataString($Password))"
    $r = Invoke-RestMethod -Method Post -Uri "$base/v3/users/login" -ContentType 'application/x-www-form-urlencoded' -Body $body -TimeoutSec 30
    return $r.results.access_token.token
}

Write-Step 'Admin login (bootstrap only)'
$adminToken = Get-Token $r2rEnv['R2R_DEFAULT_ADMIN_EMAIL'] $r2rEnv['R2R_DEFAULT_ADMIN_PASSWORD']
$adminHeaders = @{ Authorization = "Bearer $adminToken" }

Write-Step 'Tenant service identity'
$svcEmail = $platEnv['R2R_SERVICE_EMAIL']
$svcPassword = $platEnv['R2R_SERVICE_PASSWORD']
try {
    $null = Invoke-RestMethod -Method Post -Uri "$base/v3/users" -Headers $adminHeaders -ContentType 'application/json' `
        -Body (@{ email = $svcEmail; password = $svcPassword } | ConvertTo-Json) -TimeoutSec 30
    Write-Host "created service identity $svcEmail"
} catch {
    Write-Host "service identity already present (or create refused: $($_.Exception.Message))"
}
$svcToken = Get-Token $svcEmail $svcPassword

Write-Step 'Tenant collection'
$svcHeaders = @{ Authorization = "Bearer $svcToken" }
$list = Invoke-RestMethod -Uri "$base/v3/collections?owner_only=true&limit=100" -Headers $svcHeaders -TimeoutSec 30
$found = @($list.results) | Where-Object { $_.name -eq 'acme-demo-knowledge' } | Select-Object -First 1
if ($found) {
    Write-Host "collection present: $($found.id)"
} else {
    $created = Invoke-RestMethod -Method Post -Uri "$base/v3/collections" -Headers $svcHeaders -ContentType 'application/json' `
        -Body (@{ name = 'acme-demo-knowledge'; description = 'Fictional Acme Demo support policy (tenant acme-demo)' } | ConvertTo-Json) -TimeoutSec 30
    $found = $created.results
    Write-Host "created collection: $($found.id)"
}

if ($platEnv['R2R_COLLECTION_ID'] -ne $found.id) {
    Write-Host "NOTE: infra/platform/.env R2R_COLLECTION_ID differs from R2R ($($found.id)). Update the file, then restart platform-api." -ForegroundColor Yellow
}
Write-Host 'R2R tenant bootstrap complete.' -ForegroundColor Green
