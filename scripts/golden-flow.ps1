# Runs the real-stack Playwright golden flow (apps/e2e). Fails loudly until the e2e project exists and is wired.
. (Join-Path $PSScriptRoot 'common.ps1')

$e2e = Join-Path $script:RepoRoot 'apps\e2e'
if (-not (Test-Path (Join-Path $e2e 'playwright.config.ts'))) {
    throw 'apps/e2e is not implemented yet. Golden flow cannot run; do not treat this as a pass.'
}
Write-Step 'Running golden flow against the real local stack'
& docker run --rm --network host -v "${script:RepoRoot}:/workspace" -w /workspace node:26.13.1-bookworm-slim sh -c 'npm i -g pnpm@12.23.0 >/dev/null 2>&1; pnpm nx run e2e:golden-flow'
if ($LASTEXITCODE -ne 0) { throw "Golden flow failed (exit $LASTEXITCODE). See apps/e2e reports." }
