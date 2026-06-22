import { useState } from 'react';
import { CaretDown, CaretRight } from '@phosphor-icons/react';
import { cn } from '../../lib/cn';
import { StatusBadge } from './StatusBadge';
import type { DocumentChunk } from '../../types/document';

interface Props {
  chunk: DocumentChunk;
}

export function ChunkRow({ chunk }: Props) {
  const [expanded, setExpanded] = useState(false);
  const preview =
    chunk.chunkText?.length > 150
      ? chunk.chunkText.slice(0, 150) + '...'
      : chunk.chunkText;

  return (
    <div className="border-b border-neutral-800/50 last:border-b-0">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full text-left px-3 py-2.5 flex items-center gap-2.5 hover:bg-neutral-800/30 transition-colors group"
      >
        <span className="text-neutral-600 group-hover:text-neutral-400 transition-colors">
          {expanded ? (
            <CaretDown className="w-3 h-3" />
          ) : (
            <CaretRight className="w-3 h-3" />
          )}
        </span>
        <span className="text-xs font-mono text-neutral-500 w-16 shrink-0">
          #{chunk.chunkNo}
        </span>
        <span className="text-xs text-neutral-400 font-mono truncate flex-1">
          {expanded ? chunk.chunkText?.slice(0, 60) + '...' : preview}
        </span>
        <span className="text-[10px] text-neutral-600 font-mono shrink-0">
          {chunk.charCount} 字
        </span>
        <StatusBadge type="vector" code={chunk.vectorStatus} />
      </button>
      {expanded && (
        <div className="px-10 pb-3">
          <pre className="text-xs text-neutral-300 font-mono whitespace-pre-wrap leading-relaxed bg-neutral-950/50 rounded-md p-3 border border-neutral-800/50 max-h-48 overflow-y-auto">
            {chunk.chunkText}
          </pre>
          <div className="flex items-center gap-4 mt-2 text-[10px] text-neutral-600 font-mono">
            <span>ID: {chunk.id}</span>
            {chunk.sectionPath && (
              <span>路径: {chunk.sectionPath}</span>
            )}
            <span>Tokens: {chunk.tokenCount}</span>
            {chunk.vectorId && <span>Vector: {chunk.vectorId}</span>}
          </div>
        </div>
      )}
    </div>
  );
}
