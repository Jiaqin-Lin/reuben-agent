/**
 * 知识路由追踪解析 —— 移植自 super-agent utils/knowledgeRoute.js。
 * 把后端 KnowledgeRouteTraceItemVo 的 JSON 字段解析为可渲染结构。
 */
import type {
  KnowledgeRouteTraceItemVo,
  RouteCandidate,
} from '../types/knowledge';

const ROUTE_MODE_LABELS: Record<string, string> = {
  auto: '自动知识路由',
  shadow: '影子路由对比',
};

const ROUTE_STATUS_ALIAS: Record<string, string> = {
  '1': 'SUCCESS',
  '2': 'LOW_CONFIDENCE',
  '3': 'FAILED',
  SUCCESS: 'SUCCESS',
  LOW_CONFIDENCE: 'LOW_CONFIDENCE',
  FAILED: 'FAILED',
};

const ROUTE_STATUS_META: Record<string, { key: string; label: string; tone: 'success' | 'warning' | 'danger' }> = {
  SUCCESS: { key: 'SUCCESS', label: '成功', tone: 'success' },
  LOW_CONFIDENCE: { key: 'LOW_CONFIDENCE', label: '低置信', tone: 'warning' },
  FAILED: { key: 'FAILED', label: '失败', tone: 'danger' },
};

function asString(value: unknown): string {
  if (value == null) return '';
  return String(value).trim();
}

function toNumber(value: unknown): number | null {
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}

function parseCandidateList(rawValue?: string): RouteCandidate[] {
  const normalized = asString(rawValue);
  if (!normalized) return [];
  try {
    const parsed = JSON.parse(normalized);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function resolveRouteStatusMeta(value: unknown) {
  const alias = ROUTE_STATUS_ALIAS[asString(value)] || 'FAILED';
  return ROUTE_STATUS_META[alias] || ROUTE_STATUS_META.FAILED;
}

function resolveConfidenceBand(value: unknown) {
  const numeric = toNumber(value);
  if (numeric == null || numeric <= 0) return { label: '未形成有效置信度', tone: 'danger' as const };
  if (numeric >= 0.8) return { label: '高置信', tone: 'success' as const };
  if (numeric >= 0.55) return { label: '可用但偏保守', tone: 'warning' as const };
  return { label: '需要扩范围', tone: 'danger' as const };
}

function normalizeCandidate(item: RouteCandidate = {} as RouteCandidate): RouteCandidate & {
  scoreNumber: number | null;
  scoreText: string;
} {
  const scoreNumber = toNumber(item.score);
  return {
    ...item,
    scoreNumber,
    scoreText: scoreNumber == null ? '-' : scoreNumber.toFixed(4),
  };
}

export function formatRouteMode(value?: string): string {
  return ROUTE_MODE_LABELS[asString(value)] || asString(value) || '未知路由模式';
}

export interface NormalizedRouteTrace {
  mode: string;
  modeLabel: string;
  scopes: (RouteCandidate & { scoreNumber: number | null; scoreText: string })[];
  topics: (RouteCandidate & { scoreNumber: number | null; scoreText: string })[];
  documents: (RouteCandidate & { scoreNumber: number | null; scoreText: string })[];
  topDocument: (RouteCandidate & { scoreNumber: number | null; scoreText: string }) | null;
  selectedDocumentId: string;
  selectedDocument: (RouteCandidate & { scoreNumber: number | null; scoreText: string }) | null;
  confidenceNumber: number | null;
  confidenceText: string;
  confidenceBand: { label: string; tone: 'success' | 'warning' | 'danger' };
  statusKey: string;
  statusLabel: string;
  statusTone: 'success' | 'warning' | 'danger';
  reasonText: string;
  createTimeNumber: number;
  hitTop3: boolean;
  missedTop3: boolean;
  candidateDocumentCount: number;
  candidateTopicCount: number;
  candidateScopeCount: number;
  lowConfidenceWidened: boolean;
}

export function normalizeRouteTrace(record: KnowledgeRouteTraceItemVo = {} as KnowledgeRouteTraceItemVo): NormalizedRouteTrace {
  const scopes = parseCandidateList(record.topScopesJson).map(normalizeCandidate);
  const topics = parseCandidateList(record.topTopicsJson).map(normalizeCandidate);
  const documents = parseCandidateList(record.topDocumentsJson).map(normalizeCandidate);
  const confidenceNumber = toNumber(record.confidence);
  const statusMeta = resolveRouteStatusMeta(record.routeStatus);
  const selectedDocumentId = asString(record.selectedDocumentId);
  const selectedDocument = selectedDocumentId
    ? documents.find((item) => asString(item.documentId) === selectedDocumentId) || null
    : null;
  const topDocument = documents[0] || null;
  const createTimeNumber = toNumber(record.createTime) || 0;
  const hitSelectedDocument = asString(record.hitSelectedDocument);
  const mode = asString(record.mode);

  return {
    mode,
    modeLabel: formatRouteMode(mode),
    scopes,
    topics,
    documents,
    topDocument,
    selectedDocumentId,
    selectedDocument,
    confidenceNumber,
    confidenceText: confidenceNumber == null ? '-' : confidenceNumber.toFixed(4),
    confidenceBand: resolveConfidenceBand(confidenceNumber),
    statusKey: statusMeta.key,
    statusLabel: statusMeta.label,
    statusTone: statusMeta.tone,
    reasonText: asString(record.errorMsg) || asString(topDocument?.reason),
    createTimeNumber,
    hitTop3: hitSelectedDocument === '1',
    missedTop3: hitSelectedDocument === '0',
    candidateDocumentCount: documents.length,
    candidateTopicCount: topics.length,
    candidateScopeCount: scopes.length,
    lowConfidenceWidened: mode === 'auto' && confidenceNumber != null && confidenceNumber < 0.8 && documents.length >= 5,
  };
}

/** 按 exchangeId/turnId 建查找表，auto 优先，时间新优先。 */
export function buildRouteTraceLookup(records: KnowledgeRouteTraceItemVo[] = []): Record<string, NormalizedRouteTrace> {
  return records.reduce<Record<string, NormalizedRouteTrace>>((lookup, item) => {
    const normalized = normalizeRouteTrace(item);
    const turnId = String(item.turnId ?? '');
    if (!turnId) return lookup;
    const existing = lookup[turnId];
    if (!existing) {
      lookup[turnId] = normalized;
      return lookup;
    }
    if (normalized.mode === 'auto' && existing.mode !== 'auto') {
      lookup[turnId] = normalized;
      return lookup;
    }
    if (normalized.createTimeNumber >= existing.createTimeNumber) {
      lookup[turnId] = normalized;
    }
    return lookup;
  }, {});
}

export interface ChatRouteExplain extends NormalizedRouteTrace {
  summary: string;
  notes: string[];
  topDocuments: (RouteCandidate & { scoreNumber: number | null; scoreText: string })[];
  scopePreview: (RouteCandidate & { scoreNumber: number | null; scoreText: string })[];
  topicPreview: (RouteCandidate & { scoreNumber: number | null; scoreText: string })[];
}

export function buildChatRouteExplain(record?: NormalizedRouteTrace | null): ChatRouteExplain | null {
  if (!record) return null;
  const trace = record;
  const topDocuments = trace.documents.slice(0, 5);
  const scopePreview = trace.scopes.slice(0, 3);
  const topicPreview = trace.topics.slice(0, 3);
  const notes: string[] = [];
  let summary = '';

  if (trace.mode === 'auto') {
    summary = trace.topDocument
      ? `系统先做知识范围预选，再把 ${trace.candidateDocumentCount} 份候选文档交给稳定检索链路；当前主候选是「${trace.topDocument.documentName || trace.topDocument.documentId}」。`
      : '系统先做知识范围预选，再进入稳定检索链路；本轮没有形成稳定的显式主候选文档。';
    if (trace.lowConfidenceWidened) notes.push('当前置信度偏低，系统已放宽候选范围后再进入稳定检索。');
    if (!trace.documents.length) notes.push('原始路由没有产出显式候选文档，执行期会回退到可检索文档池。');
    if (trace.reasonText) notes.push(`路由依据：${trace.reasonText}`);
  } else if (trace.mode === 'shadow') {
    summary = trace.topDocument
      ? `系统对这轮问题做了影子路由对比，影子 Top1 是「${trace.topDocument.documentName || trace.topDocument.documentId}」，但实际回答仍固定使用你手动选择的当前文档。`
      : '系统对这轮问题做了影子路由对比，但没有形成稳定的影子候选文档。';
    if (trace.hitTop3) notes.push('影子路由 Top3 已覆盖当前文档，说明自动路由与人工选文档基本一致。');
    if (trace.missedTop3) notes.push('影子路由 Top3 未覆盖当前文档，说明这轮问题更像跨文档或元数据仍需补强。');
    if (trace.reasonText) notes.push(`影子路由依据：${trace.reasonText}`);
  } else {
    return null;
  }

  return { ...trace, summary, notes, topDocuments, scopePreview, topicPreview };
}
