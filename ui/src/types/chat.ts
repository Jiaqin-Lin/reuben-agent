/** 对话模式：1=DOCUMENT 2=OPEN_CHAT 3=AUTO_DOCUMENT */
export const ChatMode = {
  DOCUMENT: 1,
  OPEN_CHAT: 2,
  AUTO_DOCUMENT: 3,
} as const;

export type ChatModeValue = (typeof ChatMode)[keyof typeof ChatMode];

export const ChatModeLabel: Record<number, string> = {
  1: '当前文档问答',
  2: '开放式提问',
  3: '自动知识问答',
};

/** 轮次状态：1=执行中 2=完成 3=失败 4=停止 */
export const TurnStatus: Record<number, string> = {
  1: '执行中',
  2: '完成',
  3: '失败',
  4: '停止',
};

/** 会话状态：1=空闲 2=执行中 */
export const SessionStatus: Record<number, string> = {
  1: '空闲',
  2: '执行中',
};

/** 流式问答入参 */
export interface ChatStreamDto {
  question: string;
  conversationId?: string;
  chatMode: number;
  selectedDocumentId?: number | null;
  selectedDocumentName?: string | null;
}

/** SSE 事件协议载荷 */
export interface ChatStreamEvent {
  type: 'text' | 'thinking' | 'status' | 'error' | 'reference' | 'recommend' | 'done';
  content: unknown;
  conversationId?: string;
  turnId?: number;
  timestamp?: number;
  count?: number;
}

/** 知识文档下拉项 */
export interface KnowledgeDocumentOptionVo {
  documentId: number;
  documentName: string;
  fileType: number;
  indexStatus: number;
  knowledgeScopeName?: string;
  createTime?: string;
}

/** 对话轮次 */
export interface ChatTurnVo {
  turnId: number;
  conversationId: string;
  userPrompt: string;
  replyContent: string;
  sourceSnapshotList?: string;
  followupSuggestionList?: string;
  toolTraceList?: string;
  debugTraceJson?: string;
  finishNote?: string;
  turnStatus: number;
  executionMode?: number;
  firstTokenLatencyMs?: number;
  totalLatencyMs?: number;
  createTime?: string;
}

/** 会话长期摘要快照（观测页展示记忆压缩状态）。 */
export interface ConversationMemorySummaryVo {
  compressionApplied?: boolean;
  coveredExchangeCount?: number;
  summaryVersion?: number;
  compressionCount?: number;
  summaryText?: string;
}

/** 会话详情 */
export interface ConversationView {
  conversationId: string;
  chatMode: number;
  sessionStatus: number;
  title: string;
  selectedDocumentId?: number;
  selectedDocumentName?: string;
  turnCount: number;
  latestTurn?: ChatTurnVo;
  recentTurns?: ChatTurnVo[];
  latestUserMessage?: string;
  latestAssistantMessage?: string;
  messageCount?: number;
  checkpointCount?: number;
  memorySummary?: ConversationMemorySummaryVo | null;
  createTime?: string;
  updateTime?: string;
}

/** 会话列表项 */
export interface ConversationSessionListVo {
  conversationId: string;
  chatMode: number;
  sessionStatus: number;
  title: string;
  selectedDocumentId?: number;
  selectedDocumentName?: string;
  turnCount: number;
  latestTurn?: ChatTurnVo;
  createTime?: string;
  updateTime?: string;
}

export interface ChatStopVo {
  conversationId: string;
  stopped: boolean;
  message: string;
}

export interface ChatResetVo {
  conversationId: string;
  removedTurnCount: number;
  removedCheckpointCount: number;
  summaryCleared: boolean;
  message: string;
}

/** stage 状态 1=RUNNING 2=COMPLETED 3=FAILED 4=SKIPPED */
export const StageState: Record<number, string> = {
  1: 'RUNNING',
  2: 'COMPLETED',
  3: 'FAILED',
  4: 'SKIPPED',
};

export interface ChatTraceStageView {
  id: number;
  conversationId: string;
  turnId: number;
  traceId?: string;
  stageCode: number;
  stageName: string;
  stageOrder: number;
  executionMode?: number;
  stageState: number;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  summaryText?: string;
  errorMessage?: string;
}

/** 模型用量追踪（debugTraceJson.modelUsageTraces 元素）。 */
export interface ChatModelUsageTrace {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  estimatedCost?: number;
  [key: string]: unknown;
}

/** debugTraceJson 解析结果（reuben-agent 后端当前仅持久化 modelUsageTraces，其余字段为容错空态）。 */
export interface ChatDebugTrace {
  rewriteQuestion?: string;
  agentQuestion?: string;
  modelUsageTraces?: ChatModelUsageTrace[];
  ragSystemPrompt?: string;
  ragUserPrompt?: string;
  historySummary?: string;
  retrievalNotes?: string[];
  [key: string]: unknown;
}

export interface ChatTurnDetailView {
  turn: ChatTurnVo;
  stageTraces: ChatTraceStageView[];
}

export interface RetrievalResultView {
  id: number;
  conversationId: string;
  turnId: number;
  subQuestionIndex?: number;
  channelType: string;
  vectorRank?: number;
  vectorScore?: number;
  keywordRank?: number;
  keywordScore?: number;
  originalScore?: number;
  rrfScore?: number;
  rerankScore?: number;
  finalScore?: number;
  channelRank?: number;
  finalRank?: number;
  gatePassed?: number;
  isSelected?: number;
  documentId: number;
  documentName?: string;
  chunkId: number;
  sectionPath?: string;
  chunkTextPreview?: string;
  createTime?: string;
}

export interface ChannelExecutionView {
  id: number;
  conversationId: string;
  turnId: number;
  subQuestionIndex?: number;
  channelType: string;
  executionState?: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  recalledCount?: number;
  acceptedCount?: number;
  finalSelectedCount?: number;
  maxScore?: number;
  minScore?: number;
  avgScore?: number;
  errorMessage?: string;
}

export interface StageBenchmarkView {
  stageCode: number;
  executionMode?: number;
  p50?: number;
  p90?: number;
  p99?: number;
  avg?: number;
  max?: number;
  min?: number;
  sampleCount?: number;
  recentDurations?: string;
}

export interface ChatRenameDto {
  conversationId: string;
  title?: string;
}

export interface ChatSessionCreateDto {
  chatMode: number;
  selectedDocumentId?: number;
  selectedDocumentName?: string;
  title?: string;
}

export interface ChatSessionListQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
  chatMode?: number;
  turnStatus?: number;
}

export interface PageVo<T> {
  records: T[];
  total: number;
  pageNo: number;
  pageSize: number;
  totalPages?: number;
}