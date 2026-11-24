// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { GET, POST } from './route';

const ctx = { params: Promise.resolve({ path: ['tenant'] }) };
const postCtx = { params: Promise.resolve({ path: ['knowledge', 'documents'] }) };

describe('admin proxy route guards', () => {
  const original = { ...process.env };

  beforeEach(() => {
    process.env.PLATFORM_API_URL = 'http://127.0.0.1:8080';
    process.env.PLATFORM_ADMIN_API_KEY = 'test-admin-key-not-a-secret-0123456789abcdef';
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{"tenantKey":"acme-demo"}', { status: 200, headers: { 'content-type': 'application/json' } }))
    );
  });

  afterEach(() => {
    process.env = { ...original };
    vi.unstubAllGlobals();
  });

  it('leaves GET unchanged: no Origin needed and the call is forwarded', async () => {
    const response = await GET(new NextRequest('http://localhost:4300/api/admin/tenant', { headers: { host: 'localhost:4300' } }), ctx);
    expect(response.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('rejects a cross-site POST before forwarding', async () => {
    const response = await POST(
      new NextRequest('http://localhost:4300/api/admin/knowledge/documents', {
        method: 'POST',
        headers: { host: 'localhost:4300', origin: 'https://evil.example', 'sec-fetch-site': 'cross-site', 'content-type': 'application/json' },
        body: '{}',
      }),
      postCtx
    );
    expect(response.status).toBe(403);
    expect(await response.json()).toEqual({ error: 'forbidden_origin' });
    expect(fetch).not.toHaveBeenCalled();
  });

  it('forwards a same-origin POST', async () => {
    const response = await POST(
      new NextRequest('http://localhost:4300/api/admin/knowledge/documents', {
        method: 'POST',
        headers: { host: 'localhost:4300', origin: 'http://localhost:4300', 'sec-fetch-site': 'same-origin', 'content-type': 'application/json' },
        body: '{}',
      }),
      postCtx
    );
    expect(response.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
