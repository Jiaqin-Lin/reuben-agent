import { useState, useEffect, useCallback } from 'react';
import { Files } from '@phosphor-icons/react';
import { getDocument } from '../../api/document';
import { STORAGE_KEY, POLL_INTERVAL } from '../../lib/constants';
import { DocumentCard } from './DocumentCard';
import { EmptyState } from '../shared/EmptyState';
import type { StoredDocument, DocumentDetailVo } from '../../types/document';

interface Props {
  refreshKey: number;
}

export function DocumentList({ refreshKey }: Props) {
  const [docs, setDocs] = useState<DocumentDetailVo[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchAll = useCallback(async () => {
    const stored: StoredDocument[] = JSON.parse(
      localStorage.getItem(STORAGE_KEY) ?? '[]',
    );
    if (stored.length === 0) {
      setDocs([]);
      setLoading(false);
      return;
    }
    const results = await Promise.allSettled(
      stored.map((s) => getDocument(s.documentId)),
    );
    const loaded = results
      .filter(
        (r): r is PromiseFulfilledResult<DocumentDetailVo> =>
          r.status === 'fulfilled',
      )
      .map((r) => r.value);
    setDocs(loaded);
    setLoading(false);
  }, []);

  useEffect(() => {
    setLoading(true);
    fetchAll();
  }, [fetchAll, refreshKey]);

  // Poll in-progress docs
  useEffect(() => {
    const hasInProgress = docs.some(
      (d) => d.parseStatus < 3 || d.indexStatus < 3,
    );
    if (!hasInProgress) return;
    const timer = setInterval(fetchAll, POLL_INTERVAL);
    return () => clearInterval(timer);
  }, [docs, fetchAll]);

  if (loading) {
    return (
      <div className="space-y-2">
        {[1, 2, 3].map((i) => (
          <div
            key={i}
            className="h-[60px] rounded-lg bg-neutral-900/30 border border-neutral-800 animate-pulse"
          />
        ))}
      </div>
    );
  }

  if (docs.length === 0) {
    return (
      <EmptyState
        icon={Files}
        title="暂无文档"
        description="上传一个文档开始 RAG 测试"
      />
    );
  }

  return (
    <div className="space-y-1.5">
      {docs.map((doc) => (
        <DocumentCard key={doc.documentId} document={doc} />
      ))}
    </div>
  );
}
