// Fault-injection proxy for the E2E suite (host process, Node).
// Chatwoot: listens 127.0.0.1:3900 -> 127.0.0.1:3100. R2R: 127.0.0.1:7373 -> 127.0.0.1:7272.
// Control API: 127.0.0.1:3999 (GET/POST /rules, DELETE /rules/:id, DELETE /rules releases all held responses, GET /log).
// Rule actions per matched request: delay(ms) before forwarding; hang (forward upstream, withhold the response until the
// rule is deleted); error(status) (answer without forwarding). Rules match target, method, path regex, optional body regex.
import http from 'node:http';
import { appendFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(HERE, '../../../artifacts/e2e/faults');
mkdirSync(OUT, { recursive: true });
const LOG_FILE = path.join(OUT, 'proxy-log.jsonl');

const TARGETS = {
  chatwoot: { listen: 3900, host: '127.0.0.1', port: 3100 },
  r2r: { listen: 7373, host: '127.0.0.1', port: 7272 },
};
const CONTROL_PORT = 3999;
const HOLD_MAX_MS = 300_000;

let rules = [];
let ruleSeq = 0;
let reqSeq = 0;
const log = [];

const deferred = () => {
  let resolve;
  const promise = new Promise((r) => (resolve = r));
  return { promise, resolve };
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function matchRule(target, method, pathname, body) {
  return rules.find(
    (r) =>
      r.target === target &&
      r.method === method &&
      new RegExp(r.pathRe).test(pathname) &&
      (!r.bodyRe || new RegExp(r.bodyRe).test(body)) &&
      (r.maxHits === 0 || r.hits < r.maxHits),
  );
}

function forward(t, req, body) {
  return new Promise((resolve, reject) => {
    const headers = { ...req.headers, host: `${t.host}:${t.port}`, 'content-length': String(body.length) };
    delete headers.connection;
    const up = http.request({ host: t.host, port: t.port, path: req.url, method: req.method, headers }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    up.on('error', reject);
    up.end(body);
  });
}

const HOP = new Set(['connection', 'keep-alive', 'transfer-encoding', 'content-length']);

async function handle(targetName, req, res) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = Buffer.concat(chunks);
  const pathname = new URL(req.url, 'http://x').pathname;
  const rule = matchRule(targetName, req.method, pathname, body.toString('utf8'));
  if (rule) rule.hits++;
  const entry = {
    seq: ++reqSeq,
    at: new Date().toISOString(),
    target: targetName,
    method: req.method,
    path: pathname,
    rule: rule?.id ?? null,
    action: rule?.action.type ?? 'pass',
  };
  const t = TARGETS[targetName];
  const started = Date.now();
  try {
    if (rule?.action.type === 'error') {
      entry.status = rule.action.status;
      res.writeHead(rule.action.status, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'injected_fault' }));
      return;
    }
    let upstream;
    if (rule?.action.type === 'delay') {
      await sleep(rule.action.ms);
      upstream = await forward(t, req, body);
    } else if (rule?.action.type === 'hang') {
      upstream = await forward(t, req, body); // the upstream side really happens
      await Promise.race([rule.gate.promise, sleep(HOLD_MAX_MS)]); // the response to the caller is withheld
    } else {
      upstream = await forward(t, req, body);
    }
    entry.status = upstream.status;
    entry.ms = Date.now() - started;
    const headers = {};
    for (const [k, v] of Object.entries(upstream.headers)) if (!HOP.has(k)) headers[k] = v;
    res.writeHead(upstream.status, headers);
    res.end(upstream.body);
  } catch (e) {
    entry.error = e.message;
    if (!res.headersSent) {
      res.writeHead(502, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'proxy_upstream_error' }));
    }
  } finally {
    log.push(entry);
    appendFileSync(LOG_FILE, JSON.stringify(entry) + '\n');
  }
}

for (const [name, t] of Object.entries(TARGETS)) {
  http.createServer((req, res) => handle(name, req, res)).listen(t.listen, '127.0.0.1', () => {
    console.log(`fault-proxy ${name} 127.0.0.1:${t.listen} -> ${t.host}:${t.port}`);
  });
}

async function readJson(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}
const publicRule = (r) => ({ id: r.id, target: r.target, method: r.method, pathRe: r.pathRe, bodyRe: r.bodyRe ?? null, action: r.action, maxHits: r.maxHits, hits: r.hits });

http
  .createServer(async (req, res) => {
    const json = (code, obj) => {
      res.writeHead(code, { 'content-type': 'application/json' });
      res.end(JSON.stringify(obj));
    };
    try {
      const url = new URL(req.url, 'http://x');
      if (req.method === 'GET' && url.pathname === '/rules') return json(200, rules.map(publicRule));
      if (req.method === 'GET' && url.pathname === '/log') return json(200, log);
      if (req.method === 'POST' && url.pathname === '/rules') {
        const b = await readJson(req);
        if (!TARGETS[b.target] || !['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(b.method)) return json(400, { error: 'bad_rule' });
        if (!['delay', 'hang', 'error'].includes(b.action?.type)) return json(400, { error: 'bad_action' });
        const rule = { id: `r${++ruleSeq}`, target: b.target, method: b.method, pathRe: b.pathRe, bodyRe: b.bodyRe, action: b.action, maxHits: b.maxHits ?? 0, hits: 0, gate: deferred() };
        rules.push(rule);
        return json(201, publicRule(rule));
      }
      if (req.method === 'DELETE' && url.pathname.startsWith('/rules/')) {
        const id = url.pathname.split('/')[2];
        const r = rules.find((x) => x.id === id);
        if (!r) return json(404, { error: 'no_such_rule' });
        r.gate.resolve();
        rules = rules.filter((x) => x !== r);
        return json(200, { released: id });
      }
      if (req.method === 'DELETE' && url.pathname === '/rules') {
        rules.forEach((r) => r.gate.resolve());
        const n = rules.length;
        rules = [];
        return json(200, { cleared: n });
      }
      return json(404, { error: 'not_found' });
    } catch (e) {
      return json(500, { error: e.message });
    }
  })
  .listen(CONTROL_PORT, '127.0.0.1', () => console.log(`fault-proxy control 127.0.0.1:${CONTROL_PORT}`));
