import type { ChatTurnVo } from './chat';
import type { ChatRouteExplain } from '../lib/knowledgeRoute';

/** 展示态消息 —— 统一历史快照与流式增量。 */
export interface DisplayMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  turnId?: number;
  thinkingSteps: string[];
  references: unknown[];
  recommendations: string[];
  status: string;
  statusText: string;
  errorMessage: string;
  routeExplain: ChatRouteExplain | null;
  firstResponseTimeMs?: number;
  totalResponseTimeMs?: number;
  createdAt?: string;
  updatedAt?: string;
}

import { buildChatRouteExplain } from '../lib/knowledgeRoute';

/** 把后端轮次展开为 user + assistant 两条消息。 */
export function mapTurnsToMessages(
  turns: ChatTurnVo[] = [],
  routeLookup: Record<string, ChatRouteExplain | null> = {},
): DisplayMessage[] {
  return turns.flatMap((turn) => {
    const createdAt = turn.createTime;
    let recommendations: string[] = [];
    if (turn.followupSuggestionList) {
      try {
        const parsed = JSON.parse(turn.followupSuggestionList);
        if (Array.isArray(parsed)) recommendations = parsed.filter((x): x is string => typeof x === 'string');
      } catch {
        // ignore
      }
    }

    const routeExplain = routeLookup[String(turn.turnId ?? '')] ?? null;
    const finishNote = turn.finishNote || '';

    const userMessage: DisplayMessage = {
      id: `turn-${turn.turnId}-user`,
      role: 'user',
      content: turn.userPrompt || '',
      turnId: turn.turnId,
      thinkingSteps: [],
      references: [],
      recommendations: [],
      status: '',
      statusText: '',
      errorMessage: '',
      routeExplain: null,
      createdAt,
    };

    const assistantMessage: DisplayMessage = {
      id: `turn-${turn.turnId}-assistant`,
      role: 'assistant',
      content: turn.replyContent || '',
      turnId: turn.turnId,
      thinkingSteps: [],
      references: [],
      recommendations,
      status: '',
      statusText: finishNote,
      errorMessage: turn.turnStatus === 3 ? '本轮执行失败' : '',
      firstResponseTimeMs: turn.firstTokenLatencyMs ?? undefined,
      totalResponseTimeMs: turn.totalLatencyMs ?? undefined,
      routeExplain,
      createdAt,
      updatedAt: createdAt,
    };

    return [userMessage, assistantMessage];
  });
}

export { buildChatRouteExplain };
