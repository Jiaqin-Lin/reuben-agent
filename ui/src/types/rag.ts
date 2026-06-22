export interface RagRetrieveRequest {
  query: string;
  topK?: number;
  filterFields?: Record<string, string>;
}

export interface RetrievalResult {
  chunkId: string;
  chunkText: string;
  score: number;
  sectionPath: string;
  documentId: string;
  parentBlockId: string;
  source: 'vector' | 'keyword' | 'hybrid';
}

export interface RagRetrieveResponse {
  results: RetrievalResult[];
  totalCostMs: number;
}
