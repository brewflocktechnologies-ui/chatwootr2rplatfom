import { describe, expect, it } from 'vitest';
import { parseAgent, parseDiagnostics, parseDocuments, parseTenant, parseWidgetConfig } from './contracts';

describe('contract guards', () => {
  it('accepts a well-formed tenant summary and rejects missing fields', () => {
    const tenant = {
      tenantKey: 'acme-demo',
      displayName: 'Acme Demo',
      chatwootAccountId: 4,
      inboxId: 1,
      teamId: 1,
      agentBotId: 1,
      r2rCollectionId: 'c0ffee',
    };
    expect(parseTenant(tenant)).toEqual(tenant);
    expect(parseTenant({ ...tenant, inboxId: undefined })).toBeNull();
    expect(parseTenant(null)).toBeNull();
    expect(parseTenant('acme-demo')).toBeNull();
  });

  it('requires non-empty website token and string base URL for the widget config', () => {
    expect(parseWidgetConfig({ websiteToken: 'tok', chatwootBaseUrl: 'http://localhost:3100' })).toEqual({
      websiteToken: 'tok',
      chatwootBaseUrl: 'http://localhost:3100',
    });
    expect(parseWidgetConfig({ websiteToken: '  ', chatwootBaseUrl: 'http://localhost:3100' })).toBeNull();
    expect(parseWidgetConfig({ websiteToken: 'tok' })).toBeNull();
  });

  it('parses agent summaries only when every field is a string', () => {
    const agent = {
      name: 'Acme assistant',
      model: 'ollama/llama3.2:3b',
      embeddingModel: 'mxbai-embed-large',
      knowledgeScope: 'acme-demo collection',
      handoffPolicy: 'human on request or no evidence',
    };
    expect(parseAgent(agent)).toEqual(agent);
    expect(parseAgent({ ...agent, model: 42 })).toBeNull();
  });

  it('parses diagnostics and rejects entries without a name or state', () => {
    expect(parseDiagnostics({ services: [{ name: 'chatwoot', state: 'UP' }] })).toEqual({
      services: [{ name: 'chatwoot', state: 'UP', detail: null }],
    });
    expect(parseDiagnostics({ services: [{ name: 'chatwoot' }] })).toBeNull();
    expect(parseDiagnostics({})).toBeNull();
  });

  it('accepts both a bare array and a documents envelope', () => {
    const doc = { id: 'd1', filename: 'returns.md', status: 'FAILED' };
    const expected = [{ id: 'd1', filename: 'returns.md', status: 'FAILED', sizeBytes: null, createdAt: null, lastError: null }];
    expect(parseDocuments([doc])).toEqual(expected);
    expect(parseDocuments({ documents: [doc] })).toEqual(expected);
    expect(parseDocuments({ documents: [{ id: 'd1' }] })).toBeNull();
  });
});
