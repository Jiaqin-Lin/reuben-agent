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
  charCount: number;
  structureLevel: number;
  qualityLevel: number;
  createTime: string;
  updateTime: string;
  parentBlocks?: DocumentParentBlock[];
  chunks?: DocumentChunk[];
}

export interface DocumentStrategyPlanVo {
  planId: string;
  documentId: string;
  planVersion: number;
  planSource: number;
  planStatus: number;
  strategyCount: number;
  strategySnapshot: string;
  recommendReason: string;
}

export interface DocumentStrategyConfirmDto {
  documentId: string;
  planId: string;
  confirmUserId?: string;
}

export interface DocumentStrategyConfirmVo {
  taskId: string;
  planId: string;
  documentId: string;
  planStatus: number;
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

/** Lightweight document info stored in localStorage */
export interface StoredDocument {
  documentId: string;
  documentName: string;
  uploadedAt: string;
}
