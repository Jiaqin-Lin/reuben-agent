export const FILE_LIMITS = {
  maxSize: 50 * 1024 * 1024, // 50MB
  accept: '.pdf,.docx,.txt,.md,.html',
  mimeTypes: [
    'application/pdf',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'text/plain',
    'text/markdown',
    'text/html',
  ],
} as const;

export const POLL_INTERVAL = 5000; // 5 seconds

export const STORAGE_KEY = 'reuben-agent-documents';
