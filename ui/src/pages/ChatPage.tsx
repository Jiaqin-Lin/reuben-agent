import { useCallback, useEffect, useRef, useState } from 'react';
import { Sparkle, PencilSimple, ArrowCounterClockwise, Check, X, List } from '@phosphor-icons/react';
import { SessionSidebar } from '../components/chat/SessionSidebar';
import { MessageBubble } from '../components/chat/MessageBubble';
import { ChatComposer } from '../components/chat/ChatComposer';
import { useSSE } from '../hooks/useSSE';
import { useToast } from '../components/shared/Toast';
import {
  listSessions,
  getSession,
  deleteSession as apiDeleteSession,
  renameSession as apiRenameSession,
  resetSession as apiResetSession,
  listDocumentOptions,
} from '../api/chat';
import { pageQueryRouteTrace } from '../api/knowledge';
import { ChatMode } from '../types/chat';
import type {
  ConversationSessionListVo,
  KnowledgeDocumentOptionVo,
  ChatStreamEvent,
} from '../types/chat';
import { mapTurnsToMessages, type DisplayMessage } from '../types/displayMessage';
import { buildRouteTraceLookup, buildChatRouteExplain } from '../lib/knowledgeRoute';
import { ApiError } from '../types/api';

const PROMPT_CHIPS = [
  '请先介绍一下你能帮我做哪些事情，并给出几个典型使用场景',
  '请帮我把一个复杂问题拆成清晰的分析步骤，并给出执行建议',
  '结合当前项目，帮我梳理对话能力、知识库能力和后台能力之间的关系',
];

function normalizeError(e: unknown, fallback: string): string {
  if (e instanceof ApiError && e.message) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

function createConversationId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
}

export function ChatPage() {
  const { toast } = useToast();
  const { isStreaming, isStopping, start, stop } = useSSE();

  const [sessions, setSessions] = useState<ConversationSessionListVo[]>([]);
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [loadingConversation, setLoadingConversation] = useState(false);
  const [pageError, setPageError] = useState('');

  const [currentId, setCurrentId] = useState('');
  const [displayMessages, setDisplayMessages] = useState<DisplayMessage[]>([]);
  const [routeLookup, setRouteLookup] = useState<Record<string, ReturnType<typeof buildChatRouteExplain>>>({});

  const [input, setInput] = useState('');
  const [chatMode, setChatMode] = useState<number>(ChatMode.OPEN_CHAT);

  const [documentOptions, setDocumentOptions] = useState<KnowledgeDocumentOptionVo[]>([]);
  const [selectedDocumentId, setSelectedDocumentId] = useState('');

  // 阶段：会话重命名 / 重置
  const [renaming, setRenaming] = useState(false);
  const [renameValue, setRenameValue] = useState('');
  const [renamingBusy, setRenamingBusy] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);
  const renameInputRef = useRef<HTMLInputElement>(null);

  const currentAssistantIdRef = useRef('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  // 镜像 displayMessages，供 onComplete 异步回调读取最新值（避免闭包陈旧）
  const displayMessagesRef = useRef<DisplayMessage[]>([]);

  const sortedSessions = [...sessions].sort((a, b) => {
    const ta = a.updateTime ? new Date(a.updateTime).getTime() : 0;
    const tb = b.updateTime ? new Date(b.updateTime).getTime() : 0;
    return tb - ta;
  });

  // 阶段：滚动到底部
  const scrollToBottom = useCallback(() => {
    requestAnimationFrame(() => {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    });
  }, []);

  const refreshSessions = useCallback(async () => {
    setLoadingSessions(true);
    try {
      const page = await listSessions({ pageNo: 1, pageSize: 100 });
      setSessions(page.records ?? []);
    } catch (e) {
      setPageError(normalizeError(e, '加载会话列表失败'));
    } finally {
      setLoadingSessions(false);
    }
  }, []);

  const refreshDocumentOptions = useCallback(async () => {
    try {
      const data = await listDocumentOptions();
      setDocumentOptions(data ?? []);
    } catch (e) {
      toast(normalizeError(e, '加载可选知识文档失败'), 'error');
    }
  }, [toast]);

  const loadConversation = useCallback(
    async (conversationId: string) => {
      if (!conversationId || isStreaming) return;
      setLoadingConversation(true);
      setPageError('');
      try {
        const [sessionRes, traceRes] = await Promise.allSettled([
          getSession(conversationId),
          pageQueryRouteTrace({ conversationId, pageNo: 1, pageSize: 200 }),
        ]);

        if (sessionRes.status !== 'fulfilled') throw sessionRes.reason;

        const session = sessionRes.value;
        const lookup = traceRes.status === 'fulfilled'
          ? buildRouteTraceLookup(traceRes.value.records ?? [])
          : {};
        const explainLookup: Record<string, ReturnType<typeof buildChatRouteExplain>> = {};
        for (const [k, v] of Object.entries(lookup)) {
          explainLookup[k] = buildChatRouteExplain(v);
        }
        setRouteLookup(explainLookup);

        setCurrentId(conversationId);
        const mapped = mapTurnsToMessages(session.recentTurns ?? [], explainLookup);
        displayMessagesRef.current = mapped;
        setDisplayMessages(mapped);
        setChatMode(session.chatMode ?? ChatMode.OPEN_CHAT);
        setSelectedDocumentId(session.selectedDocumentId ? String(session.selectedDocumentId) : '');
        await scrollToBottom();
      } catch (e) {
        setPageError(normalizeError(e, '加载会话详情失败'));
      } finally {
        setLoadingConversation(false);
        setMobileSidebarOpen(false);
      }
    },
    [isStreaming, scrollToBottom],
  );

  const startNewConversation = useCallback(() => {
    if (isStreaming) return;
    setCurrentId(createConversationId());
    displayMessagesRef.current = [];
    setDisplayMessages([]);
    setInput('');
    setPageError('');
    setMobileSidebarOpen(false);
  }, [isStreaming]);

  const handleDelete = useCallback(
    async (id: string) => {
      if (!id || isStreaming) return;
      try {
        await apiDeleteSession(id);
        setSessions((prev) => prev.filter((s) => s.conversationId !== id));
        if (currentId === id) {
          const next = sortedSessions.find((s) => s.conversationId !== id);
          if (next) await loadConversation(next.conversationId);
          else startNewConversation();
        }
        toast('会话已删除', 'success');
      } catch (e) {
        toast(normalizeError(e, '删除会话失败'), 'error');
      }
    },
    [isStreaming, currentId, sortedSessions, loadConversation, startNewConversation, toast],
  );

  const handleModeChange = useCallback(
    (mode: number) => {
      if (isStreaming || chatMode === mode) return;
      setChatMode(mode);
      setPageError('');
      if (displayMessages.length > 0) startNewConversation();
    },
    [isStreaming, chatMode, displayMessages.length, startNewConversation],
  );

  // 阶段：把流式事件合并进当前助手消息
  const applyStreamEvent = useCallback(
    (event: ChatStreamEvent) => {
      const id = currentAssistantIdRef.current;
      setDisplayMessages((prev) => {
        const idx = prev.findIndex((m) => m.id === id);
        if (idx === -1) return prev;
        const next = [...prev];
        const msg = { ...next[idx] };
        if (event.type === 'text') {
          msg.content += (event.content as string) || '';
        } else if (event.type === 'thinking') {
          const step = (event.content as string) || '';
          if (step && !msg.thinkingSteps.includes(step)) msg.thinkingSteps = [...msg.thinkingSteps, step];
        } else if (event.type === 'reference') {
          msg.references = Array.isArray(event.content) ? event.content : [];
        } else if (event.type === 'recommend') {
          msg.recommendations = Array.isArray(event.content) ? (event.content as string[]) : [];
        } else if (event.type === 'status') {
          msg.statusText = (event.content as string) || '';
        } else if (event.type === 'error') {
          msg.errorMessage = (event.content as string) || '对话执行失败';
          msg.status = 'FAILED';
        }
        msg.updatedAt = new Date().toISOString();
        next[idx] = msg;
        displayMessagesRef.current = next;
        return next;
      });
      scrollToBottom();
    },
    [scrollToBottom],
  );

  const sendMessage = useCallback(
    async (presetQuestion?: string) => {
      const question = (presetQuestion || input).trim();
      if (!question || isStreaming) return;
      if (chatMode === ChatMode.DOCUMENT && !selectedDocumentId) {
        setPageError('当前文档问答模式下请先选择一个文档');
        return;
      }

      const conversationId = currentId || createConversationId();
      const assistantId = `assistant-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
      const userMsg: DisplayMessage = {
        id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        role: 'user',
        content: question,
        thinkingSteps: [],
        references: [],
        recommendations: [],
        status: '',
        statusText: '',
        errorMessage: '',
        routeExplain: null,
        createdAt: new Date().toISOString(),
      };
      const assistantMsg: DisplayMessage = {
        id: assistantId,
        role: 'assistant',
        content: '',
        thinkingSteps: [],
        references: [],
        recommendations: [],
        status: 'RUNNING',
        statusText: '',
        errorMessage: '',
        routeExplain: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };

      currentAssistantIdRef.current = assistantId;
      setCurrentId(conversationId);
      setPageError('');
      setDisplayMessages((prev) => {
        const next = [...prev, userMsg, assistantMsg];
        displayMessagesRef.current = next;
        return next;
      });
      if (!presetQuestion) setInput('');

      await scrollToBottom();

      const selectedDocNum = chatMode === ChatMode.DOCUMENT ? Number(selectedDocumentId) || null : null;
      const selectedDocName = selectedDocNum
        ? documentOptions.find((d) => d.documentId === selectedDocNum)?.documentName
        : null;

      await start(
        {
          question,
          conversationId,
          chatMode,
          selectedDocumentId: selectedDocNum,
          selectedDocumentName: selectedDocName ?? null,
        },
        {
          onEvent: applyStreamEvent,
          onError: (msg) => {
            setDisplayMessages((prev) => {
              const next = prev.map((m) =>
                m.id === assistantId ? { ...m, errorMessage: msg, status: 'FAILED' } : m,
              );
              displayMessagesRef.current = next;
              return next;
            });
            setPageError(msg);
          },
          onComplete: async () => {
            try {
              await refreshSessions();
              const exists = (await listSessions({ pageNo: 1, pageSize: 100 })).records.some(
                (s) => s.conversationId === conversationId,
              );
              if (!exists) return;
              // 失败回合（assistant 仍有 errorMessage 或无 content 且 FAILED）不覆盖本地状态，
              // 否则会用后端未落库的空 turns 冲掉刚发出去的气泡，造成"闪一下回到欢迎页"。
              const failed = displayMessagesRef.current.some(
                (m) =>
                  m.role === 'assistant' &&
                  (m.errorMessage || (!m.content && m.status === 'FAILED')),
              );
              if (failed) return;
              await loadConversation(conversationId);
            } catch {
              // 错误已落入页面提示
            }
          },
        },
      );
    },
    [
      input,
      isStreaming,
      chatMode,
      selectedDocumentId,
      currentId,
      documentOptions,
      start,
      applyStreamEvent,
      refreshSessions,
      loadConversation,
      scrollToBottom,
    ],
  );

  const handleStop = useCallback(() => {
    if (currentId) stop(currentId);
  }, [currentId, stop]);

  const latestAssistantId = [...displayMessages].reverse().find((m) => m.role === 'assistant')?.id;
  const latestRouteTopDocumentName = (() => {
    for (const msg of [...displayMessages].reverse()) {
      if (msg.role === 'assistant' && msg.routeExplain?.topDocument) {
        return msg.routeExplain.topDocument.documentName || String(msg.routeExplain.topDocument.documentId ?? '');
      }
    }
    return '';
  })();
  const activeSession = sessions.find((s) => s.conversationId === currentId);
  const activeTitle = activeSession?.title || activeSession?.latestTurn?.userPrompt || '新的对话';

  const startRename = useCallback(() => {
    if (isStreaming || !activeSession) return;
    setRenameValue(activeSession.title || activeSession.latestTurn?.userPrompt || '新的对话');
    setRenaming(true);
    requestAnimationFrame(() => renameInputRef.current?.focus());
  }, [isStreaming, activeSession]);

  const cancelRename = useCallback(() => {
    setRenaming(false);
    setRenameValue('');
    setRenamingBusy(false);
  }, []);

  const commitRename = useCallback(async () => {
    const title = renameValue.trim();
    if (!title || !currentId) return;
    setRenamingBusy(true);
    try {
      await apiRenameSession({ conversationId: currentId, title });
      setSessions((prev) =>
        prev.map((s) => (s.conversationId === currentId ? { ...s, title } : s)),
      );
      setRenaming(false);
      toast('会话标题已更新', 'success');
    } catch (e) {
      toast(normalizeError(e, '重命名失败'), 'error');
    } finally {
      setRenamingBusy(false);
    }
  }, [renameValue, currentId, toast]);

  const handleReset = useCallback(async () => {
    if (!currentId || isStreaming) return;
    setResetting(true);
    try {
      const res = await apiResetSession(currentId);
      await loadConversation(currentId);
      await refreshSessions();
      toast(`已重置会话，清理 ${res.removedTurnCount} 轮记录`, 'success');
    } catch (e) {
      toast(normalizeError(e, '重置失败'), 'error');
    } finally {
      setResetting(false);
      setConfirmReset(false);
    }
  }, [currentId, isStreaming, loadConversation, refreshSessions, toast]);

  // 阶段：初始化
  useEffect(() => {
    (async () => {
      await Promise.all([refreshDocumentOptions(), refreshSessions()]);
      const first = (await listSessions({ pageNo: 1, pageSize: 100 })).records[0];
      if (first) await loadConversation(first.conversationId);
      else startNewConversation();
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="flex h-screen">
      {/* 桌面侧边栏 */}
      <div className="hidden lg:block w-[280px] shrink-0">
        <SessionSidebar
          sessions={sortedSessions}
          currentId={currentId}
          loading={loadingSessions}
          disabled={isStreaming}
          onSelect={loadConversation}
          onDelete={handleDelete}
          onNew={startNewConversation}
        />
      </div>

      {/* 移动端侧边栏抽屉 */}
      {mobileSidebarOpen && (
        <div className="lg:hidden fixed inset-0 z-40 flex">
          <div
            className="w-[280px] h-full bg-neutral-950 border-r border-neutral-800"
            onClick={(e) => e.stopPropagation()}
          >
            <SessionSidebar
              sessions={sortedSessions}
              currentId={currentId}
              loading={loadingSessions}
              disabled={isStreaming}
              onSelect={loadConversation}
              onDelete={handleDelete}
              onNew={startNewConversation}
            />
          </div>
          <div className="flex-1 bg-black/50" onClick={() => setMobileSidebarOpen(false)} />
        </div>
      )}

      {/* 主面板 */}
      <div className="flex-1 flex flex-col min-w-0 bg-neutral-950">
        <header className="px-4 lg:px-6 py-3.5 border-b border-neutral-800 flex items-center justify-between gap-3">
          <div className="flex items-center gap-2 min-w-0">
            <button
              onClick={() => setMobileSidebarOpen(true)}
              className="lg:hidden shrink-0 w-9 h-9 grid place-items-center rounded-md border border-neutral-800 text-neutral-300"
              title="打开会话列表"
            >
              <List className="w-4 h-4" />
            </button>
            {renaming ? (
            <div className="flex items-center gap-2 min-w-0">
              <input
                ref={renameInputRef}
                value={renameValue}
                onChange={(e) => setRenameValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') commitRename();
                  if (e.key === 'Escape') cancelRename();
                }}
                disabled={renamingBusy}
                className="flex-1 min-w-0 px-3 py-1.5 rounded-lg bg-neutral-900 border border-amber-500/40 text-sm text-neutral-100 outline-none focus:border-amber-500/60"
              />
              <button
                onClick={commitRename}
                disabled={renamingBusy || !renameValue.trim()}
                title="确认"
                className="shrink-0 w-8 h-8 grid place-items-center rounded-lg bg-amber-500/15 text-amber-400 hover:bg-amber-500/25 transition-colors disabled:opacity-40"
              >
                <Check weight="bold" className="w-4 h-4" />
              </button>
              <button
                onClick={cancelRename}
                disabled={renamingBusy}
                title="取消"
                className="shrink-0 w-8 h-8 grid place-items-center rounded-lg border border-neutral-700 text-neutral-400 hover:text-neutral-200 transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-2 min-w-0">
              <h1 className="text-base font-semibold text-neutral-100 truncate">{activeTitle}</h1>
              {activeSession && (
                <button
                  onClick={startRename}
                  disabled={isStreaming}
                  title="重命名会话"
                  className="shrink-0 w-7 h-7 grid place-items-center rounded-md text-neutral-500 hover:text-amber-400 hover:bg-neutral-800 transition-colors disabled:opacity-30"
                >
                  <PencilSimple className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          )}
          </div>

          <div className="flex items-center gap-3 shrink-0">
            {activeSession && displayMessages.length > 0 && (
              <button
                onClick={() => setConfirmReset(true)}
                disabled={isStreaming}
                title="重置当前会话（清空历史轮次，保留会话）"
                className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border border-neutral-800 bg-neutral-900 text-neutral-400 text-xs hover:text-red-400 hover:border-red-500/30 transition-colors disabled:opacity-40"
              >
                <ArrowCounterClockwise className="w-3.5 h-3.5" />
                重置
              </button>
            )}
            {isStreaming && (
              <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-amber-500/10 text-amber-400 text-[11px] font-mono">
                <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse" />
                正在生成回答...
              </span>
            )}
          </div>
        </header>

        {confirmReset && (
          <div className="mx-6 mt-4 px-4 py-3 rounded-lg bg-red-500/10 border border-red-500/30 text-sm flex items-center justify-between gap-4">
            <span className="text-red-200">
              重置会话将清空当前会话的所有历史轮次与记忆摘要，且不可恢复。确定继续？
            </span>
            <div className="flex items-center gap-2 shrink-0">
              <button
                onClick={() => setConfirmReset(false)}
                disabled={resetting}
                className="px-3 py-1.5 rounded-lg border border-neutral-700 text-neutral-300 text-xs hover:bg-neutral-800 transition-colors"
              >
                取消
              </button>
              <button
                onClick={handleReset}
                disabled={resetting}
                className="px-3 py-1.5 rounded-lg bg-red-500/90 text-white text-xs font-medium hover:bg-red-500 transition-colors disabled:opacity-50"
              >
                {resetting ? '重置中...' : '确认重置'}
              </button>
            </div>
          </div>
        )}

        {pageError && (
          <div className="mx-6 mt-4 px-3 py-2 rounded-lg bg-red-500/10 border border-red-500/20 text-red-300 text-sm">
            {pageError}
          </div>
        )}

        <div className="flex-1 overflow-y-auto px-6 py-6">
          {loadingConversation && displayMessages.length === 0 && (
            <div className="text-center text-sm text-neutral-500 py-12">正在加载会话内容...</div>
          )}

          {!loadingConversation && displayMessages.length === 0 && (
            <div className="h-full grid place-items-center text-center px-6">
              <div className="max-w-xl">
                <div className="w-14 h-14 mx-auto rounded-2xl bg-amber-500/10 grid place-items-center mb-4">
                  <Sparkle weight="fill" className="w-7 h-7 text-amber-400" />
                </div>
                <h3 className="text-xl font-semibold text-neutral-100">让零散问题更快落成可执行方案</h3>
                <p className="mt-2 text-sm text-neutral-500 leading-relaxed">
                  结合业务问答、文档理解与知识检索，把想法整理成清晰结论和下一步动作
                </p>
                <div className="mt-6 flex flex-wrap justify-center gap-2.5">
                  {PROMPT_CHIPS.map((chip) => (
                    <button
                      key={chip}
                      onClick={() => sendMessage(chip)}
                      className="px-3.5 py-2 rounded-full border border-neutral-800 bg-neutral-900 text-neutral-300 text-xs font-medium hover:border-amber-500/30 hover:text-amber-400 transition-colors"
                    >
                      {chip.length > 14 ? chip.slice(0, 14) + '...' : chip}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          <div className="max-w-3xl mx-auto space-y-5">
            {displayMessages.map((msg) => (
              <MessageBubble
                key={msg.id}
                message={msg}
                isStreaming={isStreaming && msg.id === latestAssistantId}
                showRecommendations={msg.id === latestAssistantId && !isStreaming}
                onRecommend={(text) => sendMessage(text)}
              />
            ))}
            <div ref={messagesEndRef} />
          </div>
        </div>

        <ChatComposer
          input={input}
          onInputChange={setInput}
          chatMode={chatMode}
          onModeChange={handleModeChange}
          documentOptions={documentOptions}
          selectedDocumentId={selectedDocumentId}
          onSelectDocument={setSelectedDocumentId}
          latestTopDocumentName={latestRouteTopDocumentName}
          isStreaming={isStreaming}
          isStopping={isStopping}
          onSend={() => sendMessage()}
          onStop={handleStop}
        />
      </div>
    </div>
  );
}
