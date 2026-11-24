# DESTRUCTIVE: erases PLATFORM demo data only (the saas-platform compose project and its named volume).
# Never touches Chatwoot, R2R, Ollama volumes or any other Docker volume. Requires typed confirmation.
. (Join-Path $PSScriptRoot 'common.ps1')

$expected = 'RESET saas-platform'
Write-Host 'This erases platform demo data: the platform database volume saas-platform_db.' -ForegroundColor Red
Write-Host 'It does NOT touch Chatwoot, R2R, Ollama or any other volume.' -ForegroundColor Yellow
$typed = Read-Host "Type exactly '$expected' to continue"
if ($typed -ne $expected) { Write-Host 'Confirmation did not match. Nothing was changed.' -ForegroundColor Yellow; exit 1 }

Invoke-Compose platform @('down', '-v', '--remove-orphans')
Write-Host 'Platform demo data erased. Upstream data untouched.' -ForegroundColor Green
