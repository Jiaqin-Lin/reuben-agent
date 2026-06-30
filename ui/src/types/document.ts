export interface DocumentUploadDto {
  documentName?: string;
  operatorId?: string;
  knowledgeScopeCode?: string;
  knowledgeScopeName?: string;
  businessCategory?: string;
  documentTags?: string;
}

export interface DocumentUploadVo {
  documentId: string;
  taskId: string;
  documentName: string;
  parseStatus: number;
  strategyStatus: number;
  indexStatus: number;
}

export interface DocumentDetailVo {
  documentId: string;
  documentName: string;
  originalFileName: string;
  fileType: number;
  fileSize: number;
  parseStatus: number;
  strategyStatus: number;
  indexStatus: number;
  charCount?: number;
  tokenCount?: number;
  structureLevel: number;
  qualityLevel: number;
  parseErrorMsg?: string;
  knowledgeScopeCode?: string;
  knowledgeScopeName?: string;
  businessCategory?: string;
  documentTags?: string;
  currentPlanId?: string;
  latestIndexTaskId?: string;
  latestTaskId?: string;
  latestTaskType?: number;
  latestTaskStatus?: number;
  createTime: string;
  updateTime?: string;
  parentBlocks?: DocumentParentBlock[];
  chunks?: DocumentChunk[];
}

export interface DocumentStrategyStepVo {
  stepId: string;
  stepNo: number;
  pipelineType: string;
  strategyType: number;
  strategyRole?: number;
  sourceType?: number;
  recommendReason?: string;
}

export interface DocumentStrategyPlanVo {
  planId: string;
  documentId: string;
  planVersion: number;
  planSource: number;
  planStatus: number;
  strategyCount: number;
  strategySnapshot: string;
  recommendReason?: string;
  adjustNote?: string;
  steps?: DocumentStrategyStepVo[];
}

export interface StrategyStepInput {
  stepNo: number;
  strategyType: number;
}

export interface DocumentStrategyConfirmDto {
  documentId: string;
  planId: string;
  basePlanId?: string;
  adjustNote?: string;
  parentSteps?: StrategyStepInput[];
  childSteps?: StrategyStepInput[];
  confirmUserId?: string;
}

export interface DocumentStrategyConfirmVo {
  taskId: string;
  planId: string;
  documentId: string;
  planStatus: number;
}

export interface DocumentIndexBuildDto {
  documentId: string;
  planId: string;
  operatorId?: string;
}

export interface DocumentIndexBuildVo {
  documentId: string;
  taskId: string;
  taskType: number;
  taskStatus: number;
  indexStatus: number;
}

export interface DocumentParentBlock {
  id: string;
  documentId: string;
  taskId: string;
  planId: string;
  parentNo: number;
  sourceType: number;
  sectionPath: string;
  structureNodeId: string;
  structureNodeType: number;
  canonicalPath: string;
  itemIndex: number;
  parentText: string;
  charCount: number;
  tokenCount: number;
  childCount: number;
  startChunkNo: number;
  endChunkNo: number;
}

export interface DocumentChunk {
  id: string;
  documentId: string;
  taskId: string;
  planId: string;
  parentBlockId: string;
  chunkNo: number;
  sourceType: number;
  sectionPath: string;
  structureNodeId: string;
  structureNodeType: number;
  canonicalPath: string;
  itemIndex: number;
  chunkText: string;
  charCount: number;
  tokenCount: number;
  vectorStatus: number;
  vectorStoreType: number;
  vectorId: string;
}

/** 服务端分页 Chunk 列表项（/document/{id}/chunks） */
export interface DocumentChunkVo {
  chunkId: string;
  parentBlockId?: string;
  parentBlockNo?: number;
  parentChildCount?: number;
  parentStartChunkNo?: number;
  parentEndChunkNo?: number;
  chunkNo: number;
  sectionPath?: string;
  sourceType: number;
  charCount: number;
  tokenCount: number;
  vectorStatus: number;
  chunkText: string;
}

export interface DocumentParentBlockVo {
  parentBlockId: string;
  parentBlockNo: number;
  sectionPath?: string;
  sourceType: number;
  charCount: number;
  childCount: number;
  startChunkNo: number;
  endChunkNo: number;
  parentText: string;
}

export interface DocumentChunkDetailVo {
  documentId: string;
  taskId?: string;
  planId?: string;
  chunk?: DocumentChunkVo;
  parentBlock?: DocumentParentBlockVo;
  siblingChunks?: DocumentChunkVo[];
}

export interface DocumentTaskLogVo {
  id: string;
  stageType: number;
  eventType: number;
  logLevel: number;
  content: string;
  detailJson?: string;
  createTime: string;
}

export interface DocumentTaskLogQueryVo {
  taskId: string;
  documentId: string;
  taskType: number;
  taskStatus: number;
  currentStage?: number;
  startTime?: string;
  finishTime?: string;
  costMillis?: number;
  errorCode?: string;
  errorMsg?: string;
  total: number;
  logs: DocumentTaskLogVo[];
}

export interface DocumentDeleteVo {
  documentId: string;
  documentName: string;
  storageCleaned: boolean;
  vectorCleaned: boolean;
  keywordCleaned: boolean;
}

/** Lightweight document info stored in localStorage */
export interface StoredDocument {
  documentId: string;
  documentName: string;
  uploadedAt: string;
}

/** 文档分页列表查询入参 */
export interface DocumentPageQuery {
  pageNo?: number;
  pageSize?: number;
  keyword?: string;
}

/** 文档列表项（含知识路由元数据） */
export interface DocumentListItemVo {
  documentId: string;
  documentName: string;
  originalFileName?: string;
  fileType: number;
  fileSize?: number;
  charCount?: number;
  tokenCount?: number;
  parseStatus: number;
  strategyStatus: number;
  indexStatus: number;
  parseErrorMsg?: string;
  knowledgeScopeCode?: string;
  knowledgeScopeName?: string;
  businessCategory?: string;
  documentTags?: string;
  currentPlanId?: string;
  latestIndexTaskId?: string;
  latestTaskId?: string;
  latestTaskType?: number;
  latestTaskStatus?: number;
  createTime?: string;
  updateTime?: string;
}
