// Cross-site write protection for same-origin /api/admin/* routes.
// Writes (POST, PUT, PATCH, DELETE) must come from the admin console's own origin and must carry the
// configured admin Host. Reads (GET) are not checked here.

const DEFAULT_ADMIN_ORIGINS = ['http://localhost:4300', 'http://127.0.0.1:4300'] as const;

export interface AdminOriginConfig {
  origins: ReadonlySet<string>;
  hosts: ReadonlySet<string>;
}

export type GuardRejection = { status: 403; error: 'forbidden_host' | 'forbidden_origin' };

export function readAdminOriginConfig(env: Readonly<Record<string, string | undefined>> = process.env): AdminOriginConfig {
  const origins = new Set<string>(DEFAULT_ADMIN_ORIGINS);
  const configured = env.ADMIN_WEB_ORIGIN?.trim() ?? '';
  if (configured !== '') {
    const url = new URL(configured);
    origins.add(url.origin);
  }
  const hosts = new Set<string>();
  for (const origin of origins) {
    hosts.add(new URL(origin).host.toLowerCase());
  }
  return { origins, hosts };
}

export function isWriteMethod(method: string): boolean {
  return method === 'POST' || method === 'PUT' || method === 'PATCH' || method === 'DELETE';
}

// Returns null when the write may proceed, otherwise the rejection to send.
export function checkWriteRequest(headers: Headers, config: AdminOriginConfig): GuardRejection | null {
  const host = (headers.get('host') ?? '').trim().toLowerCase();
  if (!config.hosts.has(host)) {
    return { status: 403, error: 'forbidden_host' };
  }

  const fetchSite = headers.get('sec-fetch-site');
  if (fetchSite === 'cross-site') {
    return { status: 403, error: 'forbidden_origin' };
  }

  const origin = headers.get('origin');
  if (origin !== null) {
    return config.origins.has(origin) ? null : { status: 403, error: 'forbidden_origin' };
  }

  // No Origin header: only a direct navigation or same-origin fetch that browsers mark with Sec-Fetch-Site.
  if (fetchSite === 'same-origin' || fetchSite === 'none') {
    return null;
  }
  return { status: 403, error: 'forbidden_origin' };
}
