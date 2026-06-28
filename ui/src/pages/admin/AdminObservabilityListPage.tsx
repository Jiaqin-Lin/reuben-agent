import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowsClockwise, CircleNotch, ChatCircle } from '@phosphor-icons/react';
import { AdminPage } from '../../components/admin/AdminLayout';
import { useToast } from '../../components/shared/Toast';
import { ApiError } from '../../types/api';
import { listSessions } from '../../api/chat';
import type { ConversationSessionListVo, PageVo, ChatSessionListQuery } from '../../types/chat';
import { chatModeLabel, turnStatusLabel, turnStatusTone, formatTime, truncate } from '../../lib/observability';
import { cn } from '../../lib/cn';

const STATUS_TONE_CLASS: Record<string, string> = {
  running: 'bg-amber-500/15 text-amber-400',
  completed: 'bg-emerald-500/15 text-emerald-400',
  failed: 'bg-red-500/15 text-red-400',
  stopped: 'bg-neutral-700 text-neutral-300',
  idle: 'bg-neutral-800 text-neutral-400',
  warning: 'bg-amber-500/15 text-amber-400',
};

function errMsg(e: unknown, fallback: string): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return fallback;
}

export function AdminObservabilityListPage() {
  const { toast } = useToast();
  const nav = useNavigate();
  const [sessions, setSessions] = useState<ConversationSessionListVo[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState<PageVo<ConversationSessionListVo> | null>(null);

  const [keyword, setKeyword] = useState('');
  const [modeFilter, setModeFilter] = useState<number | ''>('');
  const [statusFilter, setStatusFilter] = useState<number | ''>('');
  const [pageNo, setPageNo] = useState(1);
  const pageSize = 12;

  const load = useCallback(
    async (override?: Partial<ChatSessionListQuery>) => {
      setLoading(true);
      try {
        const q: ChatSessionListQuery = {
          pageNo: override?.pageNo ?? pageNo,
          pageSize,
          keyword: (override?.keyword ?? keyword) || undefined,
          chatMode: (override?.chatMode ?? modeFilter) || undefined,
          turnStatus: (override?.turnStatus ?? statusFilter) || undefined,
        };
        const res = await listSessions(q);
        setSessions(res.records ?? []);
        setPage(res);
        setPageNo(res.pageNo ?? 1);
      } catch (e) {
        toast(errMsg(e, '加载会话列表失败'), 'error');
      } finally {
        setLoading(false);
      }
    },
    [keyword, modeFilter, statusFilter, pageNo, toast],
  );

  useEffect(() => {
    load({ pageNo: 1 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const applyFilters = () => load({ pageNo: 1 });
  const resetFilters = () => {
    setKeyword('');
    setModeFilter('');
    setStatusFilter('');
    load({ pageNo: 1, keyword: undefined, chatMode: undefined, turnStatus: undefined });
  };

  const goPage = (n: number) => load({ pageNo: n });

  const runningCount = sessions.filter((s) => s.sessionStatus === 2).length;
  const failedCount = sessions.filter((s) => s.latestTurn?.turnStatus === 3).length;
  const docModeCount = sessions.filter((s) => s.chatMode === 1).length;

  return (
    <AdminPage>
      <div className="flex items-start justify-between gap-4 mb-4">
        <div>
          <h1 className="text-lg font-semibold text-neutral-100">对话观测</h1>
          <p className="text-sm text-neutral-500 mt-0.5">定位问题会话，再进入详情按轮次展开全链路观测</p>
        </div>
        <button
          onClick={() => load()}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-neutral-700 text-neutral-300 text-xs hover:bg-neutral-800 disabled:opacity-50"
        >
          {loading ? <CircleNotch className="w-3.5 h-3.5 animate-spin" /> : <ArrowsClockwise className="w-3.5 h-3.5" />}
          刷新
        </button>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
        <StatBadge label="会话总数" value={page?.total ?? 0} />
        <StatBadge label="本页运行中" value={runningCount} />
        <StatBadge label="本页文档问答" value={docModeCount} />
        <StatBadge label="本页最近失败" value={failedCount} />
      </div>

      <div className="flex flex-col sm:flex-row gap-2 mb-4">
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && applyFilters()}
          placeholder="按会话标题或文档名筛选"
          className="flex-1 px-3 py-2 rounded-lg bg-neutral-900 border border-neutral-800 text-sm text-neutral-100 placeholder:text-neutral-600 focus:outline-none focus:border-amber-500/50"
        />
        <select
          value={modeFilter}
          onChange={(e) => setModeFilter(e.target.value ? Number(e.target.value) : '')}
          className="px-3 py-2 rounded-lg bg-neutral-900 border border-neutral-800 text-sm text-neutral-200"
        >
          <option value="">全部模式</option>
          <option value="1">当前文档问答</option>
          <option value="2">开放式提问</option>
          <option value="3">自动知识问答</option>
        </select>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value ? Number(e.target.value) : '')}
          className="px-3 py-2 rounded-lg bg-neutral-900 border border-neutral-800 text-sm text-neutral-200"
        >
          <option value="">全部状态</option>
          <option value="1">执行中</option>
          <option value="2">完成</option>
          <option value="3">失败</option>
          <option value="4">停止</option>
        </select>
        <button
          onClick={resetFilters}
          disabled={loading}
          className="px-3 py-2 rounded-lg border border-neutral-700 text-neutral-300 text-sm hover:bg-neutral-800 disabled:opacity-50"
        >
          重置
        </button>
        <button
          onClick={applyFilters}
          disabled={loading}
          className="px-3 py-2 rounded-lg bg-amber-500 text-black text-sm font-semibold hover:bg-amber-400 disabled:opacity-50"
        >
          应用
        </button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16 text-neutral-500">
          <CircleNotch className="w-5 h-5 animate-spin mr-2" />
          正在加载会话列表...
        </div>
      ) : sessions.length === 0 ? (
        <div className="py-16 text-center text-neutral-600">
          <ChatCircle className="w-10 h-10 mx-auto mb-3 opacity-40" />
          <p className="text-sm">当前筛选条件下没有匹配的会话</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
          {sessions.map((s) => {
            const running = s.sessionStatus === 2;
            const turnStatus = s.latestTurn?.turnStatus;
            return (
              <button
                key={s.conversationId}
                onClick={() => nav(`/admin/observability/${s.conversationId}`)}
                className="text-left p-4 rounded-xl border border-neutral-800 bg-neutral-900/40 hover:border-amber-500/30 hover:bg-neutral-900/70 transition-colors"
              >
                <div className="flex items-center justify-between gap-2 mb-2">
                  <div className="flex flex-wrap gap-1.5">
                    <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] bg-amber-500/10 text-amber-400">
                      {chatModeLabel(s.chatMode)}
                    </span>
                    {running ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] bg-amber-500/15 text-amber-400">
                        <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse" />
                        实时执行中
                      </span>
                    ) : turnStatus ? (
                      <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px]', STATUS_TONE_CLASS[turnStatusTone(turnStatus)])}>
                        {turnStatusLabel(turnStatus)}
                      </span>
                    ) : null}
                  </div>
                  <span className="text-[11px] text-neutral-500 shrink-0">{formatTime(s.updateTime || s.createTime)}</span>
                </div>

                <h3 className="text-sm font-medium text-neutral-100 truncate">
                  {truncate(s.title || s.latestTurn?.userPrompt || '未命名会话', 40)}
                </h3>
                <p className="text-xs text-neutral-500 mt-1 line-clamp-2">
                  {truncate(s.latestTurn?.replyContent || s.latestTurn?.userPrompt || '暂无内容', 80)}
                </p>

                <div className="flex items-center gap-3 mt-3 text-[11px] text-neutral-600">
                  <code className="font-mono">{s.conversationId.slice(0, 12)}...</code>
                  <span>{s.turnCount} 轮</span>
                  {s.selectedDocumentName && <span className="truncate">{s.selectedDocumentName}</span>}
                </div>

                {s.latestTurn?.finishNote && (
                  <p className="text-[11px] text-red-400 mt-2 line-clamp-1">{s.latestTurn.finishNote}</p>
                )}
              </button>
            );
          })}
        </div>
      )}

      {page && (page.totalPages ?? 0) > 0 && (
        <div className="flex items-center justify-between gap-2 mt-5 text-xs text-neutral-500">
          <span>
            第 {page.pageNo} / {page.totalPages} 页 · 共 {page.total} 条
          </span>
          <div className="flex gap-2">
            <button
              onClick={() => goPage((page.pageNo ?? 1) - 1)}
              disabled={(page.pageNo ?? 1) <= 1 || loading}
              className="px-3 py-1.5 rounded border border-neutral-700 hover:bg-neutral-800 disabled:opacity-40"
            >
              上一页
            </button>
            <button
              onClick={() => goPage((page.pageNo ?? 1) + 1)}
              disabled={(page.pageNo ?? 1) >= (page.totalPages ?? 0) || loading}
              className="px-3 py-1.5 rounded border border-neutral-700 hover:bg-neutral-800 disabled:opacity-40"
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </AdminPage>
  );
}

function StatBadge({ label, value }: { label: string; value: number }) {
  return (
    <div className="p-4 rounded-xl border border-neutral-800 bg-neutral-900/40">
      <p className="text-xs text-neutral-500">{label}</p>
      <p className="mt-1.5 text-2xl font-semibold text-neutral-100 tabular-nums">{value}</p>
    </div>
  );
}
