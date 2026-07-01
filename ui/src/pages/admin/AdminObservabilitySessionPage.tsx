import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowsClockwise, CircleNotch, Sparkle } from '@phosphor-icons/react';
import { AdminPage } from '../../components/admin/AdminLayout';
import { useToast } from '../../components/shared/Toast';
import { ApiError } from '../../types/api';
import { getSession, rebuildSummary } from '../../api/chat';
import type { ConversationView, ChatTurnVo, ChatDebugTrace } from '../../types/chat';
import {
  chatModeLabel,
  turnStatusLabel,
  turnStatusTone,
  executionModeLabel,
  formatTime,
  formatLatency,
  shortenId,
  truncate,
} from '../../lib/observability';
import { cn } from '../../lib/cn';

const POLL_INTERVAL = 4000;

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

export function AdminObservabilitySessionPage() {
  const { conversationId = '' } = useParams();
  const { toast } = useToast();
  const nav = useNavigate();
  const [session, setSession] = useState<ConversationView | null>(null);
  const [loading, setLoading] = useState(true);
  const [rebuilding, setRebuilding] = useState(false);
  const [polling, setPolling] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async () => {
    try {
      const s = await getSession(conversationId);
      setSession(s);
    } catch (e) {
      toast(errMsg(e, '加载会话详情失败'), 'error');
    } finally {
      setLoading(false);
      setPolling(false);
    }
  }, [conversationId, toast]);

  useEffect(() => {
    setLoading(true);
    load();
  }, [load]);

  // running 会话实时轮询
  useEffect(() => {
    const running = session?.sessionStatus === 2;
    if (!running) {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
      return;
    }
    setPolling(true);
    timerRef.current = setInterval(load, POLL_INTERVAL);
    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [session, load]);

  const handleRebuildSummary = async () => {
    if (!session) return;
    setRebuilding(true);
    try {
      await rebuildSummary(session.conversationId);
      toast('长期摘要已重建', 'success');
      await load();
    } catch (e) {
      toast(errMsg(e, '重建摘要失败'), 'error');
    } finally {
      setRebuilding(false);
    }
  };

  const running = session?.sessionStatus === 2;
  const turns = session?.recentTurns ?? (session?.latestTurn ? [session.latestTurn] : []);

  return (
    <AdminPage>
      <div className="flex items-center justify-between gap-3 mb-4">
        <button
          onClick={() => nav('/admin/observability')}
          className="inline-flex items-center gap-1.5 text-sm text-neutral-400 hover:text-amber-400"
        >
          <ArrowLeft className="w-4 h-4" />
          返回会话列表
        </button>
        <div className="flex items-center gap-2">
          {(running || polling) && (
            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] bg-amber-500/15 text-amber-400">
              <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse" />
              {polling ? '实时轮询中' : '会话运行中'}
            </span>
          )}
          <button
            onClick={() => load()}
            disabled={loading}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-neutral-700 text-neutral-300 text-xs hover:bg-neutral-800 disabled:opacity-50"
          >
            {loading ? <CircleNotch className="w-3.5 h-3.5 animate-spin" /> : <ArrowsClockwise className="w-3.5 h-3.5" />}
            刷新
          </button>
          <button
            onClick={handleRebuildSummary}
            disabled={!session || rebuilding}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-500 text-black text-xs font-semibold hover:bg-amber-400 disabled:opacity-50"
          >
            {rebuilding ? <CircleNotch className="w-3.5 h-3.5 animate-spin" /> : <Sparkle className="w-3.5 h-3.5" />}
            重建长期摘要
          </button>
        </div>
      </div>

      {loading && !session ? (
        <div className="flex items-center justify-center py-16 text-neutral-500">
          <CircleNotch className="w-5 h-5 animate-spin mr-2" />
          正在加载会话详情...
        </div>
      ) : !session ? (
        <div className="py-16 text-center text-neutral-600 text-sm">没有找到这条会话</div>
      ) : (
        <>
          <div className="p-5 rounded-xl border border-neutral-800 bg-neutral-900/40 mb-4">
            <div className="flex flex-wrap items-center gap-2 mb-2">
              <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] bg-amber-500/10 text-amber-400">
                {chatModeLabel(session.chatMode)}
              </span>
              {running ? (
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] bg-amber-500/15 text-amber-400">
                  当前会话仍在执行
                </span>
              ) : session.latestTurn?.turnStatus ? (
                <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px]', STATUS_TONE_CLASS[turnStatusTone(session.latestTurn.turnStatus)])}>
                  最近一轮{turnStatusLabel(session.latestTurn.turnStatus)}
                </span>
              ) : null}
              <span className="text-[11px] text-neutral-500 font-mono">会话ID {shortenId(session.conversationId)}</span>
            </div>
            <h2 className="text-base font-semibold text-neutral-100">
              {session.selectedDocumentName || session.title || '未命名会话'}
            </h2>
            <p className="text-xs text-neutral-500 mt-1">
              这个页面只负责看整条会话里的每次问答，不展示单轮内部细节。先从下方轮次列表里找到你关心的那一轮，再进入专门的轮次详情页。
            </p>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 mt-4">
              <MetaItem label="助手轮次" value={String(turns.length)} />
              <MetaItem label="会话消息数" value={String(session.messageCount ?? 0)} />
              <MetaItem
                label="长期摘要"
                value={session.memorySummary?.compressionApplied ? '已形成' : '未形成'}
              />
              <MetaItem label="最近更新" value={formatTime(session.updateTime)} />
            </div>
          </div>

          {/* Session Context 区 */}
          <div className="p-5 rounded-xl border border-neutral-800 bg-neutral-900/40 mb-4">
            <div className="mb-3">
              <span className="block text-[10px] font-mono uppercase tracking-wider text-neutral-500">Session Context</span>
              <h3 className="text-sm font-semibold text-neutral-200 mt-0.5">会话级背景</h3>
              <p className="text-xs text-neutral-500 mt-0.5">只解释整条会话的上下文、最近状态和记忆压缩，不进入某一轮内部链路。</p>
            </div>
            <dl className="space-y-0">
              <ContextRow label="最近用户问题" value={session.latestUserMessage || '无'} />
              <ContextRow label="最近助手回答" value={session.latestAssistantMessage ? truncate(session.latestAssistantMessage, 200) : '无'} />
              <ContextRow
                label="Checkpoint / 消息数"
                value={`${session.checkpointCount ?? 0} / ${session.messageCount ?? 0}`}
              />
            </dl>

            {session.memorySummary?.compressionApplied ? (
              <div className="mt-4 pt-4 border-t border-neutral-800">
                <div className="mb-2.5">
                  <span className="block text-[10px] font-mono uppercase tracking-wider text-neutral-500">Memory</span>
                  <h4 className="text-sm font-semibold text-neutral-200 mt-0.5">长期摘要快照</h4>
                </div>
                <div className="flex flex-wrap gap-1.5 mb-2.5">
                  <span className="inline-flex px-2 py-0.5 rounded bg-neutral-800 text-neutral-300 text-[11px] font-mono">
                    covered {session.memorySummary.coveredExchangeCount ?? 0}
                  </span>
                  <span className="inline-flex px-2 py-0.5 rounded bg-neutral-800 text-neutral-300 text-[11px] font-mono">
                    version {session.memorySummary.summaryVersion ?? 0}
                  </span>
                  <span className="inline-flex px-2 py-0.5 rounded bg-neutral-800 text-neutral-300 text-[11px] font-mono">
                    compress {session.memorySummary.compressionCount ?? 0}
                  </span>
                </div>
                <pre className="m-0 p-3 rounded-lg bg-neutral-900 border border-neutral-800 text-neutral-300 text-xs whitespace-pre-wrap leading-relaxed font-mono">
                  {session.memorySummary.summaryText || '无'}
                </pre>
              </div>
            ) : (
              <div className="mt-3 p-3 rounded-lg bg-neutral-900 border border-neutral-800 text-xs text-neutral-500">
                当前会话还没有形成长期摘要。常见原因是轮次还不够，或者摘要预热尚未完成。
              </div>
            )}
          </div>

          <h3 className="text-sm font-semibold text-neutral-200 mb-3">轮次列表（{turns.length}）</h3>
          {turns.length === 0 ? (
            <div className="py-12 text-center text-neutral-600 text-sm">当前会话还没有助手轮次</div>
          ) : (
            <div className="space-y-2">
              {[...turns].reverse().map((turn, idx) => (
                <TurnRow
                  key={turn.turnId}
                  turn={turn}
                  index={turns.length - idx}
                  conversationId={session.conversationId}
                  onClick={() => nav(`/admin/observability/${session.conversationId}/exchanges/${turn.turnId}`)}
                />
              ))}
            </div>
          )}
        </>
      )}
    </AdminPage>
  );
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[11px] text-neutral-500">{label}</p>
      <p className="text-sm text-neutral-200 mt-0.5 truncate">{value}</p>
    </div>
  );
}

function ContextRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-4 py-2.5 border-b border-neutral-800/60 last:border-b-0">
      <dt className="shrink-0 w-32 text-xs text-neutral-500">{label}</dt>
      <dd className="m-0 text-sm text-neutral-200 leading-relaxed break-words">{value}</dd>
    </div>
  );
}

function parseDebugTrace(raw?: string): ChatDebugTrace | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as ChatDebugTrace) : null;
  } catch {
    return null;
  }
}

function countJsonList(raw?: string): number {
  if (!raw) return 0;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.length : 0;
  } catch {
    return 0;
  }
}

function TurnRow({
  turn,
  index,
  conversationId,
  onClick,
}: {
  turn: ChatTurnVo;
  index: number;
  conversationId: string;
  onClick: () => void;
}) {
  const tone = turnStatusTone(turn.turnStatus);
  const debugTrace = useMemo(() => parseDebugTrace(turn.debugTraceJson), [turn.debugTraceJson]);
  const referenceCount = countJsonList(turn.sourceSnapshotList);
  const recommendationCount = countJsonList(turn.followupSuggestionList);
  const tokenCount = (debugTrace?.modelUsageTraces ?? []).reduce((sum, t) => sum + Number(t.totalTokens ?? 0), 0);
  const costSum = (debugTrace?.modelUsageTraces ?? []).reduce((s, t) => s + Number(t.estimatedCost ?? 0), 0);
  const costText = costSum > 0 ? `¥ ${costSum.toFixed(4)}` : '无';
  return (
    <button
      onClick={onClick}
      className="w-full text-left p-4 rounded-xl border border-neutral-800 bg-neutral-900/40 hover:border-amber-500/30 hover:bg-neutral-900/70 transition-colors"
    >
      <div className="flex items-center justify-between gap-2 mb-1.5">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-neutral-500">第 {index} 轮</span>
          <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-[11px]', STATUS_TONE_CLASS[tone])}>
            {turnStatusLabel(turn.turnStatus)}
          </span>
          {turn.executionMode != null && (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[11px] bg-neutral-800 text-neutral-300">
              {executionModeLabel(turn.executionMode)}
            </span>
          )}
        </div>
        <span className="text-[11px] text-neutral-500 shrink-0">{formatTime(turn.createTime)}</span>
      </div>
      <p className="text-sm text-neutral-200 line-clamp-1">问：{turn.userPrompt || '未记录问题'}</p>
      {turn.replyContent && <p className="text-xs text-neutral-500 mt-1 line-clamp-2">答：{turn.replyContent}</p>}
      <div className="flex flex-wrap items-center gap-3 mt-2 text-[11px] text-neutral-600">
        <span>耗时 {formatLatency(turn.totalLatencyMs ?? undefined)}</span>
        <span>引用 {referenceCount}</span>
        <span>推荐 {recommendationCount}</span>
        <span>Token {tokenCount || '无'}</span>
        <span>成本 {costText}</span>
        <code className="font-mono ml-auto">{conversationId.slice(0, 10)}.../turn {turn.turnId}</code>
      </div>
    </button>
  );
}
