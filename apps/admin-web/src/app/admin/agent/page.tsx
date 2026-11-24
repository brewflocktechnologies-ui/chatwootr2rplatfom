'use client';

import { parseAgent } from '@/lib/contracts';
import { useApiResource } from '@/lib/use-api-resource';
import { ErrorState, LoadingState, Panel } from '@/components/ui';

export default function AgentPage() {
  const { state, reload } = useApiResource('/api/admin/agent', parseAgent);

  return (
    <div className="stack">
      <h1>Agent</h1>
      <p className="muted">
        Read-only. Prompt editing is outside the MVP scope. Changes to the agent are made by the platform
        configuration, not in this console.
      </p>

      <Panel title="Configured agent">
        {state.status === 'loading' ? <LoadingState label="Loading agent configuration..." /> : null}
        {state.status === 'error' ? (
          <ErrorState title="Agent configuration is unavailable" failure={state.failure} onRetry={reload} />
        ) : null}
        {state.status === 'ready' ? (
          <dl className="definition-list">
            <dt>Name</dt>
            <dd>{state.data.name}</dd>
            <dt>Generation model</dt>
            <dd>{state.data.model}</dd>
            <dt>Embedding model</dt>
            <dd>{state.data.embeddingModel}</dd>
            <dt>Knowledge scope</dt>
            <dd>{state.data.knowledgeScope}</dd>
            <dt>Handoff policy</dt>
            <dd>{state.data.handoffPolicy}</dd>
          </dl>
        ) : null}
      </Panel>
    </div>
  );
}
