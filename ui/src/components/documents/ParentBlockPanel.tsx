import { useState } from 'react';
import { CaretDown, CaretRight, TextAlignLeft } from '@phosphor-icons/react';
import { ChunkRow } from './ChunkRow';
import { EmptyState } from '../shared/EmptyState';
import type { DocumentParentBlock, DocumentChunk } from '../../types/document';

interface Props {
  parentBlocks: DocumentParentBlock[];
  chunks: DocumentChunk[];
}

export function ParentBlockPanel({ parentBlocks, chunks }: Props) {
  const [expandedBlocks, setExpandedBlocks] = useState<Set<string>>(new Set());

  const toggleBlock = (blockId: string) => {
    setExpandedBlocks((prev) => {
      const next = new Set(prev);
      if (next.has(blockId)) next.delete(blockId);
      else next.add(blockId);
      return next;
    });
  };

  if (parentBlocks.length === 0) {
    return (
      <div>
        <h2 className="text-sm font-medium text-neutral-400 uppercase tracking-wider mb-3">
          文档分块
        </h2>
        <div className="border border-neutral-800 rounded-lg bg-neutral-900/30">
          <EmptyState
            icon={TextAlignLeft}
            title="暂无分块数据"
            description="确认策略后系统将自动生成文档分块并完成向量化"
          />
        </div>
      </div>
    );
  }

  const chunksByParent = new Map<string, DocumentChunk[]>();
  for (const c of chunks) {
    const list = chunksByParent.get(c.parentBlockId) ?? [];
    list.push(c);
    chunksByParent.set(c.parentBlockId, list);
  }

  return (
    <div>
      <h2 className="text-sm font-medium text-neutral-400 uppercase tracking-wider mb-3">
        文档分块 ({parentBlocks.length} 父块, {chunks.length} 子块)
      </h2>
      <div className="border border-neutral-800 rounded-lg bg-neutral-900/30 divide-y divide-neutral-800/50 overflow-hidden">
        {parentBlocks.map((block) => {
          const blockChunks = chunksByParent.get(block.id) ?? [];
          const isExpanded = expandedBlocks.has(block.id);

          return (
            <div key={block.id}>
              {/* Block header */}
              <button
                onClick={() => toggleBlock(block.id)}
                className="w-full text-left px-4 py-3 flex items-center gap-3 hover:bg-neutral-800/30 transition-colors group"
              >
                <span className="text-neutral-500 group-hover:text-neutral-400">
                  {isExpanded ? (
                    <CaretDown className="w-3.5 h-3.5" />
                  ) : (
                    <CaretRight className="w-3.5 h-3.5" />
                  )}
                </span>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-mono text-amber-500 shrink-0">
                      Block {block.parentNo}
                    </span>
                    {block.sectionPath && (
                      <span className="text-xs text-neutral-500 font-mono truncate">
                        {block.sectionPath}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-neutral-400 truncate mt-0.5">
                    {block.parentText?.slice(0, 100)}
                  </p>
                </div>
                <div className="flex items-center gap-3 text-[10px] text-neutral-600 font-mono shrink-0">
                  <span>{block.charCount} 字</span>
                  <span>{blockChunks.length} chunks</span>
                </div>
              </button>

              {/* Chunks */}
              {isExpanded && (
                <div className="border-t border-neutral-800/50 bg-neutral-950/30">
                  {blockChunks.length > 0 ? (
                    blockChunks.map((chunk) => (
                      <ChunkRow key={chunk.id} chunk={chunk} />
                    ))
                  ) : (
                    <p className="text-xs text-neutral-600 px-4 py-3">
                      暂无子块
                    </p>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
