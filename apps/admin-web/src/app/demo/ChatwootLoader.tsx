'use client';

import { useEffect, useState } from 'react';
import { loadChatwootWebsiteWidget } from '@/lib/chatwoot';
import { parseWidgetConfig } from '@/lib/contracts';
import { requestJson } from '@/lib/browser-api';

type LoaderState = { status: 'loading' } | { status: 'ready' } | { status: 'error'; message: string };

// Fetches the public widget config from the same-origin proxy, then loads Chatwoot's native widget.
export function ChatwootLoader() {
  const [state, setState] = useState<LoaderState>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    void requestJson('/api/public/widget-config', parseWidgetConfig).then((result) => {
      if (cancelled) return;
      if (!result.ok) {
        setState({ status: 'error', message: `Chat is unavailable right now. ${result.failure.kind === 'http' ? result.failure.message : ''}`.trim() });
        return;
      }
      loadChatwootWebsiteWidget(result.data.chatwootBaseUrl, result.data.websiteToken, (message) => {
        if (!cancelled) setState({ status: 'error', message: `Chat is unavailable right now. ${message}` });
      });
      setState({ status: 'ready' });
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.status === 'error') {
    return (
      <p role="alert" className="state state--error">
        {state.message}
      </p>
    );
  }
  return (
    <p className="muted" role="status">
      {state.status === 'loading' ? 'Loading chat...' : 'Chat is loading. Use the chat button in the corner.'}
    </p>
  );
}
