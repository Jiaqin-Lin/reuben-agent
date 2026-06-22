import { useState } from 'react';
import { retrieve } from '../../api/rag';
import { QueryInput } from './QueryInput';
import { ResultsList } from './ResultsList';
import { useToast } from '../shared/Toast';
import type { RetrievalResult } from '../../types/rag';

export function RetrievalTester() {
  const [results, setResults] = useState<RetrievalResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [totalCostMs, setTotalCostMs] = useState<number | undefined>();
  const { toast } = useToast();

  const handleSearch = async (
    query: string,
    topK: number,
    filterDocId: string,
  ) => {
    setLoading(true);
    setHasSearched(true);
    try {
      const filterFields: Record<string, string> | undefined = filterDocId
        ? { documentId: filterDocId }
        : undefined;
      const res = await retrieve({ query, topK, filterFields });
      setResults(res.results ?? []);
      setTotalCostMs(res.totalCostMs);
    } catch (e) {
      toast(e instanceof Error ? e.message : '检索失败', 'error');
      setResults([]);
      setTotalCostMs(undefined);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-lg font-semibold text-neutral-100">召回测试</h1>
        <p className="text-sm text-neutral-500 mt-0.5">
          混合检索 (向量 + 关键词) 测试，观察 RRF 融合召回效果
        </p>
      </div>

      <div className="mb-6">
        <QueryInput onSubmit={handleSearch} loading={loading} />
      </div>

      <ResultsList
        results={results}
        loading={loading}
        hasSearched={hasSearched}
        totalCostMs={totalCostMs}
      />
    </div>
  );
}
