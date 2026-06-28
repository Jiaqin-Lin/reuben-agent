export interface KnowledgeScopeItemVo {
  id: number;
  scopeCode: string;
  scopeName: string;
  parentScopeCode?: string;
  description?: string;
  aliases?: string;
  examples?: string;
  sortOrder?: number;
}

export interface KnowledgeTopicItemVo {
  id: number;
  topicCode: string;
  topicName: string;
  scopeCode: string;
  description?: string;
  aliases?: string;
  examples?: string;
  answerShape?: string;
  executionPreference?: string;
  sortOrder?: number;
}

export interface TopicDocumentRelationItemVo {
  id: number;
  topicCode: string;
  documentId: number;
  documentName?: string;
  scopeName?: string;
  relationScore?: number;
  relationSource?: string;
  reason?: string;
}

export interface DocumentProfileVo {
  id: number;
  documentId: number;
  documentName?: string;
  profileVersion?: number;
  documentSummary?: string;
  documentType?: string;
  coreTopics?: string;
  exampleQuestions?: string;
  graphFriendly?: number;
  supportsGraphOutline?: number;
  supportsItemLookup?: number;
  supportsGraphAssist?: number;
  profileSource?: string;
  profileStatus?: number;
  errorMsg?: string;
  createTime?: string;
  editTime?: string;
}

/** 画像状态：1=待生成 2=生成中 3=成功 4=失败 */
export const ProfileStatus: Record<number, string> = {
  1: '待生成',
  2: '生成中',
  3: '成功',
  4: '失败',
};

/** 路由状态：1=SUCCESS 2=LOW_CONFIDENCE 3=FAILED */
export const RouteStatus: Record<number, { label: string; tone: 'success' | 'warning' | 'danger' }> = {
  1: { label: '成功', tone: 'success' },
  2: { label: '低置信', tone: 'warning' },
  3: { label: '失败', tone: 'danger' },
};

export interface KnowledgeRouteTraceItemVo {
  id: number;
  conversationId: string;
  turnId: number;
  question?: string;
  rewriteQuestion?: string;
  mode?: string;
  topScopesJson?: string;
  topTopicsJson?: string;
  topDocumentsJson?: string;
  selectedDocumentId?: number;
  hitSelectedDocument?: number;
  confidence?: number;
  routeStatus?: number;
  errorMsg?: string;
  createTime?: string;
}

/** 路由候选条目（topScopesJson/topTopicsJson/topDocumentsJson 解析后元素）。 */
export interface RouteCandidate {
  documentId?: number;
  documentName?: string;
  scopeCode?: string;
  scopeName?: string;
  topicCode?: string;
  topicName?: string;
  score?: number;
  reason?: string;
  [key: string]: unknown;
}

export interface KnowledgeScopeSaveDto {
  id?: number;
  scopeCode: string;
  scopeName: string;
  parentScopeCode?: string;
  description?: string;
  aliases?: string;
  examples?: string;
  sortOrder?: number;
}

export interface KnowledgeScopeDeleteDto {
  scopeCode: string;
}

export interface KnowledgeTopicSaveDto {
  id?: number;
  topicCode: string;
  topicName: string;
  scopeCode: string;
  description?: string;
  aliases?: string;
  examples?: string;
  answerShape?: string;
  executionPreference?: string;
  sortOrder?: number;
}

export interface KnowledgeTopicDeleteDto {
  topicCode: string;
}

export interface TopicDocumentRelationSaveDto {
  topicCode: string;
  documentId: number;
  relationScore?: number;
  relationSource?: string;
  reason?: string;
}

export interface TopicDocumentRelationRemoveDto {
  topicCode: string;
  documentId: number;
}

export interface KnowledgeRouteTraceQuery {
  conversationId?: string;
  mode?: string;
  routeStatus?: number;
  pageNo?: number;
  pageSize?: number;
}
