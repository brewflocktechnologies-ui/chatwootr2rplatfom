import type { ReactNode } from 'react';
import { describeFailure, type ApiFailure } from '@/lib/browser-api';

export type BadgeTone = 'ok' | 'warn' | 'bad' | 'unknown';

const TONE_CLASS: Record<BadgeTone, string> = {
  ok: 'badge badge--ok',
  warn: 'badge badge--warn',
  bad: 'badge badge--bad',
  unknown: 'badge',
};

// Maps platform status words to a visual tone. Unrecognised words stay neutral.
export function toneFor(status: string): BadgeTone {
  const s = status.toUpperCase();
  if (['UP', 'OK', 'HEALTHY', 'SUCCESS', 'PROCESSED', 'AI_ACTIVE', 'READY'].includes(s)) return 'ok';
  if (['DOWN', 'FAILED', 'ERROR', 'UNHEALTHY'].includes(s)) return 'bad';
  if (['DEGRADED', 'PENDING', 'QUEUED', 'PROCESSING', 'HANDOFF_PENDING', 'HUMAN_ACTIVE'].includes(s)) return 'warn';
  return 'unknown';
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={TONE_CLASS[toneFor(status)]}>{status}</span>;
}

export function LoadingState({ label }: { label: string }) {
  return (
    <p className="state muted" role="status">
      {label}
    </p>
  );
}

export function ErrorState({
  title,
  failure,
  onRetry,
}: {
  title: string;
  failure: ApiFailure;
  onRetry?: () => void;
}) {
  return (
    <div className="state state--error" role="alert">
      <strong>{title}</strong>
      <div>{describeFailure(failure)}</div>
      {onRetry ? (
        <p>
          <button type="button" className="secondary" onClick={onRetry}>
            Try again
          </button>
        </p>
      ) : null}
    </div>
  );
}

export function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="panel" aria-label={title}>
      <h2>{title}</h2>
      {children}
    </section>
  );
}
