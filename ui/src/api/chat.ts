import { apiGet, apiPost, apiDelete, openSseStream, type SseHandlers } from './client';
import type {
  ChatStreamDto,
  ChatStreamEvent,
  ChatSessionCreateDto,
  ChatSessionListQuery,
  ChatRenameDto,
  ConversationView,
  ConversationSessionListVo,
  ChatStopVo,
  ChatResetVo,
  ChatTurnDetailView,
  RetrievalResultView,
  ChannelExecutionView,
  StageBenchmarkView,
  KnowledgeDocumentOptionVo,
  PageVo,
} from '../types/chat';

export type { PageVo };
/** 流式问答（SSE）。返回 controller + done promise。 */
export function openChatStream(
  payload: ChatStreamDto,
  handlers: SseHandlers,
): { controller: AbortController; done: Promise<void> } {
  return openSseStream('/chat/stream', payload, {
    onEvent: (raw) => handlers.onEvent?.(raw),
  });
}
export function stopChat(conversationId: string): Promise<ChatStopVo> {
  return apiPost<ChatStopVo>('/chat/session/stop', { conversationId });
}

export function createSession(dto: ChatSessionCreateDto): Promise<ConversationView> {
  return apiPost<ConversationView>('/chat/session', dto);
}

export function listSessions(
  query: ChatSessionListQuery = {},
): Promise<PageVo<ConversationSessionListVo>> {
  const params: Record<string, string> = {
    pageNo: String(query.pageNo ?? 1),
    pageSize: String(query.pageSize ?? 50),
  };
  if (query.keyword) params.keyword = query.keyword;
  if (query.chatMode != null) params.chatMode = String(query.chatMode);
  if (query.turnStatus != null) params.turnStatus = String(query.turnStatus);
  return apiGet<PageVo<ConversationSessionListVo>>('/chat/session/list', params);
}

export function getSession(conversationId: string): Promise<ConversationView> {
  return apiGet<ConversationView>('/chat/session/detail', { conversationId });
}

export function renameSession(dto: ChatRenameDto): Promise<string> {
  return apiPost<string>('/chat/session/rename', dto);
}

export function resetSession(conversationId: string): Promise<ChatResetVo> {
  return apiPost<ChatResetVo>(`/chat/session/reset?conversationId=${encodeURIComponent(conversationId)}`);
}

export function deleteSession(conversationId: string): Promise<void> {
  return apiDelete<void>(`/chat/session/${encodeURIComponent(conversationId)}`);
}

export function rebuildSummary(conversationId: string): Promise<void> {
  return apiPost<void>(`/chat/session/summary/rebuild?conversationId=${encodeURIComponent(conversationId)}`);
}

export function getTurnDetail(
  conversationId: string,
  turnId: number,
): Promise<ChatTurnDetailView> {
  return apiGet<ChatTurnDetailView>('/chat/exchange/detail', {
    conversationId,
    turnId: String(turnId),
  });
}

export function getRetrievalResults(
  conversationId: string,
  turnId: number,
): Promise<RetrievalResultView[]> {
  return apiGet<RetrievalResultView[]>('/chat/exchange/retrieval/results', {
    conversationId,
    turnId: String(turnId),
  });
}

export function getChannelExecutions(
  conversationId: string,
  turnId: number,
): Promise<ChannelExecutionView[]> {
  return apiGet<ChannelExecutionView[]>('/chat/exchange/channel/executions', {
    conversationId,
    turnId: String(turnId),
  });
}

export function getStageBenchmarks(executionMode?: number): Promise<StageBenchmarkView[]> {
  const params: Record<string, string> = {};
  if (executionMode != null) params.executionMode = String(executionMode);
  return apiGet<StageBenchmarkView[]>('/chat/stage/benchmarks', params);
}

export function listDocumentOptions(): Promise<KnowledgeDocumentOptionVo[]> {
  return apiGet<KnowledgeDocumentOptionVo[]>('/chat/document/options');
}
