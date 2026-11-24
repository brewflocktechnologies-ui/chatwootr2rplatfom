// Server-side forwarder from same-origin /api/admin/* to the platform API.
// The admin key is attached here and never reaches the browser. Only routes listed in
// api-contract.md are forwarded; everything else is refused before any upstream call.

import { readServerConfig, ConfigError } from './config';

export const MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
// Multipart framing overhead allowance on top of the 2 MiB file limit.
const MULTIPART_OVERHEAD_BYTES = 64 * 1024;
const UPSTREAM_TIMEOUT_MS = 20_000;

type Method = 'GET' | 'POST';

interface Route {
  method: Method;
  pattern: RegExp;
}

const ID = '[A-Za-z0-9_-]{1,128}';

export const ALLOWED_ROUTES: readonly Route[] = [
  { method: 'GET', pattern: /^diagnostics$/ },
  { method: 'GET', pattern: /^tenant$/ },
  { method: 'GET', pattern: /^agent$/ },
  { method: 'GET', pattern: /^knowledge\/documents$/ },
  { method: 'POST', pattern: /^knowledge\/documents$/ },
  { method: 'POST', pattern: new RegExp(`^knowledge/documents/${ID}/retry$`) },
  { method: 'GET', pattern: /^conversations\/\d{1,20}$/ },
  { method: 'POST', pattern: /^conversations\/\d{1,20}\/resume$/ },
  { method: 'GET', pattern: /^webhooks$/ },
];

const WEBHOOK_STATUSES = new Set(['FAILED', 'QUEUED', 'PROCESSED']);

export interface ProxyError {
  status: number;
  error: string;
}

export function isAllowedRoute(method: string, relativePath: string): boolean {
  return ALLOWED_ROUTES.some((route) => route.method === method && route.pattern.test(relativePath));
}

// Returns the upstream query string for allowed query parameters, or null when a parameter is not allowed.
export function sanitizeQuery(relativePath: string, search: string): string | null {
  const params = new URLSearchParams(search);
  if (params.size === 0) return '';
  if (relativePath !== 'webhooks') return null;
  const status = params.get('status');
  if (params.size !== 1 || status === null || !WEBHOOK_STATUSES.has(status)) return null;
  return `status=${status}`;
}

export interface ForwardInput {
  method: Method;
  relativePath: string;
  search: string;
  contentType: string | null;
  contentLength: number | null;
  readBody: () => Promise<ArrayBuffer>;
}

export type ForwardResult =
  | { ok: true; status: number; contentType: string | null; body: ArrayBuffer }
  | { ok: false; error: ProxyError };

export async function forwardToPlatform(input: ForwardInput, fetchImpl: typeof fetch = fetch): Promise<ForwardResult> {
  if (!isAllowedRoute(input.method, input.relativePath)) {
    return { ok: false, error: { status: 404, error: 'not_found' } };
  }
  const query = sanitizeQuery(input.relativePath, input.search);
  if (query === null) {
    return { ok: false, error: { status: 400, error: 'unsupported_query' } };
  }

  let config;
  try {
    config = readServerConfig();
  } catch (err) {
    if (err instanceof ConfigError) {
      return { ok: false, error: { status: 500, error: 'admin_proxy_misconfigured' } };
    }
    throw err;
  }
  if (config.adminApiKey === null) {
    return { ok: false, error: { status: 503, error: 'admin_proxy_not_configured' } };
  }

  const headers = new Headers({ Accept: 'application/json', 'X-Admin-Api-Key': config.adminApiKey });
  let body: ArrayBuffer | undefined;
  if (input.method === 'POST') {
    const contentType = input.contentType ?? '';
    const allowedType = contentType.startsWith('multipart/form-data') || contentType.startsWith('application/json');
    if (!allowedType && input.contentLength !== 0) {
      return { ok: false, error: { status: 415, error: 'unsupported_media_type' } };
    }
    if (input.contentLength !== null && input.contentLength > MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES) {
      return { ok: false, error: { status: 413, error: 'payload_too_large' } };
    }
    body = await input.readBody();
    if (body.byteLength > MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES) {
      return { ok: false, error: { status: 413, error: 'payload_too_large' } };
    }
    if (allowedType) headers.set('Content-Type', contentType);
  }

  const url = new URL(`/api/admin/${input.relativePath}`, config.platformApiBaseUrl);
  if (query !== '') url.search = query;

  try {
    const upstream = await fetchImpl(url, {
      method: input.method,
      headers,
      body,
      cache: 'no-store',
      redirect: 'error',
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    return {
      ok: true,
      status: upstream.status,
      contentType: upstream.headers.get('content-type'),
      body: await upstream.arrayBuffer(),
    };
  } catch (err) {
    const timedOut = err instanceof Error && (err.name === 'TimeoutError' || err.name === 'AbortError');
    return timedOut
      ? { ok: false, error: { status: 504, error: 'platform_timeout' } }
      : { ok: false, error: { status: 502, error: 'platform_unavailable' } };
  }
}
