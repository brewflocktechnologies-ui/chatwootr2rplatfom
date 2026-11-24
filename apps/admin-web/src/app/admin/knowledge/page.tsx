'use client';

import { useState, type ChangeEvent, type FormEvent } from 'react';
import { describeFailure, requestJson } from '@/lib/browser-api';
import { parseDocuments, type KnowledgeDocument } from '@/lib/contracts';
import { formatBytes, KNOWLEDGE_MAX_BYTES, validateKnowledgeFile } from '@/lib/upload-validation';
import { useApiResource } from '@/lib/use-api-resource';
import { ErrorState, LoadingState, Panel, StatusBadge } from '@/components/ui';

const acceptAnyJson = (value: unknown): unknown => value;
const DOCUMENTS_URL = '/api/admin/knowledge/documents';

interface Feedback {
  tone: 'ok' | 'error';
  message: string;
}

export default function KnowledgePage() {
  const { state, reload } = useApiResource(DOCUMENTS_URL, parseDocuments);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [retryingId, setRetryingId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);

  function onFileChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0] ?? null;
    setFeedback(null);
    if (selected === null) {
      setFile(null);
      setFileError(null);
      return;
    }
    const check = validateKnowledgeFile(selected);
    setFile(check.ok ? selected : null);
    setFileError(check.ok ? null : check.message);
  }

  async function onUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (file === null || uploading) return;
    setUploading(true);
    setFeedback(null);
    const body = new FormData();
    body.append('file', file, file.name);
    const result = await requestJson(DOCUMENTS_URL, acceptAnyJson, { method: 'POST', body });
    setUploading(false);
    if (result.ok) {
      setFeedback({ tone: 'ok', message: `Uploaded ${file.name}. Ingestion status will update below.` });
      setFile(null);
      setFileError(null);
      (event.currentTarget as HTMLFormElement).reset();
      reload();
    } else {
      setFeedback({ tone: 'error', message: `Upload failed: ${describeFailure(result.failure)}` });
    }
  }

  async function onRetry(doc: KnowledgeDocument) {
    setRetryingId(doc.id);
    setFeedback(null);
    const result = await requestJson(
      `${DOCUMENTS_URL}/${encodeURIComponent(doc.id)}/retry`,
      acceptAnyJson,
      { method: 'POST' }
    );
    setRetryingId(null);
    if (result.ok) {
      setFeedback({ tone: 'ok', message: `Retry requested for ${doc.filename}.` });
      reload();
    } else {
      setFeedback({ tone: 'error', message: `Retry failed: ${describeFailure(result.failure)}` });
    }
  }

  return (
    <div className="stack">
      <h1>Knowledge</h1>
      <p className="muted">
        Upload .md, .txt or .pdf files up to 2 MiB into the acme-demo knowledge collection. Files are treated as
        untrusted data at retrieval time.
      </p>

      <Panel title="Upload a document">
        <form onSubmit={onUpload} className="stack">
          <label>
            <span className="muted">File (.md, .txt, .pdf, max 2 MiB)</span>
            <br />
            <input
              type="file"
              accept=".md,.txt,.pdf,text/markdown,text/plain,application/pdf"
              onChange={onFileChange}
              disabled={uploading}
              aria-describedby="upload-help"
            />
          </label>
          <p id="upload-help" className="muted">
            Limit {formatBytes(KNOWLEDGE_MAX_BYTES)}. The platform re-checks type and size.
          </p>
          {fileError ? (
            <p role="alert" className="state state--error">
              {fileError}
            </p>
          ) : null}
          <div>
            <button type="submit" disabled={file === null || uploading}>
              {uploading ? 'Uploading...' : 'Upload'}
            </button>
          </div>
          {feedback ? (
            <p role={feedback.tone === 'error' ? 'alert' : 'status'} className={feedback.tone === 'error' ? 'state state--error' : 'state'}>
              {feedback.message}
            </p>
          ) : null}
        </form>
      </Panel>

      <Panel title="Documents">
        <div className="row-actions">
          <button type="button" className="secondary" onClick={reload} disabled={state.status === 'loading'}>
            Refresh
          </button>
        </div>
        {state.status === 'loading' ? <LoadingState label="Loading documents..." /> : null}
        {state.status === 'error' ? (
          <ErrorState title="Documents are unavailable" failure={state.failure} onRetry={reload} />
        ) : null}
        {state.status === 'ready' && state.data.length === 0 ? (
          <p className="muted">No documents uploaded yet.</p>
        ) : null}
        {state.status === 'ready' && state.data.length > 0 ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">File</th>
                  <th scope="col">Status</th>
                  <th scope="col">Size</th>
                  <th scope="col">Created</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                {state.data.map((doc) => (
                  <tr key={doc.id}>
                    <td>
                      {doc.filename}
                      {doc.lastError ? <div className="muted">{doc.lastError}</div> : null}
                    </td>
                    <td>
                      <StatusBadge status={doc.status} />
                    </td>
                    <td>{formatBytes(doc.sizeBytes)}</td>
                    <td>{doc.createdAt ?? 'unknown'}</td>
                    <td>
                      {doc.status.toUpperCase() === 'FAILED' ? (
                        <button
                          type="button"
                          className="secondary"
                          onClick={() => void onRetry(doc)}
                          disabled={retryingId !== null}
                        >
                          {retryingId === doc.id ? 'Retrying...' : 'Retry'}
                        </button>
                      ) : (
                        <span className="muted">none</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </Panel>
    </div>
  );
}

