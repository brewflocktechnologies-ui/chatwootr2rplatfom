'use client';

import { useCallback, useEffect, useState } from 'react';
import { requestJson, type ApiFailure } from './browser-api';

export type ResourceState<T> =
  | { status: 'loading' }
  | { status: 'ready'; data: T }
  | { status: 'error'; failure: ApiFailure };

interface Entry<T> {
  key: string;
  state: ResourceState<T>;
}

// Fetches a same-origin JSON resource. `parse` must be a stable (module-level) guard.
// A result is only shown for the request key it belongs to, so a reload or URL change shows
// "loading" until the new response arrives without setting state synchronously inside the effect.
export function useApiResource<T>(
  url: string,
  parse: (value: unknown) => T | null
): { state: ResourceState<T>; reload: () => void } {
  const [version, setVersion] = useState(0);
  const [entry, setEntry] = useState<Entry<T> | null>(null);
  const key = `${url}#${version}`;

  useEffect(() => {
    let cancelled = false;
    void requestJson(url, parse).then((result) => {
      if (cancelled) return;
      const state: ResourceState<T> = result.ok
        ? { status: 'ready', data: result.data }
        : { status: 'error', failure: result.failure };
      setEntry({ key, state });
    });
    return () => {
      cancelled = true;
    };
  }, [key, url, parse]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  const state: ResourceState<T> = entry !== null && entry.key === key ? entry.state : { status: 'loading' };
  return { state, reload };
}
