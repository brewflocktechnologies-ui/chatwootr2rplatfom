// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { forwardToPlatform, isAllowedRoute, MAX_UPLOAD_BYTES, sanitizeQuery } from './platform-proxy';

const ADMIN_KEY = 'test-admin-key-not-a-secret-0123456789abcdef';

function fakeFetch(status = 200, body = '{"ok":true}', contentType = 'application/json') {
  return vi.fn<typeof fetch>(async () => new Response(body, { status, headers: { 'content-type': contentType } }));
}

describe('route allowlist', () => {
  it('allows only the contract routes for each method', () => {
    expect(isAllowedRoute('GET', 'diagnostics')).toBe(true);
    expect(isAllowedRoute('GET', 'knowledge/documents')).toBe(true);
    expect(isAllowedRoute('POST', 'knowledge/documents/abc-123/retry')).toBe(true);
    expect(isAllowedRoute('POST', 'conversations/42/resume')).toBe(true);
    expect(isAllowedRoute('POST', 'diagnostics')).toBe(false);
    expect(isAllowedRoute('GET', 'secrets')).toBe(false);
    expect(isAllowedRoute('GET', '../../actuator/env')).toBe(false);
    expect(isAllowedRoute('POST', 'knowledge/documents/a/b/retry')).toBe(false);
  });

  it('allows only known webhook status filters', () => {
    expect(sanitizeQuery('webhooks', 'status=FAILED')).toBe('status=FAILED');
    expect(sanitizeQuery('webhooks', 'status=ANYTHING')).toBeNull();
    expect(sanitizeQuery('diagnostics', 'x=1')).toBeNull();
    expect(sanitizeQuery('diagnostics', '')).toBe('');
  });
});

describe('forwardToPlatform', () => {
  const original = { ...process.env };

  beforeEach(() => {
    process.env.PLATFORM_API_URL = 'http://127.0.0.1:8080';
    process.env.PLATFORM_ADMIN_API_KEY = ADMIN_KEY;
  });

  afterEach(() => {
    process.env = { ...original };
  });

  it('adds the admin key server-side and returns the upstream body without it', async () => {
    const fetchMock = fakeFetch();
    const result = await forwardToPlatform(
      {
        method: 'GET',
        relativePath: 'diagnostics',
        search: '',
        contentType: null,
        contentLength: null,
        readBody: async () => new ArrayBuffer(0),
      },
      fetchMock
    );

    expect(result.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(String(url)).toBe('http://127.0.0.1:8080/api/admin/diagnostics');
    expect(new Headers(init?.headers).get('X-Admin-Api-Key')).toBe(ADMIN_KEY);
    if (result.ok) {
      expect(new TextDecoder().decode(result.body)).not.toContain(ADMIN_KEY);
    }
  });

  it('refuses unknown routes before any upstream call', async () => {
    const fetchMock = fakeFetch();
    const result = await forwardToPlatform(
      { method: 'GET', relativePath: 'env', search: '', contentType: null, contentLength: null, readBody: async () => new ArrayBuffer(0) },
      fetchMock
    );
    expect(result).toEqual({ ok: false, error: { status: 404, error: 'not_found' } });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses an oversized upload before reading or forwarding it', async () => {
    const fetchMock = fakeFetch();
    const readBody = vi.fn(async () => new ArrayBuffer(0));
    const result = await forwardToPlatform(
      {
        method: 'POST',
        relativePath: 'knowledge/documents',
        search: '',
        contentType: 'multipart/form-data; boundary=x',
        contentLength: MAX_UPLOAD_BYTES * 2,
        readBody,
      },
      fetchMock
    );
    expect(result).toEqual({ ok: false, error: { status: 413, error: 'payload_too_large' } });
    expect(readBody).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a POST with an unsupported content type', async () => {
    const result = await forwardToPlatform(
      {
        method: 'POST',
        relativePath: 'knowledge/documents',
        search: '',
        contentType: 'text/html',
        contentLength: 12,
        readBody: async () => new ArrayBuffer(12),
      },
      fakeFetch()
    );
    expect(result).toEqual({ ok: false, error: { status: 415, error: 'unsupported_media_type' } });
  });

  it('reports a missing admin key without calling the platform', async () => {
    delete process.env.PLATFORM_ADMIN_API_KEY;
    const fetchMock = fakeFetch();
    const result = await forwardToPlatform(
      { method: 'GET', relativePath: 'tenant', search: '', contentType: null, contentLength: null, readBody: async () => new ArrayBuffer(0) },
      fetchMock
    );
    expect(result).toEqual({ ok: false, error: { status: 503, error: 'admin_proxy_not_configured' } });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('maps an unreachable platform to 502 without leaking the upstream error', async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => {
      throw new TypeError('fetch failed: connect ECONNREFUSED 127.0.0.1:8080');
    });
    const result = await forwardToPlatform(
      { method: 'GET', relativePath: 'agent', search: '', contentType: null, contentLength: null, readBody: async () => new ArrayBuffer(0) },
      fetchMock
    );
    expect(result).toEqual({ ok: false, error: { status: 502, error: 'platform_unavailable' } });
  });
});
