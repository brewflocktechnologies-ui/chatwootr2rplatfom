# Idempotent local model bootstrap. Pulls only what is missing. Generation and embedding run inside local Ollama only.
. (Join-Path $PSScriptRoot 'common.ps1')

$required = @('llama3.2:3b', 'mxbai-embed-large')
$container = 'saas-ollama-ollama-1'
$present = (& docker exec $container ollama list 2>$null) -join "`n"
if ($LASTEXITCODE -ne 0) { throw "Ollama container '$container' is not running. Run scripts/up.ps1 first." }

foreach ($m in $required) {
    if ($present -match [regex]::Escape($m)) {
        Write-Host "present: $m"
    } else {
        Write-Step "pulling $m (first start can take several minutes)"
        & docker exec $container ollama pull $m
        if ($LASTEXITCODE -ne 0) { throw "ollama pull $m failed." }
    }
}
Write-Host 'Models ready.' -ForegroundColor Green
