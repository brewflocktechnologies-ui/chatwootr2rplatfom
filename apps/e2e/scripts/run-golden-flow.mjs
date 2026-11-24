// Host-side driver for the golden flow. Runs on the Windows host (Node), not inside the container.
// Steps 1-2 are read-only checks. Steps 3-17 run in node:26.13.1-bookworm-slim with the repo mounted at /work.
// The only container action is `docker compose restart platform-api` (infra/platform) when the test asks for it.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const ART = path.join(REPO, 'artifacts', 'e2e');
const CONTROL = path.join(ART, 'control');
const NODE_IMAGE = 'node:26.13.1-bookworm-slim';
const PLATFORM_CONTAINER = 'saas-platform-platform-api-1';
const override = process.env.GF_WIDGET_BASE_OVERRIDE || '';

for (const d of ['control', 'traces', 'screenshots', 'api']) mkdirSync(path.join(ART, d), { recursive: true });
rmSync(path.join(CONTROL, 'restart-request'), { force: true });
rmSync(path.join(CONTROL, 'restart-done'), { force: true });

function readEnvFile(rel) {
  const out = {};
  if (!existsSync(path.join(REPO, rel))) return out;
  for (const line of readFileSync(path.join(REPO, rel), 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (m) out[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
  return out;
}
// Only credential values are redacted by value (not every env value, which would also hit tenant keys and URLs).
const SENSITIVE_KEYS = ['PLATFORM_ADMIN_API_KEY', 'CHATWOOT_API_TOKEN', 'CHATWOOT_BOT_SECRET', 'CHATWOOT_BOT_ACCESS_TOKEN', 'R2R_SERVICE_PASSWORD', 'PLATFORM_DB_PASSWORD', 'HUMAN_AGENT_EMAIL', 'HUMAN_AGENT_PASSWORD', 'CHATWOOT_ADMIN_PASSWORD', 'CHATWOOT_ADMIN_EMAIL'];
const allEnv = { ...readEnvFile('infra/platform/.env'), ...readEnvFile('infra/chatwoot/.env.secrets') };
const secretValues = SENSITIVE_KEYS.map((k) => allEnv[k]).filter((v) => v && v.length >= 6);
// Token-like redaction (kept in step with tests/support.ts redactTokens): JWTs, auth header/field values.
function redactTokens(t) {
  return t
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[EMAIL]')
    .replace(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}(\.[A-Za-z0-9_-]{10,})?/g, '[JWT]')
    .replace(/("name"\s*:\s*"(?:access-token|client|uid|authorization|api_access_token|x-auth-token|x-admin-api-key|cookie|set-cookie|x-chatwoot-signature)"\s*,\s*"value"\s*:\s*")[^"]*/gi, '$1[REDACTED]')
    .replace(/("(?:password|token|access_token|api_access_token|secret|authorization)"\s*:\s*")[^"]*/gi, '$1[REDACTED]')
    .replace(/((?:api_access_token|access-token|x-auth-token|authorization)\s*[:=]\s*)[^\s"',;]+/gi, '$1[REDACTED]');
}
const redact = (t) => {
  let out = t;
  for (const v of secretValues) out = out.split(v).join('[REDACTED]');
  return redactTokens(out);
};

// Runtime redaction of everything saved under artifacts/e2e: text files directly, and zip archives (Playwright traces)
// by extracting, redacting text entries, and rebuilding the archive. Runs before the summary is written.
function redactArtifacts(root) {
  const isBinary = (f) => /\.(png|jpe?g|webp|woff2?|webm|ico)$/i.test(f);
  const all = [];
  const walk = (d) => {
    for (const f of readdirSync(d)) {
      const q = path.join(d, f);
      if (statSync(q).isDirectory()) walk(q);
      else all.push(q);
    }
  };
  walk(root);
  const changed = [];
  for (const f of all) {
    if (f.endsWith('.zip') || isBinary(f)) continue;
    const t = readFileSync(f, 'utf8');
    const r = redact(t);
    if (r !== t) { writeFileSync(f, r); changed.push(path.relative(root, f)); }
  }
  let zipIdx = 0;
  for (const z of all.filter((f) => f.endsWith('.zip'))) {
    const dir = path.join(tmpdir(), `gf-zip-${Date.now()}-${zipIdx++}`);
    const ps1 = path.join(tmpdir(), `gf-zip-${Date.now()}-${zipIdx}.ps1`);
    writeFileSync(ps1, [
      `$ErrorActionPreference = 'Stop'`,
      `Expand-Archive -Force -LiteralPath '${z}' -DestinationPath '${dir}'`,
      `Write-Output 'extracted'`,
    ].join('\r\n'));
    const ex = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ps1], { encoding: 'utf8' });
    if (ex.status !== 0) { changed.push(`ERROR extracting ${path.relative(root, z)}`); continue; }
    let hits = 0;
    const inner = [];
    const w2 = (d) => { for (const f of readdirSync(d)) { const q = path.join(d, f); if (statSync(q).isDirectory()) w2(q); else inner.push(q); } };
    w2(dir);
    for (const f of inner) {
      if (isBinary(f)) continue;
      const t = readFileSync(f, 'utf8');
      const r = redact(t);
      if (r !== t) { writeFileSync(f, r); hits++; }
    }
    if (hits > 0) {
      const tmpZip = z + '.redacted.zip';
      const ps2 = path.join(tmpdir(), `gf-zip-${Date.now()}-${zipIdx}-b.ps1`);
      writeFileSync(ps2, [
        `$ErrorActionPreference = 'Stop'`,
        `Compress-Archive -Force -Path '${dir}\\*' -DestinationPath '${tmpZip}'`,
        `Copy-Item -Force '${tmpZip}' '${z}'`,
        `Remove-Item -Force '${tmpZip}'`,
        `Write-Output 'rebuilt'`,
      ].join('\r\n'));
      const bz = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ps2], { encoding: 'utf8' });
      changed.push(`${bz.status === 0 ? '' : 'ERROR rebuilding '}${path.relative(root, z)} (${hits} entries redacted)`);
    }
    rmSync(dir, { recursive: true, force: true });
  }
  return changed;
}

const steps = [];
const log = (msg) => console.log(`[golden-flow] ${msg}`);

// ---- Step 1: doctor + status (read-only). Output saved to artifacts.
{
  const started = new Date().toISOString();
  const status = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(REPO, 'scripts', 'status.ps1')], { encoding: 'utf8', timeout: 300_000 });
  writeFileSync(path.join(ART, 'step01-local-status.txt'), redact((status.stdout || '') + (status.stderr || '')));
  const doctor = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(REPO, 'scripts', 'doctor.ps1')], { encoding: 'utf8', timeout: 600_000 });
  writeFileSync(path.join(ART, 'step01-local-doctor.txt'), redact((doctor.stdout || '') + (doctor.stderr || '')));
  steps.push({
    step: 1,
    name: 'Run local doctor and local status, save results',
    verdict: status.status === 0 ? 'PASS' : 'FAIL',
    reason: status.status === 0 ? undefined : `status.ps1 exit ${status.status}; see step01-local-status.txt`,
    doctorExit: doctor.status,
    startedAt: started,
    endedAt: new Date().toISOString(),
  });
}

// ---- Step 2: verify services are healthy (no `local:up` needed if all are up; nothing is started here).
{
  const checks = {
    chatwoot: 'http://127.0.0.1:3100/api',
    r2r: 'http://127.0.0.1:7272/v3/health',
    ollama: 'http://127.0.0.1:11434/api/tags',
    platformApi: 'http://127.0.0.1:8080/actuator/health/readiness',
    adminWeb: 'http://127.0.0.1:4300/demo',
  };
  const codes = {};
  for (const [k, u] of Object.entries(checks)) {
    try { codes[k] = (await fetch(u, { signal: AbortSignal.timeout(8000) })).status; } catch (e) { codes[k] = 'ERR'; }
  }
  const allUp = Object.values(codes).every((c) => c === 200);
  steps.push({ step: 2, name: 'Verify services healthy (local:up not required when all are up)', verdict: allUp ? 'PASS' : 'FAIL', codes, reason: allUp ? undefined : 'a service is not healthy' });
}

// ---- Steps 3-17: containerized Playwright run.
const runStart = new Date();
const runLog = path.join(ART, 'run-container.log');
const dockerArgs = [
  'run', '--rm', '--network', 'host',
  '-v', `${REPO.replace(/\\/g, '/')}:/work`,
  '-v', 'saas-e2e-node-modules:/work/apps/e2e/node_modules',
  '-v', 'saas-e2e-ms-playwright:/ms-playwright',
  '-v', 'saas-e2e-pnpm-store:/pnpm-store',
  '-e', 'npm_config_store_dir=/pnpm-store',
  '-e', 'PLAYWRIGHT_BROWSERS_PATH=/ms-playwright',
  ...(override ? ['-e', `GF_WIDGET_BASE_OVERRIDE=${override}`] : []),
  '-w', '/work/apps/e2e',
  NODE_IMAGE,
  'sh', '-c',
  'npm i -g pnpm@12.23.0 >/dev/null 2>&1 && pnpm install && pnpm exec playwright install --with-deps chromium && pnpm exec playwright test',
];
log(`starting container run (variant: ${override ? 'widget rewrite' : 'primary'})`);
const logFd = openSync(runLog, 'w');
const child = spawn('docker', dockerArgs, { stdio: ['ignore', logFd, logFd], cwd: REPO });
let exited = false;
let containerExit = null;
child.on('exit', (code) => { containerExit = code; exited = true; });

// Restart broker: the only container action the flow is allowed to take (bootstrap step 16).
let restartHandled = false;
const restartTimer = setInterval(async () => {
  const req = path.join(CONTROL, 'restart-request');
  if (restartHandled || !existsSync(req)) return;
  restartHandled = true;
  rmSync(req, { force: true });
  log('restart requested by test: docker compose restart platform-api');
  const r = spawnSync('docker', ['compose', 'restart', 'platform-api'], { cwd: path.join(REPO, 'infra', 'platform'), encoding: 'utf8', timeout: 300_000 });
  let healthy = false;
  for (let i = 0; i < 90 && !healthy; i++) {
    await new Promise((res) => setTimeout(res, 2000));
    try { healthy = (await fetch('http://127.0.0.1:8080/actuator/health/readiness', { signal: AbortSignal.timeout(3000) })).status === 200; } catch { healthy = false; }
  }
  const msg = `${r.status === 0 && healthy ? 'OK' : 'FAIL'} compose-exit=${r.status} readiness=${healthy ? 200 : 'not-ready'} at=${new Date().toISOString()}`;
  writeFileSync(path.join(CONTROL, 'restart-done'), msg);
  log(`restart: ${msg}`);
}, 1000);

await new Promise((resolve) => {
  const iv = setInterval(() => { if (exited) { clearInterval(iv); resolve(); } }, 2000);
});
clearInterval(restartTimer);

// ---- Runtime redaction before the archive is final.
const redactionLog = redactArtifacts(ART);
writeFileSync(path.join(ART, 'redaction-log.txt'), redactionLog.join('\n') + '\n');
log(`redaction: ${redactionLog.length} file(s) changed`);

// ---- Step 17 supplement: platform log for the run window (sanitized).
const logs = spawnSync('docker', ['logs', '--since', runStart.toISOString(), PLATFORM_CONTAINER], { encoding: 'utf8', timeout: 120_000 });
writeFileSync(path.join(ART, 'api', 'platform-api.log'), redact((logs.stdout || '') + (logs.stderr || '')));

const summary = {
  variant: override ? 'PARTIAL: widget base URL rewritten by route interception' : 'primary',
  containerExitCode: containerExit,
  startedAt: runStart.toISOString(),
  endedAt: new Date().toISOString(),
  driverSteps: steps,
  redactionChanged: redactionLog,
  artifacts: {
    htmlReport: 'artifacts/e2e/html-report/index.html',
    jsonResults: 'artifacts/e2e/results.json',
    ledger: 'artifacts/e2e/api/ledger.json',
    observations: 'artifacts/e2e/api/observations.json',
    traces: 'artifacts/e2e/traces/',
    screenshots: 'artifacts/e2e/screenshots/',
    platformLog: 'artifacts/e2e/api/platform-api.log',
    containerLog: 'artifacts/e2e/run-container.log',
  },
};
writeFileSync(path.join(ART, 'driver-summary.json'), redact(JSON.stringify(summary, null, 2)));
log(`container exit ${containerExit}; summary in artifacts/e2e/driver-summary.json`);
process.exitCode = containerExit ?? 1;
