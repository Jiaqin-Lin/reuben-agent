import { apiUpload, apiGet, apiPost } from './client';
import type {
  DocumentUploadDto,
  DocumentUploadVo,
  DocumentDetailVo,
  DocumentStrategyPlanVo,
  DocumentStrategyConfirmVo,
  DocumentStrategyConfirmDto,
} from '../types/document';

export function uploadDocument(
  file: File,
  meta?: DocumentUploadDto,
  onProgress?: (pct: number) => void,
): Promise<DocumentUploadVo> {
  const fd = new FormData();
  fd.append('file', file);
  if (meta) {
    fd.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
  }
  return apiUpload<DocumentUploadVo>('/document/upload', fd, onProgress);
}

export function getDocument(id: string): Promise<DocumentDetailVo> {
  return apiGet<DocumentDetailVo>(`/document/${id}`);
}

export function getStrategyPlans(
  documentId: string,
): Promise<DocumentStrategyPlanVo[]> {
  return apiGet<DocumentStrategyPlanVo[]>('/document/strategy/plan', {
    documentId,
  });
}

export function confirmStrategy(
  dto: DocumentStrategyConfirmDto,
): Promise<DocumentStrategyConfirmVo> {
  return apiPost<DocumentStrategyConfirmVo>('/document/strategy/confirm', dto);
}
