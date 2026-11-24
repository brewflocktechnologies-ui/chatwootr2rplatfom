// Host-side driver for the fault-injection suite.
// Owns: fault proxy process, throwaway platform (compose project saas-e2e-faults), AgentBot outgoing_url switch (restored in
// finally), AI-07 connection sampling of the platform, R2R, and Ollama containers, and teardown (down -v).
// The main stack (saas-platform, saas-chatwoot, saas-r2r, saas-ollama) is only read; its containers are never stopped.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, openSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const ART = path.join(REPO, 'artifacts', 'e2e');
const FAULTS = path.join(ART, 'faults');
mkdirSync(FAULTS, { recursive: true });
const NODE_IMAGE = 'node:26.13.1-bookworm-slim';
const COMPOSE_ARGS = ['compose', '-p', 'saas-e2e-faults', '-f', 'apps/e2e/faults/docker-compose.yml', '--env-file', 'infra/platform/.env'];
const FAULT_BASE = 'http://127.0.0.1:8081';
const CTL = 'http://127.0.0.1:3999';
const CW = 'http://127.0.0.1:3100/api/v1/accounts/4';
const THROWAWAY_WEBHOOK = 'http://host.docker.internal:8081/api/webhooks/chatwoot/agent-bot';
const SAMPLED = ['saas-e2e-faults-faults-platform-api-1', 'saas-platform-platform-api-1', 'saas-r2r-r2r-1', 'saas-ollama-ollama-1'];
const log = (m) => console.log(`[faults] ${m}`);
const teardown = { steps: [] };
const note = (step, detail) => { teardown.steps.push({ step, at: new Date().toISOString(), ...detail }); log(`${step}: ${JSON.stringify(detail)}`); };

// Reads secrets in-process only (values are used for API calls, never printed or written).
function readEnv(rel) {
  const out = {};
  for (const line of readFileSync(path.join(REPO, rel), 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (m) out[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
  return out;
}
const platformEnv = readEnv('infra/platform/.env');
const cwToken = platformEnv.CHATWOOT_API_TOKEN;
const adminKey = platformEnv.PLATFORM_ADMIN_API_KEY;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitUntil(label, fn, timeoutMs, everyMs = 1000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    try { const v = await fn(); if (v) return v; } catch { /* retry */ }
    await sleep(everyMs);
  }
  throw new Error(`timeout: ${label}`);
}
const compose = (args) => spawnSync('docker', [...COMPOSE_ARGS, ...args], { cwd: REPO, encoding: 'utf8', timeout: 600_000 });

// ---- 0. Preflight: the main stack must be up (read-only checks).
for (const [name, url] of [['platform', 'http://127.0.0.1:8080/actuator/health/readiness'], ['chatwoot', 'http://127.0.0.1:3100/api'], ['r2r', 'http://127.0.0.1:7272/v3/health'], ['admin-web', 'http://127.0.0.1:4300/demo']]) {
  const code = await fetch(url, { signal: AbortSignal.timeout(8000) }).then((r) => r.status).catch(() => 'ERR');
  if (code !== 200) throw new Error(`main stack not healthy: ${name} ${code}`);
}
note('preflight', { mainStack: 'healthy' });

// Clean slate for this project only (its own volume), then bring up.
compose(['down', '-v', '--remove-orphans']);
let proxy;
let sampler = { running: false };
let originalBotUrl = null;
let runExit = null;
const ledgerOk = { value: false };

async function patchBot(url) {
  const r = await fetch(`${CW}/agent_bots/1`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', api_access_token: cwToken },
    body: JSON.stringify({ outgoing_url: url }),
  });
  const cur = await fetch(`${CW}/agent_bots/1`, { headers: { api_access_token: cwToken } }).then((x) => x.json());
  return { status: r.status, outgoing_url: cur.outgoing_url };
}

try {
  // ---- 1. Save the AgentBot's original outgoing URL (restored in finally).
  const bots = await fetch(`${CW}/agent_bots`, { headers: { api_access_token: cwToken } }).then((r) => r.json());
  const bot = (Array.isArray(bots) ? bots : []).find((b) => b.id === 1);
  if (!bot) throw new Error('AgentBot 1 not found');
  originalBotUrl = bot.outgoing_url;
  writeFileSync(path.join(FAULTS, 'bot-original-url.json'), JSON.stringify({ outgoing_url: originalBotUrl }, null, 2));
  note('bot-original-url-saved', { url: originalBotUrl });

  // ---- 2. Fault proxy (host process).
  const proxyLog = path.join(FAULTS, 'proxy-stdout.log');
  proxy = spawn(process.execPath, [path.join(REPO, 'apps/e2e/tools/fault-proxy.mjs')], { stdio: ['ignore', 'pipe', 'pipe'] });
  proxy.stdout.on('data', (d) => appendFileSync(proxyLog, d));
  proxy.stderr.on('data', (d) => appendFileSync(proxyLog, d));
  await waitUntil('proxy control', async () => (await fetch(`${CTL}/rules`)).ok, 15_000, 300);
  note('proxy-started', { chatwoot: '127.0.0.1:3900->3100', r2r: '127.0.0.1:7373->7272', control: '127.0.0.1:3999' });

  // ---- 3. Throwaway platform (saas-e2e-faults).
  const up = compose(['up', '-d']);
  if (up.status !== 0) throw new Error(`compose up failed: ${up.stderr}`);
  await waitUntil('throwaway readiness', async () => (await fetch(`${FAULT_BASE}/actuator/health/readiness`)).status === 200, 240_000, 2000);
  note('throwaway-up', { port: 8081, project: 'saas-e2e-faults' });

  // ---- 4. Policy knowledge in the throwaway tenant (through the R2R proxy; the platform adopts the existing R2R document).
  const form = new FormData();
  form.append('file', new Blob([readFileSync(path.join(REPO, 'docs/demo-tenant/acme-support-policy.md'))], { type: 'text/markdown' }), 'acme-support-policy.md');
  const up2 = await fetch(`${FAULT_BASE}/api/admin/knowledge/documents`, { method: 'POST', headers: { 'X-Admin-Api-Key': adminKey }, body: form });
  note('throwaway-policy-upload', { status: up2.status });
  const ready = await waitUntil('throwaway policy READY', async () => {
    const d = await fetch(`${FAULT_BASE}/api/admin/knowledge/documents`, { headers: { 'X-Admin-Api-Key': adminKey } }).then((r) => r.json());
    const doc = d.documents.find((x) => x.filename === 'acme-support-policy.md');
    return doc && doc.status !== 'INGESTING' && doc.status !== 'PENDING' ? doc : null;
  }, 240_000, 2000);
  note('throwaway-policy-status', { status: ready.status, errorCode: ready.errorCode ?? null });
  if (ready.status !== 'READY') throw new Error(`throwaway policy not READY: ${ready.status} ${ready.errorCode}`);

  // ---- 5. AgentBot webhook -> throwaway platform (restored in finally).
  const sw = await patchBot(THROWAWAY_WEBHOOK);
  note('bot-url-switched', sw);
  if (sw.outgoing_url !== THROWAWAY_WEBHOOK) throw new Error('AgentBot outgoing_url did not switch');

  // ---- 6. AI-07 connection sampler (every 2 s, ESTABLISHED rows of the four containers).
  const samplesFile = path.join(FAULTS, 'ai07-samples.jsonl');
  const parseTcp = (text, v6) => {
    const rows = [];
    for (const line of text.split('\n').slice(1)) {
      const f = line.trim().split(/\s+/);
      if (f.length < 4 || f[3] !== '01') continue; // 01 = ESTABLISHED
      const [hex, port] = f[2].split(':');
      let ip;
      if (!v6) {
        const b = hex.match(/../g).reverse().map((x) => parseInt(x, 16));
        ip = b.join('.');
      } else if (hex.startsWith('0000000000000000FFFF0000')) {
        const b = hex.slice(24).match(/../g).reverse().map((x) => parseInt(x, 16));
        ip = b.join('.');
      } else ip = `ipv6:${hex}`;
      rows.push(`${ip}:${parseInt(port, 16)}`);
    }
    return rows;
  };
  sampler.running = true;
  const sampleLoop = (async () => {
    while (sampler.running) {
      for (const c of SAMPLED) {
        const r = spawnSync('docker', ['exec', c, 'sh', '-c', 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null'], { encoding: 'utf8', timeout: 15_000 });
        if (r.status !== 0) { appendFileSync(samplesFile, JSON.stringify({ t: new Date().toISOString(), container: c, error: (r.stderr || '').slice(0, 120) }) + '\n'); continue; }
        // Output is /proc/net/tcp then /proc/net/tcp6, each with its own header line.
        const remotes = [];
        let headers = 0;
        for (const line of r.stdout.split('\n')) {
          if (line.includes('local_address')) { headers++; continue; }
          if (headers === 0) continue;
          remotes.push(...parseTcp(`header\n${line}`, headers > 1));
        }
        appendFileSync(samplesFile, JSON.stringify({ t: new Date().toISOString(), container: c, remotes: [...new Set(remotes)] }) + '\n');
      }
      await sleep(2000);
    }
  })();

  // ---- 7. Run the fault suite in the container (same image and mounts as the golden flow).
  const dockerArgs = [
    'run', '--rm', '--network', 'host',
    '-v', `${REPO.replace(/\\/g, '/')}:/work`,
    '-v', 'saas-e2e-node-modules:/work/apps/e2e/node_modules',
    '-v', 'saas-e2e-ms-playwright:/ms-playwright',
    '-v', 'saas-e2e-pnpm-store:/pnpm-store',
    '-e', 'npm_config_store_dir=/pnpm-store',
    '-e', 'PLAYWRIGHT_BROWSERS_PATH=/ms-playwright',
    '-e', 'GF_FAULTS=1',
    '-w', '/work/apps/e2e',
    NODE_IMAGE, 'sh', '-c',
    'npm i -g pnpm@12.23.0 >/dev/null 2>&1 && pnpm install && pnpm exec playwright install --with-deps chromium && pnpm exec playwright test',
  ];
  const runLog = path.join(FAULTS, 'run-container.log');
  const logFd = openSync(runLog, 'w');
  const child = spawn('docker', dockerArgs, { stdio: ['ignore', logFd, logFd], cwd: REPO });
  runExit = await new Promise((resolve) => child.on('exit', (code) => resolve(code)));
  note('playwright-finished', { exitCode: runExit });
  ledgerOk.value = true;
} catch (e) {
  note('error', { message: String(e.message || e) });
  runExit = runExit ?? 1;
} finally {
  // ---- 8. Teardown. Restore the AgentBot URL first, then stop the sampler and proxy, then remove the throwaway project.
  if (originalBotUrl) {
    const r = await patchBot(originalBotUrl).catch((e) => ({ error: e.message }));
    note('bot-url-restored', { status: r.status ?? null, restored: r.outgoing_url === originalBotUrl, error: r.error ?? null });
  }
  sampler.running = false;
  await sleep(2500);
  if (proxy && !proxy.killed) { proxy.kill(); note('proxy-stopped', {}); }
  const down = compose(['down', '-v', '--remove-orphans']);
  note('throwaway-down-v', { exitCode: down.status, stderrTail: (down.stderr || '').split('\n').slice(-3).join(' | ').slice(0, 300) });
  const vols = spawnSync('docker', ['volume', 'ls', '--format', '{{.Name}}'], { encoding: 'utf8' }).stdout.split('\n').filter((v) => /saas-e2e-faults/.test(v));
  const ctrs = spawnSync('docker', ['ps', '-a', '--format', '{{.Names}}'], { encoding: 'utf8' }).stdout.split('\n').filter((c) => /saas-e2e-faults/.test(c));
  note('throwaway-removal-check', { remainingVolumes: vols, remainingContainers: ctrs, removed: vols.length === 0 && ctrs.length === 0 });
  writeFileSync(path.join(FAULTS, 'teardown.json'), JSON.stringify(teardown, null, 2));
}

// ---- 9. AI-07 config names (names only; values are never printed). Container env names and config key names.
{
  const names = [];
  const pat = /(OPENAI|ANTHROPIC|GEMINI|GOOGLE|AZURE|MISTRAL|COHERE|GROQ|TOGETHER|OPENROUTER|HUGGING|PERPLEXITY|DEEPSEEK|VOYAGE|JINA|_API_KEY)/i;
  const host = /(api\.openai\.com|api\.anthropic\.com|generativelanguage\.googleapis\.com|openai\.azure\.com|api\.mistral\.ai|api\.cohere\.ai|api\.groq\.com|api\.together\.xyz|openrouter\.ai|api\.perplexity\.ai|api\.deepseek\.com)/i;
  for (const rel of ['infra/platform/.env', 'infra/r2r/.env', 'infra/r2r/config/r2r.local.toml', 'infra/ollama/.env']) {
    const p = path.join(REPO, rel);
    if (!existsSync(p)) { names.push({ source: rel, present: false }); continue; }
    const text = readFileSync(p, 'utf8');
    const keys = [...new Set(text.split(/\r?\n/).map((l) => /^\s*([A-Za-z0-9_.-]+)\s*[=:]/.exec(l)?.[1]).filter((k) => k && pat.test(k)))];
    const hostsSeen = [...new Set([...text.matchAll(new RegExp(host.source, 'gi'))].map((m) => m[0].toLowerCase()))];
    names.push({ source: rel, present: true, providerLikeKeyNames: keys, hostedEndpointNamesFound: hostsSeen });
  }
  for (const c of ['saas-r2r-r2r-1', 'saas-platform-platform-api-1', 'saas-e2e-faults-faults-platform-api-1']) {
    const env = spawnSync('docker', ['inspect', '-f', '{{range .Config.Env}}{{println .}}{{end}}', c], { encoding: 'utf8' }).stdout.split('\n').map((l) => l.split('=')[0]).filter(Boolean);
    names.push({ source: `container:${c}`, providerLikeKeyNames: env.filter((k) => pat.test(k)), envVarNameCount: env.length });
  }
  writeFileSync(path.join(FAULTS, 'ai07-config-names.json'), JSON.stringify(names, null, 2));
  log('AI-07 config names written (names only)');
}

process.exitCode = runExit ?? 1;
log(`done; playwright exit ${runExit}`);
