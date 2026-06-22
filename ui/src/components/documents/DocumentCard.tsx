import { useNavigate } from 'react-router-dom';
import { FileText, File } from '@phosphor-icons/react';
import { StatusBadge } from './StatusBadge';
import type { DocumentDetailVo } from '../../types/document';
import { FileType } from '../../types/enums';

interface Props {
  document: DocumentDetailVo;
}

export function DocumentCard({ document: doc }: Props) {
  const nav = useNavigate();
  const ext = FileType[doc.fileType] ?? 'FILE';
  const Icon = ext === 'PDF' || ext === 'DOCX' ? FileText : File;
  const needsConfirm = doc.strategyStatus === 2;

  return (
    <button
      onClick={() => nav(`/documents/${doc.documentId}`)}
      className="w-full text-left group"
    >
      <div className={`flex items-center gap-4 px-4 py-3.5 rounded-lg border transition-all duration-150 ${
        needsConfirm
          ? 'border-amber-500/40 bg-amber-500/5 hover:border-amber-500/60 hover:bg-amber-500/10'
          : 'border-neutral-800 bg-neutral-900/30 hover:bg-neutral-900/60 hover:border-neutral-700'
      }`}>
        {/* Icon */}
        <div className="w-9 h-9 rounded-md bg-neutral-800 flex items-center justify-center shrink-0 group-hover:bg-neutral-700 transition-colors">
          <Icon className="w-4.5 h-4.5 text-neutral-400" weight="fill" />
        </div>

        {/* Info */}
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-neutral-200 truncate">
            {doc.documentName}
          </p>
          <p className="text-xs text-neutral-600 font-mono mt-0.5">
            ID: {doc.documentId} · {(doc.fileSize / 1024).toFixed(0)} KB ·{' '}
            {doc.charCount?.toLocaleString() ?? 0} 字符
          </p>
        </div>

        {/* Status badges */}
        <div className="flex items-center gap-2 shrink-0">
          <StatusBadge type="parse" code={doc.parseStatus} />
          <StatusBadge type="strategy" code={doc.strategyStatus} />
          <StatusBadge type="index" code={doc.indexStatus} />
        </div>
      </div>
    </button>
  );
}
