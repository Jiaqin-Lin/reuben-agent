import { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowsClockwise, CircleNotch, CaretRight } from '@phosphor-icons/react';
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
} from '../../types/chat';
import {
  stageStateLabel,
  stageStateTone,
  executionModeLabel,
  channelLabel,
  formatDateTime,
  formatLatency,
  formatScore,
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

function errMsg(e: unknown, fallback: string): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return fallback;
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
              <MetaItem label="执行模式" value={executionModeLabel(turn.executionMode)} />
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
                  <TimelineItem key={s.id} stage={s} last={i === stages.length - 1} />
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

          {/* 检索结果双通道对比 */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-6">
            <RetrievalResultsCard results={results} />
            <ChannelExecutionsCard channels={channels} />
          </div>

          {/* Stage Benchmarks */}
          <BenchmarksCard benchmarks={benchmarks} />
        </>
      )}
    </AdminPage>
  );
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[11px] text-neutral-500">{label}</p>
      <p className="text-sm text-neutral-200 mt-0.5 break-words">{value}</p>
    </div>
  );
}

function TimelineItem({ stage, last }: { stage: ChatTraceStageView; last: boolean }) {
  const tone = stageStateTone(stage.stageState);
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="flex gap-3">
      <div className="flex flex-col items-center">
        <span className={cn('w-2.5 h-2.5 rounded-full mt-1.5', STAGE_TONE_DOT[tone])} />
        {!last && <span className="w-px flex-1 bg-neutral-800 my-1" />}
      </div>
      <button
        onClick={() => setExpanded((v) => !v)}
        className="flex-1 text-left p-3 rounded-lg border border-neutral-800 bg-neutral-900/40 hover:bg-neutral-900/70 transition-colors mb-1"
      >
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <span className="text-xs font-mono text-neutral-500">{stage.stageOrder}</span>
            <span className="text-sm font-medium text-neutral-200">{stage.stageName}</span>
          </div>
          <div className="flex items-center gap-2 text-[11px] text-neutral-500">
            <span className={STAGE_TONE_CLASS[tone]}>{stageStateLabel(stage.stageState)}</span>
            {stage.durationMs != null && <span className="font-mono">{stage.durationMs} ms</span>}
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
    </div>
  );
}

function RetrievalResultsCard({ results }: { results: RetrievalResultView[] }) {
  return (
    <div className="p-4 rounded-xl border border-neutral-800 bg-neutral-900/30">
      <h3 className="text-sm font-semibold text-neutral-200 mb-3">检索结果（{results.length}）</h3>
      {results.length === 0 ? (
        <p className="text-xs text-neutral-600 py-4 text-center">本轮没有检索结果</p>
      ) : (
        <div className="space-y-1.5 max-h-[360px] overflow-y-auto">
          {results.map((r) => (
            <div key={r.id} className="p-2.5 rounded-lg border border-neutral-800 bg-neutral-900/40">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs text-neutral-200 truncate">{r.documentName || `文档 ${r.documentId}`}</span>
                <span className="text-[11px] text-amber-400 font-mono shrink-0">
                  {channelLabel(r.channelType)} · {formatScore(r.finalScore ?? r.rerankScore ?? undefined)}
                </span>
              </div>
              {r.sectionPath && <p className="text-[11px] text-neutral-500 mt-0.5">{r.sectionPath}</p>}
              {r.chunkTextPreview && <p className="text-[11px] text-neutral-600 mt-1 line-clamp-2">{r.chunkTextPreview}</p>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function ChannelExecutionsCard({ channels }: { channels: ChannelExecutionView[] }) {
  return (
    <div className="p-4 rounded-xl border border-neutral-800 bg-neutral-900/30">
      <h3 className="text-sm font-semibold text-neutral-200 mb-3">双通道执行（{channels.length}）</h3>
      {channels.length === 0 ? (
        <p className="text-xs text-neutral-600 py-4 text-center">本轮没有通道执行记录</p>
      ) : (
        <div className="space-y-1.5 max-h-[360px] overflow-y-auto">
          {channels.map((c) => (
            <div key={c.id} className="p-2.5 rounded-lg border border-neutral-800 bg-neutral-900/40">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium text-neutral-200">{channelLabel(c.channelType)}</span>
                {c.durationMs != null && <span className="text-[11px] text-neutral-500 font-mono">{c.durationMs} ms</span>}
              </div>
              <div className="flex flex-wrap gap-3 mt-1.5 text-[11px] text-neutral-500">
                <span>召回 {c.recalledCount ?? 0}</span>
                <span>通过 {c.acceptedCount ?? 0}</span>
                <span>入选 {c.finalSelectedCount ?? 0}</span>
                {c.maxScore != null && <span>最高 {formatScore(c.maxScore)}</span>}
              </div>
            </div>
          ))}
        </div>
      )}
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
