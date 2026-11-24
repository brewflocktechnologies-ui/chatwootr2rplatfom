// Browser-side calls. Only same-origin /api/admin/* and /api/public/* routes are used.

export type ApiFailure =
  | { kind: 'network'; message: string }
  | { kind: 'http'; status: number; message: string }
  | { kind: 'shape'; message: string };

export type ApiResult<T> = { ok: true; data: T } | { ok: false; failure: ApiFailure };

export function describeFailure(failure: ApiFailure): string {
  switch (failure.kind) {
    case 'network':
      return failure.message;
    case 'http':
      return `${failure.message} (HTTP ${failure.status})`;
    case 'shape':
      return failure.message;
  }
}

const ERROR_MESSAGES: Record<string, string> = {
  platform_unavailable: 'The platform API is not reachable.',
  platform_timeout: 'The platform API did not respond in time.',
  admin_proxy_not_configured: 'The admin key is not configured on the server.',
  admin_proxy_misconfigured: 'The admin proxy configuration is invalid.',
  widget_config_unavailable: 'Widget configuration is unavailable.',
  widget_config_invalid: 'Widget configuration from the platform is invalid.',
  payload_too_large: 'The file is too large.',
  unsupported_media_type: 'The request type is not supported.',
  not_found: 'Not found.',
};

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body: unknown = await response.json();
    if (typeof body === 'object' && body !== null && 'error' in body && typeof body.error === 'string') {
      return ERROR_MESSAGES[body.error] ?? 'The request failed.';
    }
  } catch {
    // Non-JSON error bodies are reported by status only.
  }
  return 'The request failed.';
}

export async function requestJson<T>(
  url: string,
  parse: (value: unknown) => T | null,
  init: RequestInit = {}
): Promise<ApiResult<T>> {
  let response: Response;
  try {
    response = await fetch(url, {
      cache: 'no-store',
      ...init,
      headers: { Accept: 'application/json', ...init.headers },
    });
  } catch {
    return { ok: false, failure: { kind: 'network', message: 'Could not reach the Next.js server.' } };
  }
  if (!response.ok) {
    return {
      ok: false,
      failure: { kind: 'http', status: response.status, message: await readErrorMessage(response) },
    };
  }
  // An empty body (for example a bare 202) is passed to the guard as null.
  const text = await response.text();
  let body: unknown = null;
  if (text !== '') {
    try {
      body = JSON.parse(text);
    } catch {
      return { ok: false, failure: { kind: 'shape', message: 'The response was not valid JSON.' } };
    }
  }
  const data = parse(body);
  if (data === null) {
    return { ok: false, failure: { kind: 'shape', message: 'The response shape is not recognised.' } };
  }
  return { ok: true, data };
}
