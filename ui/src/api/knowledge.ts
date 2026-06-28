import { apiGet, apiPost } from './client';
import type {
  KnowledgeScopeItemVo,
  KnowledgeTopicItemVo,
  TopicDocumentRelationItemVo,
  DocumentProfileVo,
  KnowledgeRouteTraceItemVo,
  KnowledgeScopeSaveDto,
  KnowledgeScopeDeleteDto,
  KnowledgeTopicSaveDto,
  KnowledgeTopicDeleteDto,
  TopicDocumentRelationSaveDto,
  TopicDocumentRelationRemoveDto,
  KnowledgeRouteTraceQuery,
} from '../types/knowledge';
import type { PageVo } from '../types/chat';

// ==================== Scope ====================

export function listScopes(): Promise<KnowledgeScopeItemVo[]> {
  return apiGet<KnowledgeScopeItemVo[]>('/document/knowledge/scope/list');
}

export function saveScope(dto: KnowledgeScopeSaveDto): Promise<KnowledgeScopeItemVo> {
  return apiPost<KnowledgeScopeItemVo>('/document/knowledge/scope/save', dto);
}

export function deleteScope(dto: KnowledgeScopeDeleteDto): Promise<void> {
  return apiPost<void>('/document/knowledge/scope/delete', dto);
}

// ==================== Topic ====================

export function listTopics(scopeCode?: string): Promise<KnowledgeTopicItemVo[]> {
  const params: Record<string, string> = {};
  if (scopeCode) params.scopeCode = scopeCode;
  return apiGet<KnowledgeTopicItemVo[]>('/document/knowledge/topic/list', params);
}

export function saveTopic(dto: KnowledgeTopicSaveDto): Promise<KnowledgeTopicItemVo> {
  return apiPost<KnowledgeTopicItemVo>('/document/knowledge/topic/save', dto);
}

export function deleteTopic(dto: KnowledgeTopicDeleteDto): Promise<void> {
  return apiPost<void>('/document/knowledge/topic/delete', dto);
}

// ==================== Topic-Document Relation ====================

export function listRelations(topicCode: string): Promise<TopicDocumentRelationItemVo[]> {
  return apiGet<TopicDocumentRelationItemVo[]>('/document/knowledge/topic/document/list', {
    topicCode,
  });
}

export function saveRelation(
  dto: TopicDocumentRelationSaveDto,
): Promise<TopicDocumentRelationItemVo> {
  return apiPost<TopicDocumentRelationItemVo>('/document/knowledge/topic/document/save', dto);
}

export function removeRelation(dto: TopicDocumentRelationRemoveDto): Promise<void> {
  return apiPost<void>('/document/knowledge/topic/document/remove', dto);
}

// ==================== Document Profile ====================

export function getProfile(documentId: number): Promise<DocumentProfileVo> {
  return apiGet<DocumentProfileVo>('/document/knowledge/document/profile/detail', {
    documentId: String(documentId),
  });
}

export function regenerateProfile(documentId: number): Promise<DocumentProfileVo> {
  return apiPost<DocumentProfileVo>(
    `/document/knowledge/document/profile/regenerate?documentId=${documentId}`,
  );
}

export function batchRegenerateProfiles(documentIds: number[]): Promise<DocumentProfileVo[]> {
  return apiPost<DocumentProfileVo[]>(
    '/document/knowledge/document/profile/batch-regenerate',
    documentIds,
  );
}

// ==================== Route Trace ====================

export function pageQueryRouteTrace(
  query: KnowledgeRouteTraceQuery = {},
): Promise<PageVo<KnowledgeRouteTraceItemVo>> {
  const params: Record<string, string> = {
    pageNo: String(query.pageNo ?? 1),
    pageSize: String(query.pageSize ?? 20),
  };
  if (query.conversationId) params.conversationId = query.conversationId;
  if (query.mode) params.mode = query.mode;
  if (query.routeStatus != null) params.routeStatus = String(query.routeStatus);
  return apiGet<PageVo<KnowledgeRouteTraceItemVo>>('/document/knowledge/route/trace/page', params);
}
