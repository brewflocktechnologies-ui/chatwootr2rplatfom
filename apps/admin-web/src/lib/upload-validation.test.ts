import { describe, expect, it } from 'vitest';
import { KNOWLEDGE_MAX_BYTES, formatBytes, validateKnowledgeFile } from './upload-validation';

describe('validateKnowledgeFile', () => {
  it('accepts .md, .txt and .pdf up to the limit, case-insensitively', () => {
    expect(validateKnowledgeFile({ name: 'returns.md', size: 10 })).toEqual({ ok: true });
    expect(validateKnowledgeFile({ name: 'faq.TXT', size: 10 })).toEqual({ ok: true });
    expect(validateKnowledgeFile({ name: 'policy.pdf', size: KNOWLEDGE_MAX_BYTES })).toEqual({ ok: true });
  });

  it('rejects other extensions, empty files and files over 2 MiB', () => {
    expect(validateKnowledgeFile({ name: 'run.exe', size: 10 }).ok).toBe(false);
    expect(validateKnowledgeFile({ name: 'noextension', size: 10 }).ok).toBe(false);
    expect(validateKnowledgeFile({ name: 'empty.md', size: 0 }).ok).toBe(false);
    expect(validateKnowledgeFile({ name: 'big.md', size: KNOWLEDGE_MAX_BYTES + 1 }).ok).toBe(false);
  });
});

describe('formatBytes', () => {
  it('formats sizes and marks unknown sizes', () => {
    expect(formatBytes(null)).toBe('unknown size');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2.0 KiB');
    expect(formatBytes(KNOWLEDGE_MAX_BYTES)).toBe('2.00 MiB');
  });
});
