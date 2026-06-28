import { Plus, Trash, ChatCircleDots } from '@phosphor-icons/react';
import { cn } from '../../lib/cn';
import { ChatModeLabel } from '../../types/chat';
import type { ConversationSessionListVo } from '../../types/chat';

interface Props {
  sessions: ConversationSessionListVo[];
  currentId: string;
  loading: boolean;
  disabled?: boolean;
  onSelect: (id: string) => void;
  onDelete: (id: string) => void;
  onNew: () => void;
}

function truncate(value: string, max: number): string {
  if (!value) return '';
  return value.length > max ? `${value.slice(0, max)}...` : value;
}

function formatTime(value?: string): string {
  if (!value) return '刚刚';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date);
}

function sessionTitle(session: ConversationSessionListVo): string {
  return truncate(session.title || session.latestTurn?.userPrompt || '新的对话', 22);
}

function sessionPreview(session: ConversationSessionListVo): string {
  return truncate(
    session.latestTurn?.replyContent || session.latestTurn?.userPrompt || '还没有消息内容',
    48,
  );
}

export function SessionSidebar({ sessions, currentId, loading, disabled, onSelect, onDelete, onNew }: Props) {
  return (
    <aside className="flex flex-col w-full h-full bg-neutral-950 border-r border-neutral-800">
      <div className="px-4 py-4 border-b border-neutral-800">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold text-neutral-200">聊天记录</h2>
        </div>
        <button
          onClick={onNew}
          disabled={disabled}
          className="mt-3 w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-400 text-sm font-medium hover:bg-amber-500/15 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <Plus weight="bold" className="w-4 h-4" />
          新对话
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-2 py-3 space-y-1.5">
        {loading && sessions.length === 0 && (
          <div className="px-3 py-8 text-center text-xs text-neutral-600">加载中...</div>
        )}

        {!loading && sessions.length === 0 && (
          <div className="mx-2 px-3 py-6 rounded-lg border border-dashed border-neutral-800 text-center">
            <ChatCircleDots className="w-6 h-6 mx-auto text-neutral-700 mb-2" />
            <p className="text-xs text-neutral-400 font-medium">还没有历史会话</p>
            <p className="text-[11px] text-neutral-600 mt-1">发送第一条消息后会自动出现</p>
          </div>
        )}

        {sessions.map((session) => {
          const active = session.conversationId === currentId;
          const running = session.sessionStatus === 2;
          return (
            <div
              key={session.conversationId}
              className={cn(
                'group flex gap-2 p-2.5 rounded-lg border transition-colors',
                active
                  ? 'border-amber-500/40 bg-amber-500/5'
                  : 'border-neutral-800 bg-neutral-900/40 hover:border-neutral-700',
              )}
            >
              <button
                onClick={() => onSelect(session.conversationId)}
                disabled={disabled}
                className="flex-1 min-w-0 text-left disabled:cursor-not-allowed"
              >
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-neutral-100 truncate flex-1">
                    {sessionTitle(session)}
                  </span>
                  {running && (
                    <span className="shrink-0 px-1.5 py-0.5 rounded-full bg-amber-500/15 text-amber-400 text-[10px] font-mono">
                      运行中
                    </span>
                  )}
                </div>
                <p className="mt-1 text-xs text-neutral-500 line-clamp-2 leading-relaxed">
                  {sessionPreview(session)}
                </p>
                <div className="mt-1.5 flex items-center gap-2 text-[11px] text-neutral-600 font-mono">
                  <span>{formatTime(session.updateTime)}</span>
                  <span>·</span>
                  <span>{ChatModeLabel[session.chatMode] ?? '对话'}</span>
                  <span>·</span>
                  <span>{session.turnCount} 轮</span>
                </div>
              </button>
              <button
                onClick={() => onDelete(session.conversationId)}
                disabled={disabled}
                title="删除会话"
                className="shrink-0 w-7 h-7 grid place-items-center rounded-md text-neutral-600 hover:text-red-400 hover:bg-red-500/10 transition-colors disabled:opacity-30 disabled:cursor-not-allowed opacity-0 group-hover:opacity-100"
              >
                <Trash className="w-3.5 h-3.5" />
              </button>
            </div>
          );
        })}
      </div>
    </aside>
  );
}
