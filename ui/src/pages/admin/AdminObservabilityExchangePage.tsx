import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowsClockwise, CircleNotch, CaretRight, X } from '@phosphor-icons/react';
import { AdminPage } from '../../components/admin/AdminLayout';
import { useToast } from '../../components/shared/Toast';
import { ApiError } from '../../types/api';
import {
  getTurnDetail,
  getRetrievalResults,
  getChannelExecutions,
  getStageBenchmarks,
} from '../../api/chat';
import type {
  ChatTurnDetailView,
  ChatTraceStageView,
  RetrievalResultView,
  ChannelExecutionView,
  StageBenchmarkView,
  ChatDebugTrace,
} from '../../types/chat';
import {
  stageStateLabel,
  stageStateTone,
  executionModeLabel,
  channelLabel,
  formatDateTime,
  formatLatency,
  formatScore,
  executionStateLabel,
  formatBenchmarkComparison,
  BENCHMARK_LEVEL_CLASS,
} from '../../lib/observability';
import { Markdown } from '../../components/shared/Markdown';
import { cn } from '../../lib/cn';

const STAGE_TONE_DOT: Record<string, string> = {
  running: 'bg-amber-400',
  completed: 'bg-emerald-400',
  failed: 'bg-red-400',
  idle: 'bg-neutral-600',
  warning: 'bg-amber-400',
};

const STAGE_TONE_CLASS: Record<string, string> = {
  running: 'text-amber-400',
  completed: 'text-emerald-400',
  failed: 'text-red-400',
  idle: 'text-neutral-500',
  warning: 'text-amber-400',
};

const SELECTION_BADGE: Record<string, { label: string; cls: string }> = {
  selected: { label: '已选入', cls: 'bg-emerald-500/15 text-emerald-400' },
  filtered: { label: '闸门过滤', cls: 'bg-red-500/15 text-red-400' },
  omitted: { label: '未选入', cls: 'bg-neutral-700 text-neutral-400' },
};

function errMsg(e: unknown, fallback: string): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return fallback;
}

/** 安全解析 debugTraceJson。 */
function parseDebugTrace(raw?: string): ChatDebugTrace | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as ChatDebugTrace) : null;
  } catch {
    return null;
  }
}

/** 按子问题分组检索结果。 */
function groupResultsBySubQuestion(results: RetrievalResultView[]) {
  const grouped = new Map<number, { index: number; channels: Map<string, RetrievalResultView[]> }>();
  for (const r of results) {
    const idx = r.subQuestionIndex ?? 0;
    if (!grouped.has(idx)) grouped.set(idx, { index: idx, channels: new Map() });
    const subQ = grouped.get(idx)!;
    const type = r.channelType || 'unknown';
    if (!subQ.channels.has(type)) subQ.channels.set(type, []);
    subQ.channels.get(type)!.push(r);
  }
  return Array.from(grouped.values()).map((subQ) => ({
    index: subQ.index,
    channels: Array.from(subQ.channels.entries()).map(([type, items]) => ({ type, results: items })),
  }));
}

function selectionKind(r: RetrievalResultView): keyof typeof SELECTION_BADGE {
  if (r.isSelected === 1) return 'selected';
  if (r.gatePassed === 0) return 'filtered';
  return 'omitted';
}

export function AdminObservabilityExchangePage() {
  const { conversationId = '', exchangeId = '' } = useParams();
  const { toast } = useToast();
  const nav = useNavigate();
  const [detail, setDetail] = useState<ChatTurnDetailView | null>(null);
  const [results, setResults] = useState<RetrievalResultView[]>([]);
  const [channels, setChannels] = useState<ChannelExecutionView[]>([]);
  const [benchmarks, setBenchmarks] = useState<StageBenchmarkView[]>([]);
  const [loading, setLoading] = useState(true);
  const [overlayStage, setOverlayStage] = useState<ChatTraceStageView | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [d, r, c, b] = await Promise.all([
        getTurnDetail(conversationId, Number(exchangeId)),
        getRetrievalResults(conversationId, Number(exchangeId)).catch(() => [] as RetrievalResultView[]),
        getChannelExecutions(conversationId, Number(exchangeId)).catch(() => [] as ChannelExecutionView[]),
        getStageBenchmarks().catch(() => [] as StageBenchmarkView[]),
      ]);
      setDetail(d);
      setResults(r);
      setChannels(c);
      setBenchmarks(b);
    } catch (e) {
      toast(errMsg(e, '加载轮次详情失败'), 'error');
    } finally {
      setLoading(false);
    }
  }, [conversationId, exchangeId, toast]);

  useEffect(() => {
    load();
  }, [load]);

  const turn = detail?.turn;
  const stages = detail?.stageTraces ?? [];
  const debugTrace = useMemo(() => parseDebugTrace(turn?.debugTraceJson), [turn?.debugTraceJson]);

  const totalTokens = useMemo(
    () => (debugTrace?.modelUsageTraces ?? []).reduce((sum, t) => sum + Number(t.totalTokens ?? 0), 0),
    [debugTrace],
  );
  const totalCost = useMemo(() => {
    const sum = (debugTrace?.modelUsageTraces ?? []).reduce((s, t) => s + Number(t.estimatedCost ?? 0), 0);
    return sum > 0 ? `¥ ${sum.toFixed(4)}` : '无';
  }, [debugTrace]);

  const groupedResults = useMemo(() => groupResultsBySubQuestion(results), [results]);

  const findBenchmark = (stageCode?: number, executionMode?: number) =>
    benchmarks.find((b) => b.stageCode === stageCode && b.executionMode === executionMode) ?? null;

  return (
    <AdminPage>
      <div className="flex items-center justify-between gap-3 mb-4">
        <button
          onClick={() => nav(`/admin/observability/${conversationId}`)}
          className="inline-flex items-center gap-1.5 text-sm text-neutral-400 hover:text-amber-400"
        >
          <ArrowLeft className="w-4 h-4" />
          返回会话轮次列表
        </button>
        <button
          onClick={() => load()}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-neutral-700 text-neutral-300 text-xs hover:bg-neutral-800 disabled:opacity-50"
        >
          {loading ? <CircleNotch className="w-3.5 h-3.5 animate-spin" /> : <ArrowsClockwise className="w-3.5 h-3.5" />}
          刷新
        </button>
      </div>

      {loading && !detail ? (
        <div className="flex items-center justify-center py-16 text-neutral-500">
          <CircleNotch className="w-5 h-5 animate-spin mr-2" />
          正在加载轮次详情...
        </div>
      ) : !turn ? (
        <div className="py-16 text-center text-neutral-600 text-sm">没有找到这条轮次</div>
      ) : (
        <>
          {/* 头部 */}
          <div className="p-5 rounded-xl border border-neutral-800 bg-neutral-900/40 mb-4">
            <div className="flex flex-wrap items-center gap-2 mb-3">
              {turn.turnStatus != null && (
                <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px]', STAGE_TONE_CLASS[stageStateTone(turn.turnStatus)])}>
                  {stageStateLabel(turn.turnStatus)}
                </span>
              )}
              {turn.executionMode != null && (
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] bg-neutral-800 text-neutral-300">
                  {executionModeLabel(turn.executionMode)}
                </span>
              )}
              <span className="text-[11px] text-neutral-500 font-mono">轮次 {turn.turnId}</span>
            </div>
            <h2 className="text-base font-semibold text-neutral-100">{turn.userPrompt || '未记录问题'}</h2>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-4">
              <MetaItem label="执行时间" value={formatDateTime(turn.createTime)} />
              <MetaItem label="首包耗时" value={formatLatency(turn.firstTokenLatencyMs ?? undefined)} />
              <MetaItem label="总耗时" value={formatLatency(turn.totalLatencyMs ?? undefined)} />
              <MetaItem label="引用 / 推荐" value={`${countReferences(turn.sourceSnapshotList)} / ${countReferences(turn.followupSuggestionList)}`} />
              <MetaItem label="总 Token" value={totalTokens > 0 ? String(totalTokens) : '无'} />
              <MetaItem label="成本" value={totalCost} />
              <MetaItem label="执行模式" value={executionModeLabel(turn.executionMode)} />
              <MetaItem label="模型调用次数" value={String(debugTrace?.modelUsageTraces?.length ?? 0)} />
            </div>
            {turn.finishNote && (
              <p className="text-xs text-red-400 mt-3 p-2 rounded-md bg-red-500/10 border border-red-500/30">{turn.finishNote}</p>
            )}
          </div>

          {/* 执行阶段时间线 */}
          <h3 className="text-sm font-semibold text-neutral-200 mb-3">执行阶段时间线（{stages.length}）</h3>
          {stages.length === 0 ? (
            <div className="py-8 text-center text-neutral-600 text-sm mb-4">当前轮次还没有可展示的阶段轨迹</div>
          ) : (
            <div className="space-y-1.5 mb-6">
              {stages
                .slice()
                .sort((a, b) => a.stageOrder - b.stageOrder)
                .map((s, i) => (
                  <TimelineItem
                    key={s.id}
                    stage={s}
                    last={i === stages.length - 1}
                    benchmark={findBenchmark(s.stageCode, s.executionMode)}
                    onOpen={() => setOverlayStage(s)}
                  />
                ))}
            </div>
          )}

          {/* 回答预览 */}
          {turn.replyContent && (
            <div className="mb-6">
              <h3 className="text-sm font-semibold text-neutral-200 mb-2">回答预览</h3>
              <div className="p-4 rounded-xl border border-neutral-800 bg-neutral-900/30">
                <Markdown content={turn.replyContent} />
              </div>
            </div>
          )}

          {/* 通道性能对比 */}
          {channels.length > 0 && (
            <div className="mb-6">
              <h3 className="text-sm font-semibold text-neutral-200 mb-3">通道性能对比（{channels.length}）</h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {channels.map((c) => (
                  <div key={c.id} className="p-4 rounded-xl border border-neutral-800 bg-neutral-900/30">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <strong className="text-sm text-neutral-200">{channelLabel(c.channelType)}</strong>
                        {c.subQuestionIndex != null && (
                          <span className="text-[11px] text-neutral-500">子问题 {c.subQuestionIndex}</span>
                        )}
                      </div>
                      <span className="text-[11px] text-neutral-400">{executionStateLabel(c.executionState)}</span>
                    </div>
                    <div className="grid grid-cols-3 gap-2 mt-3">
                      <ChannelMetric label="召回数" value={c.recalledCount ?? 0} />
                      <ChannelMetric label="闸门后" value={c.acceptedCount ?? 0} />
                      <ChannelMetric label="最终选入" value={c.finalSelectedCount ?? 0} highlight />
                      <ChannelMetric label="耗时" value={c.durationMs != null ? `${c.durationMs} ms` : '-'} />
                      <ChannelMetric label="平均分" value={formatScore(c.avgScore ?? undefined)} />
                      <ChannelMetric label="分数区间" value={`${formatScore(c.minScore ?? undefined)} ~ ${formatScore(c.maxScore ?? undefined)}`} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 按子问题分组的检索结果表 */}
          {groupedResults.length > 0 && (
            <div className="mb-6">
              <h3 className="text-sm font-semibold text-neutral-200 mb-3">检索结果详情（{results.length}）</h3>
              <div className="space-y-4">
                {groupedResults.map((subQ) => (
                  <div key={subQ.index} className="rounded-xl border border-neutral-800 bg-neutral-900/30 overflow-hidden">
                    <div className="px-4 py-2.5 border-b border-neutral-800 bg-neutral-900/50">
                      <span className="text-xs font-semibold text-neutral-300">子问题 {subQ.index + 1}</span>
                    </div>
                    {subQ.channels.map((channel) => (
                      <div key={channel.type} className="p-3">
                        <div className="flex items-center gap-2 mb-2">
                          <span className="text-xs font-medium text-amber-400">{channelLabel(channel.type)}</span>
                          <span className="text-[11px] text-neutral-600">{channel.results.length} 条</span>
                        </div>
                        <div className="overflow-x-auto">
                          <table className="w-full text-xs">
                            <thead>
                              <tr className="text-left text-neutral-500 border-b border-neutral-800">
                                <th className="py-1.5 pr-3 font-medium">排名变化</th>
                                <th className="py-1.5 pr-3 font-medium">文档块</th>
                                <th className="py-1.5 pr-3 font-medium">原始分</th>
                                <th className="py-1.5 pr-3 font-medium">RRF 分</th>
                                <th className="py-1.5 pr-3 font-medium">Rerank 分</th>
                                <th className="py-1.5 font-medium">状态</th>
                              </tr>
                            </thead>
                            <tbody>
                              {channel.results.map((r) => {
                                const sel = SELECTION_BADGE[selectionKind(r)];
                                return (
                                  <tr
                                    key={r.id}
                                    className={cn('border-b border-neutral-800/50', r.isSelected === 1 && 'bg-amber-500/5')}
                                  >
                                    <td className="py-1.5 pr-3 text-neutral-400 font-mono">
                                      {r.channelRank ?? r.vectorRank ?? '-'}
                                      {r.finalRank ? <span className="text-neutral-600"> → {r.finalRank}</span> : null}
                                    </td>
                                    <td className="py-1.5 pr-3">
                                      <div className="text-neutral-200 truncate max-w-[220px]">{r.documentName || '未知文档'}</div>
                                      {r.sectionPath && <div className="text-[10px] text-neutral-600 truncate max-w-[220px]">{r.sectionPath}</div>}
                                    </td>
                                    <td className="py-1.5 pr-3 text-neutral-400 font-mono">{formatScore(r.originalScore ?? r.vectorScore ?? undefined)}</td>
                                    <td className="py-1.5 pr-3 text-neutral-400 font-mono">{formatScore(r.rrfScore ?? undefined)}</td>
                                    <td className="py-1.5 pr-3 text-neutral-400 font-mono">{formatScore(r.rerankScore ?? undefined)}</td>
                                    <td className="py-1.5">
                                      <span className={cn('inline-flex px-1.5 py-0.5 rounded text-[10px]', sel.cls)}>{sel.label}</span>
                                    </td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Prompt 预览 */}
          <PromptPreview debugTrace={debugTrace} />

          {/* Stage Benchmarks */}
          <BenchmarksCard benchmarks={benchmarks} />
        </>
      )}

      {overlayStage && (
        <TraceOverlay stage={overlayStage} benchmark={findBenchmark(overlayStage.stageCode, overlayStage.executionMode)} onClose={() => setOverlayStage(null)} />
      )}
    </AdminPage>
  );
}

function countReferences(raw?: string): number {
  if (!raw) return 0;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.length : 0;
  } catch {
    return 0;
  }
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[11px] text-neutral-500">{label}</p>
      <p className="text-sm text-neutral-200 mt-0.5 break-words">{value}</p>
    </div>
  );
}

function ChannelMetric({ label, value, highlight }: { label: string; value: string | number; highlight?: boolean }) {
  return (
    <div className="rounded-lg bg-neutral-900/50 px-2.5 py-1.5">
      <p className="text-[10px] text-neutral-500">{label}</p>
      <p className={cn('text-xs mt-0.5 font-mono', highlight ? 'text-amber-400' : 'text-neutral-300')}>{value}</p>
    </div>
  );
}

function TimelineItem({
  stage,
  last,
  benchmark,
  onOpen,
}: {
  stage: ChatTraceStageView;
  last: boolean;
  benchmark: { p50?: number; p90?: number; p99?: number } | null;
  onOpen: () => void;
}) {
  const tone = stageStateTone(stage.stageState);
  const [expanded, setExpanded] = useState(false);
  const comparison = formatBenchmarkComparison(stage.durationMs ?? undefined, benchmark);
  return (
    <div className="flex gap-3">
      <div className="flex flex-col items-center">
        <span className={cn('w-2.5 h-2.5 rounded-full mt-1.5', STAGE_TONE_DOT[tone])} />
        {!last && <span className="w-px flex-1 bg-neutral-800 my-1" />}
      </div>
      <div className="flex-1 mb-1">
        <button
          onClick={() => setExpanded((v) => !v)}
          className="w-full text-left p-3 rounded-lg border border-neutral-800 bg-neutral-900/40 hover:bg-neutral-900/70 transition-colors"
        >
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="text-xs font-mono text-neutral-500">{stage.stageOrder}</span>
              <span className="text-sm font-medium text-neutral-200">{stage.stageName}</span>
            </div>
            <div className="flex items-center gap-2 text-[11px] text-neutral-500">
              <span className={STAGE_TONE_CLASS[tone]}>{stageStateLabel(stage.stageState)}</span>
              {stage.durationMs != null && <span className="font-mono">{stage.durationMs} ms</span>}
              {comparison && (
                <span className={cn('font-medium', BENCHMARK_LEVEL_CLASS[comparison.level])}>{comparison.text}</span>
              )}
              {stage.summaryText && <CaretRight className={cn('w-3 h-3 transition-transform', expanded && 'rotate-90')} />}
            </div>
          </div>
          {stage.errorMessage && <p className="text-xs text-red-400 mt-1">{stage.errorMessage}</p>}
          {expanded && stage.summaryText && (
            <p className="text-xs text-neutral-400 mt-2 leading-relaxed whitespace-pre-wrap">{stage.summaryText}</p>
          )}
          {expanded && (stage.startTime || stage.endTime) && (
            <div className="flex gap-4 mt-2 text-[11px] text-neutral-600">
              <span>开始 {formatDateTime(stage.startTime)}</span>
              <span>结束 {formatDateTime(stage.endTime)}</span>
            </div>
          )}
        </button>
        <button
          onClick={onOpen}
          className="mt-1 ml-1 text-[11px] text-amber-400 hover:text-amber-300"
        >
          查看这个阶段的执行过程 →
        </button>
      </div>
    </div>
  );
}

function PromptPreview({ debugTrace }: { debugTrace: ChatDebugTrace | null }) {
  const [tab, setTab] = useState<'system' | 'user'>('system');
  const systemPrompt = debugTrace?.ragSystemPrompt || '';
  const userPrompt = debugTrace?.ragUserPrompt || '';
  if (!systemPrompt && !userPrompt) return null;
  return (
    <div className="mb-6 p-4 rounded-xl border border-neutral-800 bg-neutral-900/30">
      <h3 className="text-sm font-semibold text-neutral-200 mb-3">Prompt 预览</h3>
      <div className="flex gap-1 mb-3">
        <button
          onClick={() => setTab('system')}
          className={cn('px-3 py-1 rounded-lg text-xs', tab === 'system' ? 'bg-amber-500/15 text-amber-400' : 'text-neutral-400 hover:bg-neutral-800')}
        >
          System Prompt
        </button>
        <button
          onClick={() => setTab('user')}
          className={cn('px-3 py-1 rounded-lg text-xs', tab === 'user' ? 'bg-amber-500/15 text-amber-400' : 'text-neutral-400 hover:bg-neutral-800')}
        >
          User Prompt
        </button>
      </div>
      <pre className="text-xs text-neutral-300 bg-neutral-950/60 rounded-lg p-3 overflow-x-auto whitespace-pre-wrap max-h-[360px]">
        {tab === 'system' ? systemPrompt || '无' : userPrompt || '无'}
      </pre>
    </div>
  );
}

function BenchmarksCard({ benchmarks }: { benchmarks: StageBenchmarkView[] }) {
  if (!benchmarks.length) return null;
  return (
    <div className="p-4 rounded-xl border border-neutral-800 bg-neutral-900/30">
      <h3 className="text-sm font-semibold text-neutral-200 mb-3">阶段基准（P50 / P90 / P99）</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-neutral-500 border-b border-neutral-800">
              <th className="py-2 pr-3 font-medium">阶段编码</th>
              <th className="py-2 pr-3 font-medium">P50</th>
              <th className="py-2 pr-3 font-medium">P90</th>
              <th className="py-2 pr-3 font-medium">P99</th>
              <th className="py-2 pr-3 font-medium">avg</th>
              <th className="py-2 pr-3 font-medium">max</th>
              <th className="py-2 font-medium">样本</th>
            </tr>
          </thead>
          <tbody>
            {benchmarks.map((b, i) => (
              <tr key={i} className="border-b border-neutral-800/50">
                <td className="py-2 pr-3 text-neutral-300 font-mono">{b.stageCode}</td>
                <td className="py-2 pr-3 text-neutral-400 font-mono">{b.p50 ?? '-'}</td>
                <td className="py-2 pr-3 text-neutral-400 font-mono">{b.p90 ?? '-'}</td>
                <td className="py-2 pr-3 text-neutral-400 font-mono">{b.p99 ?? '-'}</td>
                <td className="py-2 pr-3 text-neutral-400 font-mono">{b.avg ?? '-'}</td>
                <td className="py-2 pr-3 text-neutral-400 font-mono">{b.max ?? '-'}</td>
                <td className="py-2 text-neutral-500">{b.sampleCount ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function TraceOverlay({
  stage,
  benchmark,
  onClose,
}: {
  stage: ChatTraceStageView;
  benchmark: { p50?: number; p90?: number; p99?: number } | null;
  onClose: () => void;
}) {
  const tone = stageStateTone(stage.stageState);
  const comparison = formatBenchmarkComparison(stage.durationMs ?? undefined, benchmark);
  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-md h-full bg-neutral-950 border-l border-neutral-800 overflow-y-auto">
        <div className="sticky top-0 flex items-center justify-between gap-2 px-4 py-3 border-b border-neutral-800 bg-neutral-950">
          <div>
            <p className="text-[11px] font-mono text-neutral-500">阶段 {stage.stageOrder}</p>
            <h3 className="text-sm font-semibold text-neutral-100">{stage.stageName}</h3>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-md text-neutral-500 hover:text-neutral-200 hover:bg-neutral-800">
            <X className="w-4 h-4" />
          </button>
        </div>
        <div className="p-4 space-y-3">
          <div className="flex flex-wrap gap-2">
            <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px]', STAGE_TONE_CLASS[tone])}>
              {stageStateLabel(stage.stageState)}
            </span>
            {stage.executionMode != null && (
              <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] bg-neutral-800 text-neutral-300">
                {executionModeLabel(stage.executionMode)}
              </span>
            )}
          </div>
          <div className="grid grid-cols-2 gap-3">
            <OverlayMetric label="耗时" value={stage.durationMs != null ? `${stage.durationMs} ms` : '无'} />
            <OverlayMetric label="基准对比" value={comparison?.text ?? '无基准'} tone={comparison ? BENCHMARK_LEVEL_CLASS[comparison.level] : undefined} />
            <OverlayMetric label="开始时间" value={formatDateTime(stage.startTime)} />
            <OverlayMetric label="结束时间" value={formatDateTime(stage.endTime)} />
          </div>
          {stage.summaryText && (
            <div>
              <p className="text-[11px] text-neutral-500 mb-1">阶段摘要</p>
              <p className="text-xs text-neutral-300 leading-relaxed whitespace-pre-wrap rounded-lg bg-neutral-900/50 p-3">{stage.summaryText}</p>
            </div>
          )}
          {stage.errorMessage && (
            <div>
              <p className="text-[11px] text-red-400 mb-1">错误信息</p>
              <p className="text-xs text-red-300 rounded-lg bg-red-500/10 border border-red-500/30 p-3 whitespace-pre-wrap">{stage.errorMessage}</p>
            </div>
          )}
          {stage.traceId && (
            <div>
              <p className="text-[11px] text-neutral-500 mb-1">Trace ID</p>
              <code className="text-[11px] text-neutral-400 font-mono break-all">{stage.traceId}</code>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function OverlayMetric({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="rounded-lg bg-neutral-900/50 px-3 py-2">
      <p className="text-[10px] text-neutral-500">{label}</p>
      <p className={cn('text-xs mt-0.5', tone ?? 'text-neutral-200')}>{value}</p>
    </div>
  );
}
