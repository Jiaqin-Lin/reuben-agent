import { apiPost } from './client';
import type { RagRetrieveRequest, RagRetrieveResponse } from '../types/rag';

export function retrieve(
  request: RagRetrieveRequest,
): Promise<RagRetrieveResponse> {
  return apiPost<RagRetrieveResponse>('/rag/retrieve', request);
}
