import { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { User, Sparkle, Copy, Check, CaretDown } from '@phosphor-icons/react';
import { Markdown } from '../shared/Markdown';
import { cn } from '../../lib/cn';
import type { DisplayMessage } from '../../types/displayMessage';
import type { ChatRouteExplain } from '../../lib/knowledgeRoute';

interface Props {
  message: DisplayMessage;
  isStreaming?: boolean;
  showRecommendations?: boolean;
  onRecommend?: (text: string) => void;
}

function formatTime(value?: string): string {
  if (!value) return '刚刚';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date);
}

const TONE_BORDER: Record<string, string> = {
  success: 'border-emerald-500/30',
  warning: 'border-amber-500/30',
  danger: 'border-red-500/30',
};

const TONE_BADGE: Record<string, string> = {
  success: 'bg-emerald-500/15 text-emerald-400',
  warning: 'bg-amber-500/15 text-amber-400',
  danger: 'bg-red-500/15 text-red-400',
};

function RouteExplainCard({ explain }: { explain: ChatRouteExplain }) {
  const [open, setOpen] = useState(false);
  return (
    <section
      className={cn(
        'mt-4 p-4 rounded-xl border bg-neutral-900/60',
        TONE_BORDER[explain.statusTone],
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[10px] font-mono uppercase tracking-wider text-neutral-500">
            {explain.modeLabel}
          </p>
          <h4 className="mt-1 text-sm font-semibold text-neutral-100">
            {explain.confidenceBand.label} · 置信度 {explain.confidenceText}
          </h4>
        </div>
        <span className={cn('shrink-0 px-2.5 py-1 rounded-full text-[11px] font-mono font-semibold', TONE_BADGE[explain.statusTone])}>
          {explain.statusLabel}
        </span>
      </div>

      <p className="mt-3 text-sm text-neutral-300 leading-relaxed">{explain.summary}</p>

      {explain.notes.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {explain.notes.map((note, i) => (
            <span key={i} className="px-2.5 py-1 rounded-full bg-neutral-800 text-[11px] text-neutral-300">
              {note}
            </span>
          ))}
        </div>
      )}

      {explain.topDocuments.length > 0 && (
        <div className="mt-4 grid grid-cols-[repeat(auto-fit,minmax(160px,1fr))] gap-2">
          {explain.topDocuments.map((doc, i) => (
            <article
              key={doc.documentId || i}
              className={cn(
                'p-3 rounded-lg border bg-neutral-950/50 grid gap-1',
                i === 0 ? 'border-amber-500/30' : 'border-neutral-800',
              )}
            >
              <p className="text-[10px] font-mono uppercase tracking-wider text-neutral-500">候选 {i + 1}</p>
              <strong className="text-xs text-neutral-100 truncate">
                {doc.documentName || doc.documentId}
              </strong>
              <span className="text-[11px] text-amber-400 font-mono">匹配分 {doc.scoreText}</span>
              {doc.reason && <small className="text-[11px] text-neutral-500 line-clamp-2">{doc.reason}</small>}
            </article>
          ))}
        </div>
      )}

      {(explain.scopePreview.length > 0 || explain.topicPreview.length > 0) && (
        <div className="mt-4 pt-3 border-t border-neutral-800">
          <button
            onClick={() => setOpen((v) => !v)}
            className="flex items-center gap-1 text-xs font-medium text-amber-400 hover:opacity-80"
          >
            <CaretDown weight="bold" className={cn('w-3 h-3 transition-transform', open && 'rotate-180')} />
            查看范围与主题候选
          </button>
          <AnimatePresence>
            {open && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                transition={{ duration: 0.18 }}
                className="overflow-hidden"
              >
                <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {explain.scopePreview.length > 0 && (
                    <div className="grid gap-1.5">
                      <p className="text-[10px] font-mono uppercase tracking-wider text-neutral-500">范围候选</p>
                      <div className="flex flex-wrap gap-1.5">
                        {explain.scopePreview.map((item, i) => (
                          <span key={item.scopeCode || i} className="px-2 py-1 rounded-full bg-neutral-800/80 border border-neutral-700 text-[11px] text-neutral-300">
                            {item.scopeName || item.scopeCode} · {item.scoreText}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                  {explain.topicPreview.length > 0 && (
                    <div className="grid gap-1.5">
                      <p className="text-[10px] font-mono uppercase tracking-wider text-neutral-500">主题候选</p>
                      <div className="flex flex-wrap gap-1.5">
                        {explain.topicPreview.map((item, i) => (
                          <span key={item.topicCode || i} className="px-2 py-1 rounded-full bg-neutral-800/80 border border-neutral-700 text-[11px] text-neutral-300">
                            {item.topicName || item.topicCode} · {item.scoreText}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      )}
    </section>
  );
}

export function MessageBubble({ message, isStreaming, showRecommendations, onRecommend }: Props) {
  const [copied, setCopied] = useState(false);
  const isUser = message.role === 'user';

  const copy = async () => {
    const text = message.content || [message.statusText, message.errorMessage].filter(Boolean).join('\n');
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      // ignore
    }
  };

  const hasContent = Boolean(message.content);
  const showEmptyHint = !isUser && !isStreaming && !message.content && (Boolean(message.statusText) || Boolean(message.errorMessage));

  return (
    <article className={cn('flex gap-3.5', isUser && 'flex-row-reverse')}>
      <div
        className={cn(
          'w-10 h-10 shrink-0 rounded-xl grid place-items-center border',
          isUser ? 'bg-neutral-800 border-neutral-700 text-neutral-300' : 'bg-amber-500/10 border-amber-500/30 text-amber-400',
        )}
      >
        {isUser ? <User weight="regular" className="w-5 h-5" /> : <Sparkle weight="fill" className="w-5 h-5" />}
      </div>

      <div className="min-w-0 flex-1">
        <div className={cn('flex items-center justify-between gap-3 mb-2', isUser && 'flex-row-reverse')}>
          <div className={cn(isUser && 'text-right')}>
            <p className="text-sm font-semibold text-neutral-100">{isUser ? '你' : '智能助手'}</p>
            <p className="text-[11px] text-neutral-500 font-mono">{formatTime(message.updatedAt || message.createdAt)}</p>
          </div>
          <button
            onClick={copy}
            title={copied ? '已复制' : '复制内容'}
            className="w-8 h-8 grid place-items-center rounded-lg border border-neutral-800 bg-neutral-900 text-neutral-400 hover:text-neutral-200 hover:border-neutral-700 transition-colors"
          >
            {copied ? <Check weight="bold" className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
          </button>
        </div>

        {isUser ? (
          <div className="inline-block max-w-[min(760px,100%)] px-4 py-3 rounded-2xl bg-neutral-800/60 border border-neutral-700 text-neutral-100 whitespace-pre-wrap break-words leading-relaxed text-sm">
            {message.content}
          </div>
        ) : (
          <div className="px-4 py-3 rounded-2xl bg-neutral-900/60 border border-neutral-800">
            {message.statusText && (
              <p className="mb-2 px-3 py-2 rounded-lg bg-amber-500/10 border border-amber-500/20 text-amber-300 text-sm whitespace-pre-wrap">
                {message.statusText}
              </p>
            )}
            {message.errorMessage && (
              <p className="mb-2 px-3 py-2 rounded-lg bg-red-500/10 border border-red-500/20 text-red-300 text-sm whitespace-pre-wrap">
                {message.errorMessage}
              </p>
            )}
            {hasContent ? (
              <Markdown content={message.content} />
            ) : showEmptyHint ? (
              <p className="px-3 py-2 rounded-lg border border-dashed border-neutral-700 bg-neutral-800/40 text-neutral-500 text-sm">
                本次回答没有生成可展示的正文内容。
              </p>
            ) : null}

            {isStreaming && (
              <motion.span
                animate={{ opacity: [0.3, 1, 0.3] }}
                transition={{ duration: 1, repeat: Infinity }}
                className="inline-block w-1.5 h-4 mt-3 rounded-full bg-amber-400"
              />
            )}

            {message.routeExplain && <RouteExplainCard explain={message.routeExplain} />}

            {showRecommendations && message.recommendations.length > 0 && (
              <div className="mt-4 pt-3 border-t border-neutral-800">
                <p className="text-[10px] font-mono uppercase tracking-wider text-neutral-500 mb-2">推荐追问</p>
                <div className="flex flex-wrap gap-2">
                  {message.recommendations.map((item, i) => (
                    <button
                      key={i}
                      onClick={() => onRecommend?.(item)}
                      className="px-3 py-1.5 rounded-full border border-amber-500/20 bg-amber-500/5 text-amber-300 text-xs font-medium hover:bg-amber-500/10 hover:border-amber-500/30 transition-colors"
                    >
                      {item}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </article>
  );
}
