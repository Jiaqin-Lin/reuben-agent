import { MagnifyingGlass } from '@phosphor-icons/react';
import { ResultCard } from './ResultCard';
import { EmptyState } from '../shared/EmptyState';
import type { RetrievalResult } from '../../types/rag';

interface Props {
  results: RetrievalResult[];
  loading: boolean;
  hasSearched: boolean;
  totalCostMs?: number;
}

export function ResultsList({ results, loading, hasSearched, totalCostMs }: Props) {
  if (loading) {
    return (
      <div className="space-y-2">
        {[1, 2, 3, 4, 5].map((i) => (
          <div
            key={i}
            className="h-[80px] rounded-lg bg-neutral-900/30 border border-neutral-800 animate-pulse"
          />
        ))}
      </div>
    );
  }

  if (!hasSearched) {
    return (
      <EmptyState
        icon={MagnifyingGlass}
        title="输入查询开始检索"
        description="系统将通过向量检索 + 关键词检索进行混合召回，并使用 RRF 融合结果"
      />
    );
  }

  if (results.length === 0) {
    return (
      <EmptyState
        icon={MagnifyingGlass}
        title="无召回结果"
        description="尝试调整查询或确认文档已完成向量化索引"
      />
    );
  }

  const maxScore = results.length > 0
    ? Math.max(...results.map((r) => r.score))
    : 0;

  return (
    <div>
      {/* Summary bar */}
      {totalCostMs != null && (
        <div className="flex items-center gap-2 mb-3 text-xs font-mono">
          <span className="text-neutral-500">
            {results.length} 条结果
          </span>
          <span className="text-neutral-700">·</span>
          <span className={totalCostMs > 1000 ? 'text-amber-400' : 'text-neutral-500'}>
            {totalCostMs >= 1000
              ? `${(totalCostMs / 1000).toFixed(2)}s`
              : `${totalCostMs}ms`}
          </span>
        </div>
      )}

      {/* Results */}
      <div className="space-y-2">
        {results.map((result, i) => (
          <ResultCard key={result.chunkId} result={result} index={i} maxScore={maxScore} />
        ))}
      </div>
    </div>
  );
}
