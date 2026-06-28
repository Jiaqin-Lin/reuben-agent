import { apiUpload, apiGet, apiPost } from './client';
import type {
  DocumentUploadDto,
  DocumentUploadVo,
  DocumentDetailVo,
  DocumentStrategyPlanVo,
  DocumentStrategyConfirmVo,
  DocumentStrategyConfirmDto,
  DocumentPageQuery,
  DocumentListItemVo,
} from '../types/document';
import type { PageVo } from '../types/chat';

/** 文档分页列表（含知识范围、业务分类、标签等路由元数据）。 */
export function pageQueryDocuments(
  query: DocumentPageQuery = {},
): Promise<PageVo<DocumentListItemVo>> {
  const params: Record<string, string> = {
    pageNo: String(query.pageNo ?? 1),
    pageSize: String(query.pageSize ?? 20),
  };
  if (query.keyword) params.keyword = query.keyword;
  return apiGet<PageVo<DocumentListItemVo>>('/document/page', params);
}

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
