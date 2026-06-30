import { useCallback, useEffect, useState } from 'react';
import { CaretDown, CaretRight } from '@phosphor-icons/react';
import { cn } from '../../../lib/cn';
import { listChunks, getChunkDetail } from '../../../api/document';
import { useToast } from '../../shared/Toast';
import { StatusBadge } from '../StatusBadge';
import { Drawer } from '../../admin/Drawer';
import type {
  DocumentChunkVo,
  DocumentChunkDetailVo,
} from '../../../types/document';

interface Props {
  documentId: string;
  indexReady: boolean;
}

const PAGE_SIZE_OPTIONS = [10, 20, 50];

export function ChunkWorkbench({ documentId, indexReady }: Props) {
  const [pageNo, setPageNo] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [view, setView] = useState<'grouped' | 'flat'>('grouped');
  const [data, setData] = useState<DocumentChunkVo[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());
  const [drawerChunkId, setDrawerChunkId] = useState<string | null>(null);
  const { toast } = useToast();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await listChunks(documentId, undefined, pageNo, pageSize);
      setData(page.records ?? []);
      setTotal(page.total ?? 0);
    } catch (e) {
      toast(e instanceof Error ? e.message : '加载分块失败', 'error');
      setData([]);
    } finally {
      setLoading(false);
    }
  }, [documentId, pageNo, pageSize, toast]);

  useEffect(() => {
    load();
  }, [load]);

  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const stats = computeStats(data);
  const groups = groupByParent(data);

  const toggleGroup = (key: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const setAllCollapsed = (collapsed: boolean) => {
    setCollapsedGroups(collapsed ? new Set(groups.map((g) => g.key)) : new Set());
  };

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setView('grouped')}
            className={cn(
              'px-2.5 py-1 text-[11px] rounded-md border transition-colors',
              view === 'grouped'
                ? 'border-amber-500/40 bg-amber-500/10 text-amber-300'
                : 'border-neutral-800 text-neutral-400 hover:border-neutral-700',
            )}
          >
            按父块分组
          </button>
          <button
            onClick={() => setView('flat')}
            className={cn(
              'px-2.5 py-1 text-[11px] rounded-md border transition-colors',
              view === 'flat'
                ? 'border-amber-500/40 bg-amber-500/10 text-amber-300'
                : 'border-neutral-800 text-neutral-400 hover:border-neutral-700',
            )}
          >
            平铺列表
          </button>
          {view === 'grouped' && groups.length > 0 && (
            <>
              <button
                onClick={() => setAllCollapsed(false)}
                className="px-2.5 py-1 text-[11px] rounded-md border border-neutral-800 text-neutral-400 hover:border-neutral-700"
              >
                展开全部
              </button>
              <button
                onClick={() => setAllCollapsed(true)}
                className="px-2.5 py-1 text-[11px] rounded-md border border-neutral-800 text-neutral-400 hover:border-neutral-700"
              >
                折叠全部
              </button>
            </>
          )}
        </div>
        <span className="text-[11px] font-mono text-neutral-500">
          共 {total} 条 · 第 {pageNo}/{totalPages} 页
        </span>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-12 rounded-lg bg-neutral-900/30 border border-neutral-800 animate-pulse" />
          ))}
        </div>
      ) : data.length === 0 ? (
        <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-8 text-center">
          <p className="text-sm text-neutral-500">
            {indexReady ? '当前页无分块数据' : '暂无 Chunk 数据，请先完成索引构建'}
          </p>
        </div>
      ) : (
        <>
          {/* 统计卡 */}
          <div className="grid grid-cols-2 md:grid-cols-5 gap-2">
            <StatCard label="父块数" value={stats.parentCount} />
            <StatCard label="总片段" value={stats.total} />
            <StatCard label="向量可用" value={stats.vectorReady} />
            <StatCard label="待处理" value={stats.pending} />
            <StatCard label="平均 Token" value={stats.avgTokens} />
          </div>

          {view === 'grouped' ? (
            <div className="space-y-2">
              {groups.map((g) => {
                const collapsed = collapsedGroups.has(g.key);
                return (
                  <div
                    key={g.key}
                    className="rounded-lg border border-neutral-800 bg-neutral-900/30 overflow-hidden"
                  >
                    <button
                      onClick={() => toggleGroup(g.key)}
                      className="w-full text-left px-4 py-2.5 flex items-center gap-2 hover:bg-neutral-800/30 transition-colors"
                    >
                      {collapsed ? (
                        <CaretRight className="w-3.5 h-3.5 text-neutral-500" />
                      ) : (
                        <CaretDown className="w-3.5 h-3.5 text-neutral-500" />
                      )}
                      <span className="text-xs font-mono text-amber-500">
                        P#{g.parentBlockNo ?? '-'}
                      </span>
                      <span className="text-xs text-neutral-500 font-mono truncate flex-1">
                        {g.sectionPath || '未识别章节'}
                      </span>
                      <span className="text-[10px] text-neutral-600 font-mono">
                        子块 {g.items.length}/{g.parentChildCount ?? g.items.length}
                      </span>
                    </button>
                    {!collapsed && (
                      <div className="border-t border-neutral-800/50">
                        {g.items.map((c) => (
                          <ChunkRow key={c.chunkId} chunk={c} onClick={() => setDrawerChunkId(c.chunkId)} />
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 overflow-hidden divide-y divide-neutral-800/50">
              {data.map((c) => (
                <ChunkRow key={c.chunkId} chunk={c} onClick={() => setDrawerChunkId(c.chunkId)} />
              ))}
            </div>
          )}

          {/* 分页 */}
          <div className="flex items-center justify-between gap-2 pt-2">
            <button
              onClick={() => setPageNo((p) => Math.max(1, p - 1))}
              disabled={pageNo <= 1 || loading}
              className="px-3 py-1.5 text-xs rounded-md border border-neutral-800 text-neutral-300 hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              上一页
            </button>
            <div className="flex items-center gap-2 text-[11px] font-mono text-neutral-500">
              <span>每页</span>
              <select
                value={pageSize}
                onChange={(e) => {
                  setPageSize(Number(e.target.value));
                  setPageNo(1);
                }}
                className="bg-neutral-900 border border-neutral-800 rounded px-1.5 py-0.5 text-neutral-300"
              >
                {PAGE_SIZE_OPTIONS.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
            <button
              onClick={() => setPageNo((p) => Math.min(totalPages, p + 1))}
              disabled={pageNo >= totalPages || loading}
              className="px-3 py-1.5 text-xs rounded-md border border-neutral-800 text-neutral-300 hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              下一页
            </button>
          </div>
        </>
      )}

      <ChunkDetailDrawer
        chunkId={drawerChunkId}
        onClose={() => setDrawerChunkId(null)}
      />
    </section>
  );
}

function ChunkRow({
  chunk,
  onClick,
}: {
  chunk: DocumentChunkVo;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className="w-full text-left px-4 py-2.5 flex items-center gap-3 hover:bg-neutral-800/30 transition-colors"
    >
      <span className="text-xs font-mono text-neutral-500 w-16 shrink-0">
        C#{chunk.chunkNo}
      </span>
      <span className="text-xs text-neutral-400 font-mono truncate flex-1">
        {chunk.sectionPath || '未识别章节'}
      </span>
      <span className="text-[10px] text-neutral-600 font-mono shrink-0">{chunk.tokenCount} tok</span>
      <StatusBadge type="vector" code={chunk.vectorStatus} />
    </button>
  );
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 px-3 py-2">
      <p className="text-[10px] font-mono text-neutral-600">{label}</p>
      <p className="text-sm font-semibold text-neutral-200">{value.toLocaleString()}</p>
    </div>
  );
}

interface ChunkGroup {
  key: string;
  parentBlockId?: string;
  parentBlockNo?: number;
  parentChildCount?: number;
  sectionPath?: string;
  items: DocumentChunkVo[];
}

function groupByParent(records: DocumentChunkVo[]): ChunkGroup[] {
  const map = new Map<string, ChunkGroup>();
  for (const c of records) {
    const key = c.parentBlockId || `unbound-${c.chunkId}`;
    if (!map.has(key)) {
      map.set(key, {
        key,
        parentBlockId: c.parentBlockId,
        parentBlockNo: c.parentBlockNo,
        parentChildCount: c.parentChildCount,
        sectionPath: c.sectionPath,
        items: [],
      });
    }
    map.get(key)!.items.push(c);
  }
  return Array.from(map.values());
}

function computeStats(records: DocumentChunkVo[]) {
  const parentSet = new Set(records.map((r) => r.parentBlockId).filter(Boolean));
  const vectorReady = records.filter((r) => r.vectorStatus === 3).length;
  const pending = records.filter((r) => r.vectorStatus !== 3).length;
  const totalTokens = records.reduce((sum, r) => sum + (r.tokenCount || 0), 0);
  const avgTokens = records.length ? Math.round(totalTokens / records.length) : 0;
  return {
    parentCount: parentSet.size,
    total: records.length,
    vectorReady,
    pending,
    avgTokens,
  };
}

function ChunkDetailDrawer({
  chunkId,
  onClose,
}: {
  chunkId: string | null;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<DocumentChunkDetailVo | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!chunkId) {
      setDetail(null);
      return;
    }
    let alive = true;
    setLoading(true);
    getChunkDetail(chunkId)
      .then((d) => {
        if (alive) setDetail(d);
      })
      .catch(() => {
        if (alive) setDetail(null);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [chunkId]);

  return (
    <Drawer
      open={chunkId !== null}
      title="Chunk 详情"
      onClose={onClose}
    >
      {loading ? (
        <p className="text-sm text-neutral-500">正在加载 chunk 详情...</p>
      ) : !detail?.chunk ? (
        <p className="text-sm text-neutral-500">无可用 chunk 详情</p>
      ) : (
        <div className="space-y-4">
          <div className="flex gap-2 text-[11px] font-mono">
            <span className="px-2 py-0.5 rounded bg-neutral-800 text-neutral-300">
              子块 C#{detail.chunk.chunkNo ?? '-'}
            </span>
            <span className="px-2 py-0.5 rounded bg-neutral-800 text-neutral-300">
              父块 P#{detail.parentBlock?.parentBlockNo ?? '-'}
            </span>
            <span className="px-2 py-0.5 rounded bg-neutral-800 text-neutral-300">
              同父 {detail.siblingChunks?.length ?? 0}
            </span>
          </div>

          <section>
            <p className="text-[11px] font-mono uppercase tracking-wider text-amber-400/70 mb-1.5">
              Child Evidence · 当前子块
            </p>
            <pre className="text-xs text-neutral-300 font-mono whitespace-pre-wrap leading-relaxed bg-neutral-950/50 rounded-md p-3 border border-neutral-800/50 max-h-60 overflow-y-auto">
              {detail.chunk.chunkText}
            </pre>
            <div className="flex gap-3 mt-1.5 text-[10px] text-neutral-600 font-mono">
              <span>章节 {detail.chunk.sectionPath || '-'}</span>
              <span>字符 {detail.chunk.charCount ?? '-'}</span>
              <span>Token {detail.chunk.tokenCount ?? '-'}</span>
            </div>
          </section>

          {detail.parentBlock && (
            <section>
              <p className="text-[11px] font-mono uppercase tracking-wider text-amber-400/70 mb-1.5">
                Parent Context · 所属父块
              </p>
              <pre className="text-xs text-neutral-300 font-mono whitespace-pre-wrap leading-relaxed bg-neutral-950/50 rounded-md p-3 border border-neutral-800/50 max-h-60 overflow-y-auto">
                {detail.parentBlock.parentText}
              </pre>
            </section>
          )}

          {detail.siblingChunks && detail.siblingChunks.length > 0 && (
            <section>
              <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600 mb-1.5">
                同父子块（{detail.siblingChunks.length}）
              </p>
              <div className="flex flex-wrap gap-1.5">
                {detail.siblingChunks.map((s) => (
                  <span
                    key={s.chunkId}
                    className={cn(
                      'px-2 py-0.5 rounded text-[10px] font-mono border',
                      s.chunkId === detail.chunk?.chunkId
                        ? 'border-amber-500/40 bg-amber-500/10 text-amber-300'
                        : 'border-neutral-800 text-neutral-500',
                    )}
                  >
                    C#{s.chunkNo}
                  </span>
                ))}
              </div>
            </section>
          )}
        </div>
      )}
    </Drawer>
  );
}
