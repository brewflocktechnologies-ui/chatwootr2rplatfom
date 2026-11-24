'use client';

import Link from 'next/link';
import { chatwootDashboardUrl } from '@/lib/chatwoot';
import { parseDiagnostics, parseTenant, parseWidgetConfig, type ServiceHealth } from '@/lib/contracts';
import { describeFailure } from '@/lib/browser-api';
import { useApiResource } from '@/lib/use-api-resource';
import { ErrorState, LoadingState, Panel, StatusBadge } from '@/components/ui';

interface CardSpec {
  label: string;
  keywords: readonly string[];
}

const SERVICE_CARDS: readonly CardSpec[] = [
  { label: 'Chatwoot', keywords: ['chatwoot'] },
  { label: 'R2R knowledge', keywords: ['r2r'] },
  { label: 'Local model (Ollama)', keywords: ['ollama', 'model'] },
];

function findService(services: readonly ServiceHealth[], keywords: readonly string[]): ServiceHealth | undefined {
  return services.find((service) => {
    const name = service.name.toLowerCase();
    return keywords.some((keyword) => name.includes(keyword));
  });
}

export default function AdminOverviewPage() {
  const diagnostics = useApiResource('/api/admin/diagnostics', parseDiagnostics);
  const tenant = useApiResource('/api/admin/tenant', parseTenant);
  const widget = useApiResource('/api/public/widget-config', parseWidgetConfig);

  const services = diagnostics.state.status === 'ready' ? diagnostics.state.data.services : [];

  const dashboardUrl =
    tenant.state.status === 'ready' && widget.state.status === 'ready'
      ? chatwootDashboardUrl(widget.state.data.chatwootBaseUrl, tenant.state.data.chatwootAccountId)
      : null;

  return (
    <div className="stack">
      <h1>Overview</h1>

      <section aria-label="Service health">
        <div className="grid-cards grid-cards--four">
          <article className="card" aria-label="Platform API">
            <h2>Platform API</h2>
            {diagnostics.state.status === 'error' ? (
              <>
                <StatusBadge status="DOWN" />
                <p className="muted">{describeFailure(diagnostics.state.failure)}</p>
              </>
            ) : diagnostics.state.status === 'ready' ? (
              <>
                <StatusBadge status="UP" />
                <p className="muted">Answered the diagnostics request.</p>
              </>
            ) : (
              <LoadingState label="Checking..." />
            )}
          </article>

          {SERVICE_CARDS.map((spec) => {
            const service = diagnostics.state.status === 'ready' ? findService(services, spec.keywords) : undefined;
            return (
              <article className="card" key={spec.label} aria-label={spec.label}>
                <h2>{spec.label}</h2>
                {diagnostics.state.status === 'loading' ? <LoadingState label="Checking..." /> : null}
                {diagnostics.state.status === 'error' ? <p className="muted">Unavailable: the platform API did not respond.</p> : null}
                {diagnostics.state.status === 'ready' && service === undefined ? (
                  <p className="muted">No status reported by the platform.</p>
                ) : null}
                {service !== undefined ? (
                  <>
                    <StatusBadge status={service.state} />
                    {service.detail ? <p className="muted">{service.detail}</p> : null}
                  </>
                ) : null}
              </article>
            );
          })}
        </div>
      </section>

      <Panel title="Tenant">
        {tenant.state.status === 'loading' ? <LoadingState label="Loading tenant..." /> : null}
        {tenant.state.status === 'error' ? (
          <ErrorState title="Tenant overview unavailable" failure={tenant.state.failure} onRetry={tenant.reload} />
        ) : null}
        {tenant.state.status === 'ready' ? (
          <dl className="definition-list">
            <dt>Tenant key</dt>
            <dd>{tenant.state.data.tenantKey}</dd>
            <dt>Display name</dt>
            <dd>{tenant.state.data.displayName}</dd>
            <dt>Chatwoot account</dt>
            <dd>{tenant.state.data.chatwootAccountId}</dd>
            <dt>Inbox</dt>
            <dd>{tenant.state.data.inboxId}</dd>
            <dt>Team</dt>
            <dd>{tenant.state.data.teamId}</dd>
            <dt>Agent bot</dt>
            <dd>{tenant.state.data.agentBotId}</dd>
            <dt>Knowledge collection</dt>
            <dd>{tenant.state.data.r2rCollectionId}</dd>
          </dl>
        ) : null}
      </Panel>

      <Panel title="Quick links">
        <ul className="stack">
          <li>
            {dashboardUrl ? (
              <a href={dashboardUrl} target="_blank" rel="noopener noreferrer">
                Chatwoot dashboard (conversations)
              </a>
            ) : (
              <span className="muted">Chatwoot dashboard unavailable until the platform config loads.</span>
            )}
          </li>
          <li>
            <Link href="/admin/knowledge">Knowledge documents</Link>
          </li>
          <li>
            <Link href="/admin/agent">Agent configuration (read-only)</Link>
          </li>
          <li>
            <Link href="/demo">Customer demo page</Link>
          </li>
        </ul>
      </Panel>
    </div>
  );
}
