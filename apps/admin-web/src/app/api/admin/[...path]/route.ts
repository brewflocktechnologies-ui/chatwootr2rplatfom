import { NextRequest, NextResponse } from 'next/server';
import { forwardToPlatform } from '@/lib/server/platform-proxy';
import { checkWriteRequest, readAdminOriginConfig } from '@/lib/server/request-guard';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

type RouteContext = { params: Promise<{ path: string[] }> };

async function handle(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const { path } = await context.params;
  const relativePath = path.join('/');
  const contentLengthHeader = request.headers.get('content-length');
  const contentLength = contentLengthHeader === null ? null : Number(contentLengthHeader);

  const result = await forwardToPlatform({
    method: request.method === 'POST' ? 'POST' : 'GET',
    relativePath,
    search: request.nextUrl.search.slice(1),
    contentType: request.headers.get('content-type'),
    contentLength: contentLength !== null && Number.isFinite(contentLength) ? contentLength : null,
    readBody: () => request.arrayBuffer(),
  });

  if (!result.ok) {
    return NextResponse.json({ error: result.error.error }, { status: result.error.status, headers: noStore() });
  }
  return new NextResponse(result.body, {
    status: result.status,
    headers: {
      ...noStore(),
      ...(result.contentType ? { 'Content-Type': result.contentType } : {}),
    },
  });
}

function noStore(): Record<string, string> {
  return { 'Cache-Control': 'no-store' };
}

export function GET(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  return handle(request, context);
}

// Writes are checked for cross-site origin and admin host before anything is forwarded.
export async function POST(request: NextRequest, context: RouteContext): Promise<NextResponse> {
  const rejection = checkWriteRequest(request.headers, readAdminOriginConfig());
  if (rejection !== null) {
    return NextResponse.json({ error: rejection.error }, { status: rejection.status, headers: noStore() });
  }
  return handle(request, context);
}
