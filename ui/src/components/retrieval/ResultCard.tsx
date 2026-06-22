import { useState } from 'react';
import { CaretDown, CaretRight } from '@phosphor-icons/react';
import { motion } from 'motion/react';
import { cn } from '../../lib/cn';
import type { RetrievalResult } from '../../types/rag';

const SOURCE_STYLE: Record<string, string> = {
  vector: 'bg-amber-500/10 text-amber-400 border-amber-500/30',
  keyword: 'bg-blue-500/10 text-blue-400 border-blue-500/30',
  hybrid: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30',
};

interface Props {
  result: RetrievalResult;
  index: number;
  maxScore: number;
}

export function ResultCard({ result, index, maxScore }: Props) {
  const [expanded, setExpanded] = useState(false);
  const sourceStyle = SOURCE_STYLE[result.source] ?? SOURCE_STYLE.vector;
  // Normalize relative to top result so the best match fills the bar
  const scorePct = maxScore > 0 ? (result.score / maxScore) * 100 : 0;
  const preview =
    result.chunkText?.length > 200
      ? result.chunkText.slice(0, 200) + '...'
      : result.chunkText;

  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3, delay: index * 0.03, ease: [0.16, 1, 0.3, 1] }}
      className="border border-neutral-800 rounded-lg bg-neutral-900/30 overflow-hidden hover:border-neutral-700 transition-colors"
    >
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full text-left p-4"
      >
        {/* Header row */}
        <div className="flex items-center gap-3 mb-2.5">
          {/* Rank */}
          <span className="text-xs font-mono text-neutral-600 w-5 shrink-0">
            {index + 1}
          </span>

          {/* Source badge */}
          <span
            className={cn(
              'inline-flex px-1.5 py-0.5 rounded text-[10px] font-mono border',
              sourceStyle,
            )}
          >
            {result.source}
          </span>

          {/* Section path */}
          {result.sectionPath && (
            <span className="text-[11px] text-neutral-500 font-mono truncate">
              {result.sectionPath}
            </span>
          )}

          {/* Score */}
          <div className="ml-auto flex items-center gap-2 shrink-0">
            <div className="w-16 h-1.5 bg-neutral-800 rounded-full overflow-hidden">
              <div
                className="h-full bg-amber-500 rounded-full transition-all duration-500"
                style={{ width: `${scorePct}%` }}
              />
            </div>
            <span className="text-xs font-mono text-amber-400 w-10 text-right">
              {result.score.toFixed(3)}
            </span>
          </div>

          {/* Expand icon */}
          <span className="text-neutral-600">
            {expanded ? (
              <CaretDown className="w-3.5 h-3.5" />
            ) : (
              <CaretRight className="w-3.5 h-3.5" />
            )}
          </span>
        </div>

        {/* Chunk text preview */}
        <p className="text-xs text-neutral-400 font-mono leading-relaxed line-clamp-3 pl-8">
          {expanded ? result.chunkText : preview}
        </p>

        {/* Footer info */}
        <div className="flex items-center gap-4 mt-2 pl-8 text-[10px] text-neutral-600 font-mono">
          <span>Chunk #{result.chunkId}</span>
          <span>Doc #{result.documentId}</span>
          {result.parentBlockId && <span>Block #{result.parentBlockId}</span>}
        </div>
      </button>
    </motion.div>
  );
}
