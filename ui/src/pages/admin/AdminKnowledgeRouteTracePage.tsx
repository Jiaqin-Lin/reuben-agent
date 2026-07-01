import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowsClockwise, CaretDown, CircleNotch, Eye } from '@phosphor-icons/react';
import { AdminPage } from '../../components/admin/AdminLayout';
import { useToast } from '../../components/shared/Toast';
import { ApiError } from '../../types/api';
import { pageQueryRouteTrace } from '../../api/knowledge';
import type { KnowledgeRouteTraceItemVo, KnowledgeRouteTraceQuery } from '../../types/knowledge';
import type { PageVo } from '../../types/chat';
import {
  normalizeRouteTrace,
  summarizeRouteTraceRecords,
  buildTopDocumentDistribution,
  type NormalizedRouteTrace,
} from '../../lib/knowledgeRoute';
import { formatDateTime, shortenId } from '../../lib/observability';
import { cn } from '../../lib/cn';

const TONE_BADGE: Record<string, string> = {
  success: 'bg-emerald-500/15 text-emerald-400',
  warning: 'bg-amber-500/15 text-amber-400',
  danger: 'bg-red-500/15 text-red-400',
  neutral: 'bg-neutral-800 text-neutral-300',
};

function TraceChip({ tone = 'neutral', children }: { tone?: keyof typeof TONE_BADGE; children: React.ReactNode }) {
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium', TONE_BADGE[tone])}>
      {children}
    </span>
  );
}

function primaryDocumentText(item: NormalizedRouteTrace): string {
  return item.topDocument?.documentName || String(item.topDocument?.documentId ?? '') || '未形成显式主候选';
}

function actualSelectionText(item: NormalizedRouteTrace): string {
  if (item.mode === 'auto') {
    return item.topDocument?.documentName || String(item.topDocument?.documentId ?? '') || '执行期可能回退到通用可检索文档池';
  }
  return item.selectedDocument?.documentName || item.selectedDocumentId || '未记录当前文档';
}

function hitConclusion(item: NormalizedRouteTrace): string {
  if (item.mode === 'auto') {
    return item.topDocument
      ? '自动模式以该主候选为中心，再进入稳定检索主链'
      : '当前没有明确主候选，需要继续补范围、主题或文档画像';
  }
  if (item.hitTop3) return '影子路由 Top3 已覆盖当前文档，人工选择与自动路由基本一致';
  if (item.missedTop3) return '影子路由 Top3 未覆盖当前文档，这轮问题可能更像跨文档';
  return '当前样本还不足以判断影子路由与人工选择是否一致';
}

export function AdminKnowledgeRouteTracePage() {
  const { toast } = useToast();
  const [records, setRecords] = useState<KnowledgeRouteTraceItemVo[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const [filters, setFilters] = useState<KnowledgeRouteTraceQuery>({
    conversationId: '',
    mode: '',
    routeStatus: undefined,
    pageNo: 1,
    pageSize: 20,
  });
  const [page, setPage] = useState<PageVo<KnowledgeRouteTraceItemVo> | null>(null);
  const [insightCollapsed, setInsightCollapsed] = useState(false);

  const load = useCallback(
    async (override?: KnowledgeRouteTraceQuery) => {
      setLoading(true);
      try {
        const query = override ?? filters;
        const res = await pageQueryRouteTrace({
          ...query,
          pageNo: query.pageNo ?? 1,
          pageSize: query.pageSize ?? 20,
        });
        setRecords(res.records ?? []);
        setPage(res);
        setSelectedId(null);
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : e instanceof Error ? e.message : '加载追踪失败';
        toast(msg, 'error');
      } finally {
        setLoading(false);
      }
    },
    [filters, toast],
  );

  useEffect(() => {
    load({ ...filters, pageNo: 1 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const normalized = useMemo(() => records.map(normalizeRouteTrace), [records]);
  const summary = useMemo(() => summarizeRouteTraceRecords(records), [records]);
  const distribution = useMemo(() => buildTopDocumentDistribution(records), [records]);
  const selected = useMemo(() => normalized.find((r) => r.id === selectedId) ?? null, [normalized, selectedId]);

  const applyFilters = () => load({ ...filters, pageNo: 1 });

  const resetFilters = () => {
    const cleared: KnowledgeRouteTraceQuery = { conversationId: '', mode: '', routeStatus: undefined, pageNo: 1, pageSize: 20 };
    setFilters(cleared);
    load(cleared);
  };

  const changePage = (next: number) => {
    if (next <= 0) return;
    const q = { ...filters, pageNo: next };
    setFilters(q);
    load(q);
  };

  const healthCards = [
    { label: '成功率', value: summary.successRateText, tone: 'success' as const, description: '越高说明自动候选越稳定' },
    { label: '低置信率', value: summary.lowConfidenceRateText, tone: 'warning' as const, description: '越高说明范围、主题或画像还需补强' },
    {
      label: '候选文档均值',
      value: summary.averageDocumentCountText,
      tone: 'neutral' as const,
      description: '高置信时通常接近 3，低置信时会放宽到 5',
    },
  ];

  const summaryCards = [
    { label: '总追踪量', value: String(page?.total ?? 0) },
    { label: '本页 auto', value: String(summary.autoCount) },
    { label: '本页 shadow', value: String(summary.shadowCount) },
    { label: '高置信', value: String(summary.highConfidenceCount) },
    { label: '低置信或失败', value: String(summary.lowConfidenceCount + summary.failedCount) },
    { label: 'shadow Top3 命中率', value: summary.shadowHitRateText },
    { label: '平均置信度', value: summary.averageConfidenceText },
    { label: '成功率', value: summary.successRateText },
    { label: '低置信率', value: summary.lowConfidenceRateText },
    { label: '均候选文档', value: summary.averageDocumentCountText },
    { label: '扩范围次数', value: String(summary.widenedCount) },
  ];

  return (
    <AdminPage>
      <div className="flex items-start justify-between gap-4 mb-4">
        <div>
          <h1 className="text-lg font-semibold text-neutral-100">路由追踪分析</h1>
          <p className="text-sm text-neutral-500 mt-0.5">查看自动 / 影子路由的候选、置信度与命中情况，定位低置信样本</p>
        </div>
        <button
          onClick={() => load()}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-neutral-700 text-neutral-300 text-xs hover:bg-neutral-800 disabled:opacity-50"
        >
          {loading ? <CircleNotch className="w-3.5 h-3.5 animate-spin" /> : <ArrowsClockwise className="w-3.5 h-3.5" />}
          刷新追踪
        </button>
      </div>

      {/* 可折叠洞察区 */}
      <div className="mb-4 rounded-xl border border-neutral-800 bg-neutral-900/30 overflow-hidden">
        <button
          onClick={() => setInsightCollapsed((v) => !v)}
          className="w-full flex items-center justify-between px-4 py-2.5 text-sm font-semibold text-neutral-200 hover:bg-neutral-900/60 transition-colors"
        >
          <span>路由洞察</span>
          <CaretDown className={cn('w-4 h-4 text-neutral-500 transition-transform', insightCollapsed && '-rotate-90')} />
        </button>
        {!insightCollapsed && (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-0 border-t border-neutral-800">
            {/* 路由健康度 */}
            <div className="p-4 border-b lg:border-b-0 lg:border-r border-neutral-800">
              <div className="flex items-center justify-between mb-3">
                <h5 className="text-xs font-semibold text-neutral-200">路由健康度</h5>
                <span className="text-[11px] text-neutral-500">当前页样本</span>
              </div>
              <div className="space-y-2.5">
                {healthCards.map((c) => (
                  <HealthMeter key={c.label} label={c.label} value={c.value} tone={c.tone} description={c.description} />
                ))}
              </div>
            </div>

            {/* Top 候选文档分布 */}
            <div className="p-4 border-b lg:border-b-0 lg:border-r border-neutral-800">
              <div className="flex items-center justify-between mb-3">
                <h5 className="text-xs font-semibold text-neutral-200">Top 候选文档分布</h5>
                <span className="text-[11px] text-neutral-500">{distribution.length} 个文档</span>
              </div>
              {distribution.length ? (
                <div className="space-y-1.5">
                  {distribution.map((d) => (
                    <div
                      key={d.documentId}
                      className="flex items-center justify-between gap-2 p-2 rounded-lg border border-neutral-800 bg-neutral-900/40"
                    >
                      <div className="min-w-0">
                        <p className="text-xs text-neutral-200 truncate">{d.documentName}</p>
                        <p className="text-[11px] text-neutral-500">
                          出现 {d.count} 次 · 均值 {d.averageConfidenceText}
                        </p>
                      </div>
                      <span
                        className={cn(
                          'shrink-0 inline-flex px-2 py-0.5 rounded-full text-[11px] font-semibold',
                          d.lowConfidenceCount > 0
                            ? 'bg-amber-500/15 text-amber-400'
                            : 'bg-emerald-500/15 text-emerald-400',
                        )}
                      >
                        {d.lowConfidenceCount > 0 ? `${d.lowConfidenceCount} 次低置信` : '全部成功'}
                      </span>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-xs text-neutral-600">当前页还没有可统计的 Top 文档</p>
              )}
            </div>

            {/* 详细统计 mini-stats grid */}
            <div className="p-4">
              <div className="flex items-center justify-between mb-3">
                <h5 className="text-xs font-semibold text-neutral-200">详细统计</h5>
              </div>
              <div className="grid grid-cols-3 gap-2.5">
                {summaryCards.map((c) => (
                  <div key={c.label} className="flex flex-col gap-0.5">
                    <strong className="text-sm text-neutral-100 tabular-nums">{c.value}</strong>
                    <span className="text-[11px] text-neutral-500">{c.label}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_1.2fr] gap-4">
        {/* 左侧：筛选 + 列表 */}
        <div className="flex flex-col rounded-xl border border-neutral-800 bg-neutral-900/30 overflow-hidden">
          <div className="p-3 border-b border-neutral-800 space-y-2">
            <input
              value={filters.conversationId ?? ''}
              onChange={(e) => setFilters({ ...filters, conversationId: e.target.value })}
              onKeyDown={(e) => e.key === 'Enter' && applyFilters()}
              placeholder="按会话 ID 筛选..."
              className="w-full px-3 py-2 rounded-lg bg-neutral-900 border border-neutral-800 text-sm text-neutral-100 placeholder:text-neutral-600 focus:outline-none focus:border-amber-500/50"
            />
            <div className="flex gap-2">
              <select
                value={filters.mode ?? ''}
                onChange={(e) => setFilters({ ...filters, mode: e.target.value || undefined })}
                className="flex-1 px-2.5 py-1.5 rounded-lg bg-neutral-900 border border-neutral-800 text-xs text-neutral-200"
              >
                <option value="">全部模式</option>
                <option value="shadow">shadow</option>
                <option value="auto">auto</option>
              </select>
              <select
                value={filters.routeStatus ?? ''}
                onChange={(e) =>
                  setFilters({ ...filters, routeStatus: e.target.value ? Number(e.target.value) : undefined })
                }
                className="flex-1 px-2.5 py-1.5 rounded-lg bg-neutral-900 border border-neutral-800 text-xs text-neutral-200"
              >
                <option value="">全部状态</option>
                <option value="1">成功</option>
                <option value="2">低置信</option>
                <option value="3">失败</option>
              </select>
            </div>
            <div className="flex gap-2">
              <button onClick={resetFilters} className="flex-1 px-3 py-1.5 rounded-lg border border-neutral-700 text-neutral-300 text-xs hover:bg-neutral-800">
                重置
              </button>
              <button
                onClick={applyFilters}
                disabled={loading}
                className="flex-1 px-3 py-1.5 rounded-lg bg-amber-500 text-black text-xs font-semibold hover:bg-amber-400 disabled:opacity-50"
              >
                筛选
              </button>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto max-h-[560px] p-2 space-y-1.5">
            {loading ? (
              <div className="flex items-center justify-center py-12 text-neutral-500 text-sm">
                <CircleNotch className="w-4 h-4 animate-spin mr-2" />
                正在加载...
              </div>
            ) : normalized.length === 0 ? (
              <div className="py-12 text-center text-neutral-600 text-sm">暂无追踪记录</div>
            ) : (
              normalized.map((item) => (
                <button
                  key={item.id}
                  onClick={() => setSelectedId(item.id)}
                  className={cn(
                    'w-full text-left p-3 rounded-lg border transition-colors',
                    selectedId === item.id
                      ? 'border-amber-500/40 bg-amber-500/5'
                      : 'border-neutral-800 bg-neutral-900/40 hover:bg-neutral-900/70',
                  )}
                >
                  <div className="flex flex-wrap gap-1.5 mb-1.5">
                    <TraceChip tone="neutral">{item.modeLabel}</TraceChip>
                    <TraceChip tone={item.statusTone}>{item.statusLabel}</TraceChip>
                    <TraceChip tone={item.confidenceBand.tone}>{item.confidenceText}</TraceChip>
                  </div>
                  <p className="text-sm text-neutral-200 line-clamp-2">{item.question || '未记录问题'}</p>
                  <div className="flex items-center justify-between mt-1.5 text-[11px] text-neutral-500">
                    <span className="truncate">{primaryDocumentText(item)}</span>
                    <span className="shrink-0 ml-2">{formatDateTime(item.createTimeNumber)}</span>
                  </div>
                </button>
              ))
            )}
          </div>

          {page && (
            <div className="flex items-center justify-between gap-2 px-3 py-2 border-t border-neutral-800 text-xs text-neutral-500">
              <button
                onClick={() => changePage((page.pageNo ?? 1) - 1)}
                disabled={(page.pageNo ?? 1) <= 1 || loading}
                className="px-2.5 py-1 rounded border border-neutral-700 hover:bg-neutral-800 disabled:opacity-40"
              >
                上一页
              </button>
              <span>
                {page.pageNo} / {page.totalPages || 0} · 共 {page.total}
              </span>
              <button
                onClick={() => changePage((page.pageNo ?? 1) + 1)}
                disabled={(page.pageNo ?? 1) >= (page.totalPages ?? 0) || loading}
                className="px-2.5 py-1 rounded border border-neutral-700 hover:bg-neutral-800 disabled:opacity-40"
              >
                下一页
              </button>
            </div>
          )}
        </div>

        {/* 右侧：详情 */}
        <div className="rounded-xl border border-neutral-800 bg-neutral-900/30 p-5 min-h-[400px]">
          {!selected ? (
            <div className="h-full flex flex-col items-center justify-center text-center text-neutral-600 py-16">
              <Eye className="w-8 h-8 mb-3 opacity-50" />
              <p className="text-sm">从左侧选择一条追踪记录查看详情</p>
            </div>
          ) : (
            <div className="space-y-5">
              <div>
                <div className="flex flex-wrap gap-1.5 mb-2">
                  <TraceChip tone="neutral">{selected.modeLabel}</TraceChip>
                  <TraceChip tone={selected.statusTone}>{selected.statusLabel}</TraceChip>
                  <TraceChip tone={selected.confidenceBand.tone}>
                    {selected.confidenceBand.label} · {selected.confidenceText}
                  </TraceChip>
                </div>
                <p className="text-sm text-neutral-500">
                  会话 {shortenId(selected.conversationId)} · 轮次 {selected.exchangeId ?? selected.id} · {formatDateTime(selected.createTimeNumber)}
                </p>
                {selected.question && <p className="text-sm text-neutral-100 mt-2">{selected.question}</p>}
                {selected.rewriteQuestion && (
                  <p className="text-xs text-neutral-500 mt-1">改写：{selected.rewriteQuestion}</p>
                )}
              </div>

              <div className="grid grid-cols-2 gap-3">
                <SummaryCard label="主候选文档" value={primaryDocumentText(selected)} hint={selected.reasonText || '未记录额外路由说明'} />
                <SummaryCard label="实际落点" value={actualSelectionText(selected)} hint={hitConclusion(selected)} />
                <SummaryCard
                  label="候选规模"
                  value={`${selected.candidateDocumentCount} 文档 / ${selected.candidateTopicCount} 主题 / ${selected.candidateScopeCount} 范围`}
                  hint={selected.lowConfidenceWidened ? '低置信已自动放宽候选范围' : '候选规模稳定'}
                />
                <SummaryCard
                  label="置信度"
                  value={selected.confidenceText}
                  hint={selected.confidenceBand.label}
                />
              </div>

              {selected.documents.length > 0 && (
                <div>
                  <h4 className="text-xs font-mono uppercase tracking-wider text-neutral-500 mb-2">候选文档（{selected.documents.length} 份）</h4>
                  <div className="space-y-1.5">
                    {selected.documents.map((c, i) => (
                      <div
                        key={`doc-${c.documentId || i}`}
                        className={cn(
                          'flex items-start gap-2 p-2 rounded-lg border',
                          i === 0 ? 'border-amber-500/30 bg-amber-500/5' : 'border-neutral-800 bg-neutral-900/40',
                        )}
                      >
                        <span className="shrink-0 w-5 h-5 rounded-full bg-neutral-800 text-neutral-400 text-[11px] font-semibold grid place-items-center">
                          {i + 1}
                        </span>
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <strong className="text-sm text-neutral-200 truncate">{c.documentName || c.documentId}</strong>
                            <span className="text-[11px] text-amber-400 font-mono shrink-0">{c.scoreText}</span>
                          </div>
                          {c.reason && <p className="text-[11px] text-neutral-500 mt-0.5 line-clamp-1">{c.reason}</p>}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <h4 className="text-xs font-mono uppercase tracking-wider text-neutral-500 mb-2">范围候选（{selected.scopes.length}）</h4>
                  <div className="flex flex-wrap gap-1.5">
                    {selected.scopes.length ? (
                      selected.scopes.map((c, i) => (
                        <TraceChip key={`s-${c.scopeCode || i}`} tone="neutral">
                          {c.scopeName || c.scopeCode} · {c.scoreText}
                        </TraceChip>
                      ))
                    ) : (
                      <span className="text-xs text-neutral-600">没有显式范围候选</span>
                    )}
                  </div>
                </div>
                <div>
                  <h4 className="text-xs font-mono uppercase tracking-wider text-neutral-500 mb-2">主题候选（{selected.topics.length}）</h4>
                  <div className="flex flex-wrap gap-1.5">
                    {selected.topics.length ? (
                      selected.topics.map((c, i) => (
                        <TraceChip key={`t-${c.topicCode || i}`} tone="neutral">
                          {c.topicName || c.topicCode} · {c.scoreText}
                        </TraceChip>
                      ))
                    ) : (
                      <span className="text-xs text-neutral-600">没有显式主题候选</span>
                    )}
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </AdminPage>
  );
}

function SummaryCard({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="p-3 rounded-lg border border-neutral-800 bg-neutral-900/40">
      <p className="text-[11px] text-neutral-500">{label}</p>
      <p className="text-sm text-neutral-100 mt-1 font-medium break-words">{value}</p>
      <p className="text-[11px] text-neutral-600 mt-1 line-clamp-2">{hint}</p>
    </div>
  );
}

const HEALTH_FILL_CLASS: Record<string, string> = {
  success: 'bg-emerald-500',
  warning: 'bg-amber-500',
  neutral: 'bg-sky-500',
};

function parsePercent(value: string): number {
  const numeric = Number(String(value || '').replace('%', ''));
  if (!Number.isFinite(numeric)) return 0;
  return Math.max(0, Math.min(100, numeric));
}

function HealthMeter({
  label,
  value,
  tone,
  description,
}: {
  label: string;
  value: string;
  tone: 'success' | 'warning' | 'neutral';
  description: string;
}) {
  const percent = parsePercent(value);
  return (
    <div className="p-2.5 rounded-lg border border-neutral-800 bg-neutral-900/40">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-neutral-400">{label}</span>
        <strong className="text-xs text-neutral-100 tabular-nums">{value}</strong>
      </div>
      <div className="my-1.5 h-1.5 rounded-full bg-neutral-800 overflow-hidden">
        <span className={cn('block h-full rounded-full', HEALTH_FILL_CLASS[tone])} style={{ width: `${percent}%` }} />
      </div>
      <small className="text-[11px] text-neutral-600">{description}</small>
    </div>
  );
}
