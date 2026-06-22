import { useState } from 'react';
import { MagnifyingGlass, SlidersHorizontal } from '@phosphor-icons/react';

interface Props {
  onSubmit: (query: string, topK: number, filterDocId: string) => void;
  loading: boolean;
}

export function QueryInput({ onSubmit, loading }: Props) {
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(5);
  const [filterDocId, setFilterDocId] = useState('');
  const [showFilters, setShowFilters] = useState(false);

  const handleSubmit = () => {
    if (!query.trim() || loading) return;
    onSubmit(query.trim(), topK, filterDocId.trim());
  };

  return (
    <div className="border border-neutral-800 rounded-lg bg-neutral-900/30 overflow-hidden">
      {/* Main input */}
      <div className="flex items-center gap-3 px-4 py-3">
        <MagnifyingGlass className="w-4.5 h-4.5 text-neutral-500 shrink-0" />
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSubmit();
            }
          }}
          placeholder="输入检索查询..."
          className="flex-1 bg-transparent text-sm text-neutral-200 placeholder:text-neutral-600 outline-none font-mono"
          autoFocus
        />
        <button
          onClick={handleSubmit}
          disabled={!query.trim() || loading}
          className="px-4 py-1.5 text-sm font-medium bg-amber-500 text-black rounded-md hover:bg-amber-400 transition-colors disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
        >
          {loading ? '检索中...' : '检索'}
        </button>
      </div>

      {/* Filters toggle */}
      <div className="border-t border-neutral-800">
        <button
          onClick={() => setShowFilters(!showFilters)}
          className="flex items-center gap-2 px-4 py-2 w-full text-xs text-neutral-500 hover:text-neutral-400 transition-colors"
        >
          <SlidersHorizontal className="w-3.5 h-3.5" />
          过滤选项
          {showFilters ? ' ▲' : ' ▼'}
        </button>

        {showFilters && (
          <div className="px-4 pb-3 flex items-center gap-6">
            {/* TopK slider */}
            <div className="flex items-center gap-3">
              <label className="text-[11px] text-neutral-500 font-mono shrink-0">
                TopK
              </label>
              <input
                type="range"
                min={1}
                max={20}
                value={topK}
                onChange={(e) => setTopK(Number(e.target.value))}
                className="w-28 h-1 accent-amber-500"
              />
              <span className="text-xs font-mono text-amber-400 w-6 text-right">
                {topK}
              </span>
            </div>

            {/* Document filter */}
            <div className="flex items-center gap-3">
              <label className="text-[11px] text-neutral-500 font-mono shrink-0">
                文档 ID
              </label>
              <input
                type="text"
                value={filterDocId}
                onChange={(e) => setFilterDocId(e.target.value)}
                placeholder="(可选)"
                className="w-32 bg-neutral-800 border border-neutral-700 rounded px-2 py-1 text-xs font-mono text-neutral-300 placeholder:text-neutral-600 outline-none focus:border-amber-500/50"
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
