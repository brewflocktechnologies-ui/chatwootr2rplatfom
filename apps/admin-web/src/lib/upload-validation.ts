// Client-side upload check. A convenience only: the platform API enforces the same rules.

export const KNOWLEDGE_MAX_BYTES = 2 * 1024 * 1024;
export const KNOWLEDGE_ALLOWED_EXTENSIONS = ['.md', '.txt', '.pdf'] as const;

export type UploadCheck = { ok: true } | { ok: false; message: string };

export function validateKnowledgeFile(file: { name: string; size: number }): UploadCheck {
  const dot = file.name.lastIndexOf('.');
  const extension = dot === -1 ? '' : file.name.slice(dot).toLowerCase();
  if (!(KNOWLEDGE_ALLOWED_EXTENSIONS as readonly string[]).includes(extension)) {
    return { ok: false, message: 'Only .md, .txt and .pdf files can be uploaded.' };
  }
  if (file.size <= 0) {
    return { ok: false, message: 'The selected file is empty.' };
  }
  if (file.size > KNOWLEDGE_MAX_BYTES) {
    return { ok: false, message: 'The file is larger than the 2 MiB limit.' };
  }
  return { ok: true };
}

export function formatBytes(bytes: number | null): string {
  if (bytes === null) return 'unknown size';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
}
