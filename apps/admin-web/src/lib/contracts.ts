// Browser-side view of the platform API contract (docs/architecture/api-contract.md).
// Every value arriving from the network is parsed by a guard below before use.

export interface TenantSummary {
  tenantKey: string;
  displayName: string;
  chatwootAccountId: number;
  inboxId: number;
  teamId: number;
  agentBotId: number;
  r2rCollectionId: string;
}

export interface AgentSummary {
  name: string;
  model: string;
  embeddingModel: string;
  knowledgeScope: string;
  handoffPolicy: string;
}

export interface WidgetConfig {
  websiteToken: string;
  chatwootBaseUrl: string;
}

export interface ServiceHealth {
  name: string;
  state: string;
  detail: string | null;
}

export interface Diagnostics {
  services: ServiceHealth[];
}

export interface KnowledgeDocument {
  id: string;
  filename: string;
  status: string;
  sizeBytes: number | null;
  createdAt: string | null;
  lastError: string | null;
}

type Json = Record<string, unknown>;

function isObject(value: unknown): value is Json {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function optionalString(value: unknown): string | null {
  return isString(value) ? value : null;
}

function optionalNumber(value: unknown): number | null {
  return isFiniteNumber(value) ? value : null;
}

export function parseTenant(value: unknown): TenantSummary | null {
  if (!isObject(value)) return null;
  const { tenantKey, displayName, chatwootAccountId, inboxId, teamId, agentBotId, r2rCollectionId } = value;
  if (
    !isString(tenantKey) ||
    !isString(displayName) ||
    !isFiniteNumber(chatwootAccountId) ||
    !isFiniteNumber(inboxId) ||
    !isFiniteNumber(teamId) ||
    !isFiniteNumber(agentBotId) ||
    !isString(r2rCollectionId)
  ) {
    return null;
  }
  return { tenantKey, displayName, chatwootAccountId, inboxId, teamId, agentBotId, r2rCollectionId };
}

export function parseAgent(value: unknown): AgentSummary | null {
  if (!isObject(value)) return null;
  const { name, model, embeddingModel, knowledgeScope, handoffPolicy } = value;
  if (
    !isString(name) ||
    !isString(model) ||
    !isString(embeddingModel) ||
    !isString(knowledgeScope) ||
    !isString(handoffPolicy)
  ) {
    return null;
  }
  return { name, model, embeddingModel, knowledgeScope, handoffPolicy };
}

export function parseWidgetConfig(value: unknown): WidgetConfig | null {
  if (!isObject(value)) return null;
  const { websiteToken, chatwootBaseUrl } = value;
  if (!isString(websiteToken) || websiteToken.trim() === '' || !isString(chatwootBaseUrl)) return null;
  return { websiteToken, chatwootBaseUrl };
}

export function parseDiagnostics(value: unknown): Diagnostics | null {
  if (!isObject(value) || !Array.isArray(value.services)) return null;
  const services: ServiceHealth[] = [];
  for (const entry of value.services) {
    if (!isObject(entry) || !isString(entry.name) || !isString(entry.state)) return null;
    services.push({ name: entry.name, state: entry.state, detail: optionalString(entry.detail) });
  }
  return { services };
}

function toDocument(value: unknown): KnowledgeDocument | null {
  if (!isObject(value)) return null;
  const { id, filename, status, sizeBytes, createdAt, lastError } = value;
  if (!isString(id) || !isString(filename) || !isString(status)) return null;
  return {
    id,
    filename,
    status,
    sizeBytes: optionalNumber(sizeBytes),
    createdAt: optionalString(createdAt),
    lastError: optionalString(lastError),
  };
}

// The documents list shape is not frozen in api-contract.md yet; accept a bare array or { documents: [] }.
export function parseDocuments(value: unknown): KnowledgeDocument[] | null {
  const list = Array.isArray(value) ? value : isObject(value) && Array.isArray(value.documents) ? value.documents : null;
  if (list === null) return null;
  const documents: KnowledgeDocument[] = [];
  for (const entry of list) {
    const doc = toDocument(entry);
    if (doc === null) return null;
    documents.push(doc);
  }
  return documents;
}
