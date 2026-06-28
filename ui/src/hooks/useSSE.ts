import { useCallback, useRef, useState } from 'react';
import { openChatStream, stopChat } from '../api/chat';
import type { ChatStreamDto, ChatStreamEvent } from '../types/chat';

interface UseSseOptions {
  onEvent: (event: ChatStreamEvent) => void;
  onError?: (message: string) => void;
  onComplete?: () => void;
}

export function useSSE() {
  const [isStreaming, setIsStreaming] = useState(false);
  const [isStopping, setIsStopping] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);

  const start = useCallback(
    async (payload: ChatStreamDto, handlers: UseSseOptions) => {
      setIsStreaming(true);
      setIsStopping(false);

      const { controller, done } = openChatStream(payload, {
        onEvent: (raw) => handlers.onEvent(raw as ChatStreamEvent),
      });
      controllerRef.current = controller;

      try {
        await done;
      } catch (err) {
        const name = (err as Error)?.name;
        if (name !== 'AbortError') {
          const msg = err instanceof Error ? err.message : '流式对话失败';
          handlers.onError?.(msg);
        }
      } finally {
        controllerRef.current = null;
        setIsStreaming(false);
        setIsStopping(false);
        handlers.onComplete?.();
      }
    },
    [],
  );

  const stop = useCallback(async (conversationId: string) => {
    if (!controllerRef.current) return;
    setIsStopping(true);
    // 阶段：通知后端停止后立即中断本地流
    try {
      await stopChat(conversationId);
    } catch {
      // 停止接口失败也允许本地中断
    }
    controllerRef.current.abort();
  }, []);

  const abort = useCallback(() => {
    controllerRef.current?.abort();
  }, []);

  return { isStreaming, isStopping, start, stop, abort };
}
