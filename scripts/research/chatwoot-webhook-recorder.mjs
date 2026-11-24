// Research probe only: records raw Chatwoot AgentBot deliveries (headers + body)
// to artifacts/ (git-ignored). Binds to loopback; reached from containers via host.docker.internal.
// Not part of the platform. Usage: node scripts/research/chatwoot-webhook-recorder.mjs [port]
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const port = Number(process.argv[2] ?? 8099);
const outDir = path.resolve('artifacts/probes/chatwoot-webhooks');
fs.mkdirSync(outDir, { recursive: true });
let seq = 0;

http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks).toString('utf8');
    const name = `${String(++seq).padStart(3, '0')}-${Date.now()}.json`;
    fs.writeFileSync(path.join(outDir, name), JSON.stringify({
      method: req.method, url: req.url, headers: req.headers, body,
      receivedAt: new Date().toISOString(),
    }, null, 2));
    console.log(`[recorder] ${req.method} ${req.url} -> ${name} (${body.length} bytes)`);
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"ok":true}');
  });
}).listen(port, '127.0.0.1', () => console.log(`[recorder] listening 127.0.0.1:${port}`));
