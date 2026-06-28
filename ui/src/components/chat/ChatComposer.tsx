import { useRef, useEffect } from 'react';
import { PaperPlaneTilt, Stop } from '@phosphor-icons/react';
import { cn } from '../../lib/cn';
import { ChatMode, ChatModeLabel } from '../../types/chat';
import type { KnowledgeDocumentOptionVo } from '../../types/chat';

interface Props {
  input: string;
  onInputChange: (v: string) => void;
  chatMode: number;
  onModeChange: (mode: number) => void;
  documentOptions: KnowledgeDocumentOptionVo[];
  selectedDocumentId: string;
  onSelectDocument: (id: string) => void;
  isStreaming: boolean;
  isStopping: boolean;
  onSend: () => void;
  onStop: () => void;
}

const MODES = [
  { value: ChatMode.OPEN_CHAT, label: ChatModeLabel[ChatMode.OPEN_CHAT] },
  { value: ChatMode.DOCUMENT, label: ChatModeLabel[ChatMode.DOCUMENT] },
  { value: ChatMode.AUTO_DOCUMENT, label: ChatModeLabel[ChatMode.AUTO_DOCUMENT] },
];

export function ChatComposer({
  input,
  onInputChange,
  chatMode,
  onModeChange,
  documentOptions,
  selectedDocumentId,
  onSelectDocument,
  isStreaming,
  isStopping,
  onSend,
  onStop,
}: Props) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const resize = () => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 220)}px`;
  };

  useEffect(() => {
    resize();
  }, [input]);

  const isDocumentMode = chatMode === ChatMode.DOCUMENT;
  const isAutoMode = chatMode === ChatMode.AUTO_DOCUMENT;
  const canSend = input.trim().length > 0 && (!isDocumentMode || Boolean(selectedDocumentId));

  const placeholder = isAutoMode
    ? '请输入你的问题，系统会自动选择最相关的知识文档...'
    : isDocumentMode
      ? '请输入关于当前文档的问题...'
      : '请输入你的问题，例如：帮我分析一下这个方案该怎么拆分模块。';

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (canSend && !isStreaming) onSend();
    }
  };

  return (
    <div className="px-4 py-3 border-t border-neutral-800 bg-neutral-950">
      {/* 回答模式 */}
      <div className="flex items-center gap-2 mb-2.5 flex-wrap">
        <span className="text-xs text-neutral-500">回答模式</span>
        <div className="inline-flex gap-1 p-1 rounded-full bg-neutral-900 border border-neutral-800">
          {MODES.map((m) => (
            <button
              key={m.value}
              onClick={() => onModeChange(m.value)}
              disabled={isStreaming}
              className={cn(
                'px-3 py-1 rounded-full text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed',
                chatMode === m.value
                  ? 'bg-neutral-700 text-amber-400'
                  : 'text-neutral-400 hover:text-neutral-200',
              )}
            >
              {m.label}
            </button>
          ))}
        </div>
      </div>

      {/* 范围提示 */}
      <div className="flex items-center gap-2 mb-2.5 flex-wrap text-xs">
        {isDocumentMode && (
          <>
            <span className="text-neutral-500">提问文档</span>
            <select
              value={selectedDocumentId}
              onChange={(e) => onSelectDocument(e.target.value)}
              disabled={isStreaming}
              className="min-w-[200px] px-2.5 py-1 rounded-lg bg-neutral-900 border border-neutral-800 text-neutral-200 text-xs focus:border-amber-500/50 outline-none"
            >
              <option value="">请选择一个文档</option>
              {documentOptions.map((doc) => (
                <option key={doc.documentId} value={String(doc.documentId)}>
                  {doc.documentName}
                </option>
              ))}
            </select>
            {!selectedDocumentId && (
              <span className="px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400 text-[11px]">
                请先选择一个文档再发送问题
              </span>
            )}
          </>
        )}
        {isAutoMode && (
          <>
            <span className="text-neutral-500">知识库使用</span>
            <span className="px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400">
              系统会先自动预选 3-5 份候选文档
            </span>
            <span className="px-2 py-0.5 rounded-full bg-neutral-800 text-neutral-400">
              候选选择只做预选，后续仍走稳定检索链路
            </span>
          </>
        )}
        {chatMode === ChatMode.OPEN_CHAT && (
          <>
            <span className="text-neutral-500">知识库使用</span>
            <span className="px-2 py-0.5 rounded-full bg-neutral-800 text-neutral-400">
              当前不会使用业务知识库文档
            </span>
          </>
        )}
      </div>

      {/* 输入框 */}
      <textarea
        ref={textareaRef}
        value={input}
        onChange={(e) => onInputChange(e.target.value)}
        onKeyDown={handleKeyDown}
        rows={1}
        placeholder={placeholder}
        disabled={isStreaming}
        className="w-full min-h-[52px] max-h-[220px] resize-none px-3 py-2.5 rounded-xl bg-neutral-900 border border-neutral-800 text-neutral-100 text-sm placeholder:text-neutral-600 focus:border-amber-500/50 outline-none disabled:opacity-60"
      />

      <div className="mt-2.5 flex items-center justify-between">
        <span className="text-[11px] text-neutral-600 font-mono">Enter 发送 · Shift+Enter 换行</span>
        <div className="flex items-center gap-2">
          {isStreaming && (
            <button
              onClick={onStop}
              disabled={isStopping}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-neutral-700 bg-neutral-900 text-neutral-300 text-xs font-medium hover:bg-neutral-800 transition-colors disabled:opacity-50"
            >
              <Stop weight="fill" className="w-3.5 h-3.5" />
              {isStopping ? '停止中...' : '停止生成'}
            </button>
          )}
          <button
            onClick={onSend}
            disabled={isStreaming || !canSend}
            className="flex items-center gap-1.5 px-4 py-1.5 rounded-lg bg-amber-500 text-black text-xs font-semibold hover:bg-amber-400 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          >
            <PaperPlaneTilt weight="fill" className="w-3.5 h-3.5" />
            发送
          </button>
        </div>
      </div>
    </div>
  );
}
