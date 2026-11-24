import { NextResponse } from 'next/server';
import { readServerConfig } from '@/lib/server/config';
import { parseWidgetConfig } from '@/lib/contracts';
import { normalizeChatwootBaseUrl } from '@/lib/chatwoot';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

// Public, unauthenticated: forwards the platform's widget config (website token + Chatwoot base URL).
// No admin key is attached on this route.
export async function GET(): Promise<NextResponse> {
  let base: URL;
  try {
    base = readServerConfig().platformApiBaseUrl;
  } catch {
    return NextResponse.json({ error: 'widget_config_misconfigured' }, { status: 500, headers: noStore() });
  }

  try {
    const upstream = await fetch(new URL('/api/public/widget-config', base), {
      cache: 'no-store',
      redirect: 'error',
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(10_000),
    });
    if (!upstream.ok) {
      return NextResponse.json({ error: 'widget_config_unavailable' }, { status: 502, headers: noStore() });
    }
    const config = parseWidgetConfig(await upstream.json());
    if (config === null || normalizeChatwootBaseUrl(config.chatwootBaseUrl) === null) {
      return NextResponse.json({ error: 'widget_config_invalid' }, { status: 502, headers: noStore() });
    }
    return NextResponse.json(config, { headers: noStore() });
  } catch {
    return NextResponse.json({ error: 'platform_unavailable' }, { status: 502, headers: noStore() });
  }
}

function noStore(): Record<string, string> {
  return { 'Cache-Control': 'no-store' };
}
