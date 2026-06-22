import { useEffect, useRef } from 'react';
import { useApi } from '../../hooks/useApi';
import { getDocument, getStrategyPlans } from '../../api/document';
import { DocumentMeta } from './DocumentMeta';
import { ParentBlockPanel } from './ParentBlockPanel';
import { StrategyPanel } from './StrategyPanel';
import { StatusBadge } from './StatusBadge';
import { ArrowLeft } from '@phosphor-icons/react';
import { useNavigate, useParams } from 'react-router-dom';

const POLL_MS = 5000;

export function DocumentDetail() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const docId = id!;

  const {
    data: document,
    loading: docLoading,
    error: docError,
    refetch: refetchDoc,
  } = useApi(() => getDocument(docId), [docId]);

  const {
    data: plans,
    loading: plansLoading,
    refetch: refetchPlans,
  } = useApi(() => getStrategyPlans(docId), [docId]);

  // Poll while document is in progress (parse or index not yet done)
  const timerRef = useRef<ReturnType<typeof setInterval>>(undefined);
  useEffect(() => {
    if (!document) return;
    const inProgress =
      document.parseStatus < 3 ||
      document.strategyStatus < 2 ||
      document.indexStatus < 3;
    if (inProgress) {
      timerRef.current = setInterval(() => {
        refetchDoc();
        refetchPlans();
      }, POLL_MS);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [document?.parseStatus, document?.strategyStatus, document?.indexStatus, refetchDoc, refetchPlans]);

  if (docLoading) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-6 w-48 bg-neutral-800 rounded" />
        <div className="grid grid-cols-4 gap-3">
          {[1, 2, 3, 4, 5, 6, 7, 8].map((i) => (
            <div key={i} className="h-16 bg-neutral-800 rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  if (docError || !document) {
    return (
      <div className="text-center py-20">
        <p className="text-red-400 text-sm">{docError ?? '文档不存在'}</p>
        <button
          onClick={() => nav('/documents')}
          className="mt-4 text-sm text-neutral-400 hover:text-neutral-200 transition-colors"
        >
          返回文档列表
        </button>
      </div>
    );
  }

  return (
    <div>
      {/* Header */}
      <div className="flex items-center gap-4 mb-6">
        <button
          onClick={() => nav('/documents')}
          className="p-1.5 rounded-md hover:bg-neutral-800 text-neutral-500 hover:text-neutral-300 transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1">
          <div className="flex items-center gap-3">
            <h1 className="text-lg font-semibold text-neutral-100">
              {document.documentName}
            </h1>
            <div className="flex items-center gap-1.5">
              <StatusBadge type="parse" code={document.parseStatus} />
              <StatusBadge type="strategy" code={document.strategyStatus} />
              <StatusBadge type="index" code={document.indexStatus} />
            </div>
          </div>
          {document.originalFileName && (
            <p className="text-xs text-neutral-500 mt-0.5">
              原始文件: {document.originalFileName}
            </p>
          )}
        </div>
      </div>

      {/* Sections */}
      <div className="space-y-8">
        <DocumentMeta document={document} />
        <ParentBlockPanel
          parentBlocks={document.parentBlocks ?? []}
          chunks={document.chunks ?? []}
        />
        {plansLoading ? (
          <div className="h-24 bg-neutral-800 rounded-lg animate-pulse" />
        ) : (
          <StrategyPanel
            documentId={docId}
            plans={plans ?? []}
            onConfirmed={() => {
              refetchDoc();
              refetchPlans();
            }}
          />
        )}
      </div>
    </div>
  );
}
