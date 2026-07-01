/**
 * 对话可观测性格式化 —— 移植 super-agent observabilityHelpers，适配 reuben-agent 数值型枚举。
 * reuben-agent chatMode: 1=DOCUMENT 2=OPEN_CHAT 3=AUTO_DOCUMENT
 * turnStatus: 1=执行中 2=完成 3=失败 4=停止
 */

export type StatusTone = 'running' | 'completed' | 'failed' | 'stopped' | 'idle' | 'warning';

export type ChatModeLabel = '当前文档问答' | '开放式提问' | '自动知识问答';

/** stage 状态 1=RUNNING 2=COMPLETED 3=FAILED 4=SKIPPED */
export function stageStateLabel(state?: number): string {
  switch (state) {
    case 1:
      return 'RUNNING';
    case 2:
      return 'COMPLETED';
    case 3:
      return 'FAILED';
    case 4:
      return 'SKIPPED';
    default:
      return '未知';
  }
}

export function stageStateTone(state?: number): StatusTone {
  switch (state) {
    case 1:
      return 'running';
    case 2:
      return 'completed';
    case 3:
      return 'failed';
    case 4:
      return 'idle';
    default:
      return 'idle';
  }
}

export function chatModeLabel(mode?: number): string {
  switch (mode) {
    case 1:
      return '当前文档问答';
    case 2:
      return '开放式提问';
    case 3:
      return '自动知识问答';
    default:
      return '未知模式';
  }
}

export function turnStatusLabel(status?: number): string {
  switch (status) {
    case 1:
      return '执行中';
    case 2:
      return '完成';
    case 3:
      return '失败';
    case 4:
      return '停止';
    default:
      return '未知状态';
  }
}

export function turnStatusTone(status?: number): StatusTone {
  switch (status) {
    case 1:
      return 'running';
    case 2:
      return 'completed';
    case 3:
      return 'failed';
    case 4:
      return 'stopped';
    default:
      return 'idle';
  }
}

/** executionMode 1=仅结构图定位 2=先结构定位再检索 3=直接检索 4=ReAct 5=澄清。 */
export function executionModeLabel(mode?: number): string {
  switch (mode) {
    case 1:
      return '仅结构图定位';
    case 2:
      return '先结构定位再检索';
    case 3:
      return '直接证据检索';
    case 4:
      return 'ReAct Agent';
    case 5:
      return '澄清确认';
    default:
      return '未识别';
  }
}

export function channelLabel(type?: string): string {
  const map: Record<string, string> = {
    keyword: '关键词检索',
    vector: '向量检索',
    rerank: '重排精排',
    hybrid: '融合结果',
    'web-search': '网页搜索',
  };
  if (!type) return '未知通道';
  return map[type] || type;
}

export function formatTime(value?: string): string {
  if (!value) return '刚刚';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

export function formatDateTime(value?: string | number): string {
  if (!value) return '无';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '无';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date);
}

export function formatLatency(value?: number): string {
  if (value == null || value <= 0) return '无';
  return `${value} ms`;
}

export function formatScore(value?: number): string {
  if (value == null) return '-';
  if (Number.isNaN(value)) return '-';
  return value.toFixed(4);
}

export function truncate(value: string, max: number): string {
  if (!value) return '';
  return value.length > max ? `${value.slice(0, max)}...` : value;
}

export function shortenId(value?: string): string {
  const normalized = String(value ?? '');
  if (normalized.length <= 14) return normalized || '-';
  return `${normalized.slice(0, 6)}...${normalized.slice(-6)}`;
}

/**
 * 数字分页导航条目：当前页左右各保留 1 页，首尾恒定显示，中间用 '...' 省略号。
 * 例如 current=1 total=10 → [1,2,'...',9,10]；current=5 → [1,'...',4,5,6,'...',10]。
 */
export function paginationItems(current: number, total: number): (number | '...')[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1);
  }
  const items: (number | '...')[] = [1];
  const left = Math.max(2, current - 1);
  const right = Math.min(total - 1, current + 1);
  if (left > 2) items.push('...');
  for (let i = left; i <= right; i++) items.push(i);
  if (right < total - 1) items.push('...');
  items.push(total);
  return items;
}

/** 通道执行状态格式化。 */
export function executionStateLabel(state?: string): string {
  if (!state) return '未知';
  const normalized = state.toUpperCase();
  if (normalized === 'COMPLETED' || normalized === '1') return '已完成';
  if (normalized === 'FAILED' || normalized === '0') return '失败';
  if (normalized === 'RUNNING') return '进行中';
  return state;
}

/**
 * 单阶段耗时与基准对比：本次耗时落在 P50/P90/P99 哪个区间。
 * 移植 Vue formatBenchmarkComparison，level 用于配色，text 用于展示。
 */
export function formatBenchmarkComparison(
  actualMs?: number,
  benchmark?: { p50?: number; p90?: number; p99?: number } | null,
): { level: 'excellent' | 'good' | 'warning' | 'slow'; text: string } | null {
  if (!benchmark || !actualMs) return null;
  const p50 = benchmark.p50 ?? 0;
  const p90 = benchmark.p90 ?? 0;
  const p99 = benchmark.p99 ?? 0;
  if (actualMs <= p50) return { level: 'excellent', text: '优秀（≤ P50）' };
  if (actualMs <= p90) return { level: 'good', text: '良好（P50-P90）' };
  if (actualMs <= p99) return { level: 'warning', text: '偏慢（P90-P99）' };
  return { level: 'slow', text: '异常慢（> P99）' };
}

export const BENCHMARK_LEVEL_CLASS: Record<string, string> = {
  excellent: 'text-emerald-400',
  good: 'text-amber-400',
  warning: 'text-amber-400',
  slow: 'text-red-400',
};

// ==================== Trace Stage Inspector ====================
// 移植 super-agent observabilityHelpers.buildTraceStageInspector / buildUsageStageInspector。
// reuben-agent 后端用数值型 stageCode（1..10，见 ChatTraceStageCode 枚举），
// 且当前未持久化阶段 snapshot —— snapshot 为空时 inspector 仅渲染 summary/error 等基础项，
// 后端补齐 snapshot 持久化后自动点亮结构化面板，无需改前端。

/** stageCode → Vue 侧字符串枚举映射（buildTraceStageInspector 用 switch 分派）。 */
const STAGE_CODE_KEY: Record<number, string> = {
  1: 'MEMORY',
  2: 'INTENT',
  3: 'REWRITE',
  4: 'ROUTE',
  5: 'RAG_RETRIEVE',
  6: 'EVIDENCE_BUDGET',
  7: 'ANSWER_GENERATE',
  8: 'REACT_AGENT',
  9: 'RECOMMENDATION',
  10: 'FINALIZE',
};

export interface InspectorPair {
  label: string;
  value: string;
  code?: boolean;
}

export interface InspectorListSection {
  label: string;
  items: string[];
  ordered?: boolean;
}

export interface InspectorTableSection {
  label: string;
  columns: string[];
  rows: { cells: string[] }[];
}

export interface StageInspector {
  title: string;
  summary: string;
  status?: number;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  summaryItems: InspectorPair[];
  listSections: InspectorListSection[];
  tableSections: InspectorTableSection[];
  advancedItems: InspectorPair[];
}

interface StageLike {
  stageCode: number;
  stageName: string;
  summaryText?: string;
  stageState?: number;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  snapshot?: Record<string, unknown> | null;
}

interface ExchangeLike {
  question?: string;
  answer?: string;
  debugTrace?: {
    modelUsageTraces?: Array<Record<string, unknown>> | null;
    limitStats?: Record<string, unknown> | null;
  } | null;
  createTime?: string;
  editTime?: string;
  totalResponseTimeMs?: number;
}

function snapshotValue(snapshot: Record<string, unknown> | null | undefined, key: string): unknown {
  if (!snapshot || typeof snapshot !== 'object') return '';
  return (snapshot as Record<string, unknown>)[key];
}

function snapshotList(snapshot: Record<string, unknown> | null | undefined, key: string): unknown[] {
  const value = snapshotValue(snapshot, key);
  return Array.isArray(value) ? value.filter(Boolean) : [];
}

function asList<T = unknown>(value: unknown): T[] {
  return Array.isArray(value) ? value : [];
}

function pushPair(target: InspectorPair[], label: string, value: unknown, options: { code?: boolean } = {}): void {
  if (value == null || value === '') return;
  target.push({ label, value: String(value), code: Boolean(options.code) });
}

function safeJson(snapshot: Record<string, unknown> | null | undefined): string {
  if (!snapshot || typeof snapshot !== 'object' || !Object.keys(snapshot).length) return '';
  try {
    return JSON.stringify(snapshot, null, 2);
  } catch {
    return '';
  }
}

function stageUsageDetails(exchange: ExchangeLike, stageNames: string[]): string[] {
  const traces = asList<Record<string, unknown>>(exchange?.debugTrace?.modelUsageTraces);
  return traces
    .filter((item) => stageNames.includes(String(item?.stageName ?? '')))
    .map((item) => {
      const tokens = item?.totalTokens ? `总Token ${item.totalTokens}` : '';
      const prompt = item?.promptTokens ? `输入 ${item.promptTokens}` : '';
      const completion = item?.completionTokens ? `输出 ${item.completionTokens}` : '';
      const cost = item?.estimatedCost ? `成本约 ¥${Number(item.estimatedCost).toFixed(4)}` : '';
      const duration = item?.durationMs ? `耗时 ${item.durationMs} ms` : '';
      return `${item?.stageName || 'unknown'} | ${item?.provider || 'unknown'} / ${item?.model || 'unknown'} | ${[prompt, completion, tokens, cost, duration].filter(Boolean).join('，')}`;
    });
}

function buildReferenceDecisionRows(details: unknown[] = []): { reference: string; reason: string }[] {
  return asList(details).map((detail) => {
    const text = String(detail ?? '');
    const index = text.lastIndexOf(' | ');
    if (index === -1) return { reference: text, reason: '' };
    return { reference: text.slice(0, index), reason: text.slice(index + 3) };
  });
}

/** 关系类型 / 答案形态 / 检索模式格式化（snapshot 内字符串值 → 中文）。 */
function formatRelationType(value: unknown): string {
  const map: Record<string, string> = {
    FOLLOW_UP: '追问',
    NEW_TOPIC: '新话题',
    CONTINUATION: '延续',
    CLARIFICATION: '澄清',
  };
  return map[String(value)] || String(value || '');
}
function formatAnswerShape(value: unknown): string {
  const map: Record<string, string> = {
    LIST: '列表',
    TEXT: '文本',
    TABLE: '表格',
    CODE: '代码',
    MIXED: '混合',
  };
  return map[String(value)] || String(value || '');
}
function formatRetrievalMode(value: unknown): string {
  const map: Record<string, string> = {
    RAG: 'RAG 检索',
    GRAPH: '结构图定位',
    REACT: 'ReAct',
    NONE: '不检索',
  };
  return map[String(value)] || String(value || '');
}
function formatConfidence(value: unknown): string {
  if (value == null || value === '') return '';
  return String(value);
}

/**
 * 构建某阶段的 Inspector：summaryItems / listSections / tableSections / advancedItems。
 * snapshot 缺失时退化为仅基础项 + summaryText，不报错。
 */
export function buildTraceStageInspector(stageTrace: StageLike | null, exchange: ExchangeLike | null): StageInspector | null {
  if (!stageTrace) return null;

  const snapshot = stageTrace.snapshot ?? null;
  const stageKey = STAGE_CODE_KEY[stageTrace.stageCode];
  const summaryItems: InspectorPair[] = [];
  const listSections: InspectorListSection[] = [];
  const tableSections: InspectorTableSection[] = [];
  const advancedItems: InspectorPair[] = [];

  switch (stageKey) {
    case 'MEMORY':
      pushPair(summaryItems, '是否命中长期摘要', snapshotValue(snapshot, 'compressionApplied') ? '是' : '否');
      pushPair(summaryItems, '摘要覆盖到的最后一轮', snapshotValue(snapshot, 'coveredExchangeId'));
      pushPair(summaryItems, '摘要覆盖轮次', snapshotValue(snapshot, 'coveredExchangeCount'));
      pushPair(summaryItems, '累计压缩次数', snapshotValue(snapshot, 'compressionCount'));
      pushPair(advancedItems, '长期摘要文本', snapshotValue(snapshot, 'longTermSummary'), { code: true });
      pushPair(advancedItems, '最近原文窗口', snapshotValue(snapshot, 'recentTranscript'), { code: true });
      pushPair(advancedItems, '回答阶段最近上下文', snapshotValue(snapshot, 'answerRecentTranscript'), { code: true });
      listSections.push({ label: '这一阶段的模型使用', items: stageUsageDetails(exchange ?? {}, ['summary']), ordered: false });
      break;
    case 'INTENT':
      pushPair(summaryItems, '原始问题', snapshotValue(snapshot, 'originalQuestion'));
      pushPair(summaryItems, '关系判定', formatRelationType(snapshotValue(snapshot, 'relationType')));
      pushPair(summaryItems, '当前主题', snapshotValue(snapshot, 'resolvedTopic'));
      pushPair(summaryItems, '当前面向', snapshotValue(snapshot, 'resolvedFacet'));
      pushPair(summaryItems, '信息需求', snapshotValue(snapshot, 'informationNeed'));
      pushPair(summaryItems, '答案形态', formatAnswerShape(snapshotValue(snapshot, 'answerShape')));
      pushPair(summaryItems, '检索模式', formatRetrievalMode(snapshotValue(snapshot, 'retrievalMode')));
      pushPair(summaryItems, '检索查询', snapshotValue(snapshot, 'retrievalQuery'));
      pushPair(summaryItems, '置信度', formatConfidence(snapshotValue(snapshot, 'confidence')));
      pushPair(summaryItems, '判定理由', snapshotValue(snapshot, 'rationale'));
      listSections.push({
        label: '分析时参考的上轮锚点',
        items: snapshotValue(snapshot, 'previousAnchorDescription') ? [String(snapshotValue(snapshot, 'previousAnchorDescription'))] : [],
        ordered: false,
      });
      listSections.push({ label: '规划出的检索子问题', items: snapshotList(snapshot, 'retrievalSubQuestions').map(String), ordered: true });
      listSections.push({ label: '软章节提示', items: snapshotList(snapshot, 'softSectionHints').map(String), ordered: false });
      listSections.push({ label: '上下文提示词', items: snapshotList(snapshot, 'queryContextHints').map(String), ordered: false });
      listSections.push({ label: '这一阶段的模型使用', items: stageUsageDetails(exchange ?? {}, ['intent']), ordered: false });
      break;
    case 'REWRITE':
      pushPair(summaryItems, '原始问题', exchange?.question || '');
      pushPair(summaryItems, '改写后问题', snapshotValue(snapshot, 'rewriteQuestion'));
      pushPair(summaryItems, '改写参考历史', snapshotValue(snapshot, 'historyContext'), { code: true });
      pushPair(summaryItems, '参数覆盖', snapshotValue(snapshot, 'rewriteOverrideEnabled') === true ? '已启用' : '未启用');
      pushPair(summaryItems, 'Temperature', snapshotValue(snapshot, 'rewriteTemperature'));
      pushPair(summaryItems, 'TopP', snapshotValue(snapshot, 'rewriteTopP'));
      pushPair(
        summaryItems,
        'Thinking',
        snapshotValue(snapshot, 'rewriteThinking') === true ? 'true' : snapshotValue(snapshot, 'rewriteThinking') === false ? 'false' : '',
      );
      listSections.push({ label: '改写拆分出的子问题', items: snapshotList(snapshot, 'subQuestions').map(String), ordered: true });
      listSections.push({ label: '这一阶段的模型使用', items: stageUsageDetails(exchange ?? {}, ['rewrite']), ordered: false });
      break;
    case 'ROUTE':
      pushPair(summaryItems, '原始问题', snapshotValue(snapshot, 'originalQuestion'));
      pushPair(summaryItems, '最终执行路径', executionModeLabel(Number(snapshotValue(snapshot, 'executionMode')) || undefined));
      pushPair(summaryItems, '最终检索问题', snapshotValue(snapshot, 'retrievalQuestion'));
      pushPair(summaryItems, '根主题', snapshotValue(snapshot, 'rootTopic'));
      pushPair(summaryItems, '根章节编码', snapshotValue(snapshot, 'rootSectionCode'));
      pushPair(summaryItems, '根章节标题', snapshotValue(snapshot, 'rootSectionTitle'));
      pushPair(summaryItems, '目标章节提示', snapshotValue(snapshot, 'targetSectionHint'));
      pushPair(summaryItems, '是否使用锚点', snapshotValue(snapshot, 'anchorApplied') ? '是' : '否');
      listSections.push({ label: '最终检索子问题', items: snapshotList(snapshot, 'retrievalSubQuestions').map(String), ordered: true });
      break;
    case 'RAG_RETRIEVE':
      pushPair(summaryItems, '实际检索问题', snapshotValue(snapshot, 'retrievalQuestion'));
      pushPair(summaryItems, '最终证据条数', snapshotValue(snapshot, 'referenceCount'));
      pushPair(summaryItems, '子问题数量', snapshotValue(snapshot, 'subQuestionCount'));
      listSections.push({ label: '使用通道', items: snapshotList(snapshot, 'usedChannels').map((c) => channelLabel(String(c))), ordered: false });
      listSections.push({ label: '检索过程说明', items: snapshotList(snapshot, 'retrievalNotes').map(String), ordered: false });
      listSections.push({
        label: '子问题检索明细',
        items: snapshotList(snapshot, 'subQuestions')
          .map((item) => {
            if (!item || typeof item !== 'object') return '';
            const obj = item as Record<string, unknown>;
            const channelTraceText = asList<Record<string, unknown>>(obj.channelTraces)
              .map((trace) => `${channelLabel(String(trace?.channelName))} raw=${trace?.recalledCount || 0} accepted=${trace?.acceptedCount || 0}`)
              .filter(Boolean)
              .join('；');
            return `${obj.index}. ${obj.question} | 通道 ${channelTraceText || '无'} | fused ${obj.fusedCandidateCount || 0} | parent ${obj.parentCandidateCount || 0} | rerank ${obj.rerankedCandidateCount || 0} | 文档 ${obj.documentCount || 0} | 引用 ${obj.referenceCount || 0}`;
          })
          .filter(Boolean),
        ordered: false,
      });
      listSections.push({
        label: '最终证据概览',
        items: snapshotList(snapshot, 'references')
          .map((item) => {
            if (!item || typeof item !== 'object') return '';
            const obj = item as Record<string, unknown>;
            return `[${obj.referenceId || '-'}] ${obj.documentName || '未命名引用'} ${obj.sectionPath ? `| ${obj.sectionPath}` : ''} ${obj.channel ? `| ${channelLabel(String(obj.channel))}` : ''}`.trim();
          })
          .filter(Boolean),
        ordered: false,
      });
      tableSections.push({
        label: '子问题检索链路',
        columns: ['子问题', '关键词 raw/accepted', '向量 raw/accepted', '融合', '父块', '重排', '最终引用'],
        rows: snapshotList(snapshot, 'subQuestions')
          .map((item) => {
            if (!item || typeof item !== 'object') return null;
            const obj = item as Record<string, unknown>;
            const channelTraces = asList<Record<string, unknown>>(obj.channelTraces);
            const keywordTrace = channelTraces.find((t) => t?.channelName === 'keyword');
            const vectorTrace = channelTraces.find((t) => t?.channelName === 'vector');
            return {
              cells: [
                `${obj.index}. ${obj.question}`,
                `${keywordTrace?.recalledCount ?? 0} / ${keywordTrace?.acceptedCount ?? 0}`,
                `${vectorTrace?.recalledCount ?? 0} / ${vectorTrace?.acceptedCount ?? 0}`,
                String(obj.fusedCandidateCount ?? 0),
                String(obj.parentCandidateCount ?? 0),
                String(obj.rerankedCandidateCount ?? 0),
                String(obj.referenceCount ?? 0),
              ],
            };
          })
          .filter(Boolean) as { cells: string[] }[],
      });
      tableSections.push({
        label: '最终证据表',
        columns: ['引用', '文档', '章节', '通道'],
        rows: snapshotList(snapshot, 'references')
          .map((item) => {
            if (!item || typeof item !== 'object') return null;
            const obj = item as Record<string, unknown>;
            return {
              cells: [
                String(obj.referenceId || '-'),
                String(obj.documentName || '未命名引用'),
                String(obj.sectionPath || '未识别章节'),
                channelLabel(String(obj.channel)),
              ],
            };
          })
          .filter(Boolean) as { cells: string[] }[],
      });
      break;
    case 'EVIDENCE_BUDGET':
      pushPair(summaryItems, '总预算', snapshotValue(snapshot, 'totalBudget'));
      pushPair(summaryItems, '单子问题预算', snapshotValue(snapshot, 'perSubQuestionBudget'));
      pushPair(summaryItems, '实际渲染引用', snapshotValue(snapshot, 'renderedReferenceCount'));
      pushPair(summaryItems, '被省略引用', snapshotValue(snapshot, 'omittedReferenceCount'));
      listSections.push({ label: '已纳入 Prompt 的引用', items: snapshotList(snapshot, 'renderedReferenceDetails').map(String), ordered: false });
      listSections.push({ label: '因预算被省略的引用', items: snapshotList(snapshot, 'omittedReferenceDetails').map(String), ordered: false });
      tableSections.push({
        label: '保留到 Prompt 的引用',
        columns: ['引用', '结果'],
        rows: buildReferenceDecisionRows(snapshotList(snapshot, 'renderedReferenceDetails')).map((item) => ({
          cells: [item.reference, item.reason || '已纳入 Prompt'],
        })),
      });
      tableSections.push({
        label: '因预算被裁掉的引用',
        columns: ['引用', '原因'],
        rows: buildReferenceDecisionRows(snapshotList(snapshot, 'omittedReferenceDetails')).map((item) => ({
          cells: [item.reference, item.reason || '超出上下文预算'],
        })),
      });
      pushPair(advancedItems, '系统 Prompt', snapshotValue(snapshot, 'systemPrompt'), { code: true });
      pushPair(advancedItems, '用户 Prompt', snapshotValue(snapshot, 'userPrompt'), { code: true });
      break;
    case 'ANSWER_GENERATE':
      pushPair(summaryItems, '首包耗时', snapshotValue(snapshot, 'firstResponseTimeMs') ? `${snapshotValue(snapshot, 'firstResponseTimeMs')} ms` : '');
      pushPair(summaryItems, '回答长度', snapshotValue(snapshot, 'answerLength'));
      pushPair(advancedItems, '本轮回答全文', exchange?.answer || '', { code: true });
      listSections.push({ label: '这一阶段的模型使用', items: stageUsageDetails(exchange ?? {}, ['rag_answer', 'react_agent_turn']), ordered: false });
      break;
    case 'REACT_AGENT':
      pushPair(summaryItems, '使用组件数', snapshotList(snapshot, 'usedTools').length);
      listSections.push({ label: '使用组件', items: snapshotList(snapshot, 'usedTools').map(String), ordered: false });
      break;
    case 'RECOMMENDATION':
      pushPair(summaryItems, '推荐问题数量', snapshotValue(snapshot, 'recommendationCount'));
      listSections.push({ label: '推荐问题列表', items: snapshotList(snapshot, 'recommendations').map(String), ordered: true });
      listSections.push({ label: '这一阶段的模型使用', items: stageUsageDetails(exchange ?? {}, ['recommendation']), ordered: false });
      break;
    case 'FINALIZE':
      pushPair(summaryItems, '最终状态', turnStatusLabel(Number(snapshotValue(snapshot, 'finalStatus')) || undefined));
      pushPair(summaryItems, '回答长度', snapshotValue(snapshot, 'answerLength'));
      pushPair(summaryItems, '引用数', snapshotValue(snapshot, 'referenceCount'));
      pushPair(summaryItems, '推荐问题数', snapshotValue(snapshot, 'recommendationCount'));
      pushPair(summaryItems, '结束原因', snapshotValue(snapshot, 'reason') || snapshotValue(snapshot, 'errorMessage'));
      break;
    default:
      pushPair(summaryItems, '阶段摘要', stageTrace.summaryText || '');
      break;
  }

  const rawSnapshot = safeJson(snapshot);
  if (rawSnapshot) pushPair(advancedItems, '原始阶段快照 JSON', rawSnapshot, { code: true });

  const normalizedListSections = listSections
    .map((section) => ({ ...section, items: asList(section.items).map(String).filter(Boolean) }))
    .filter((section) => section.items.length > 0);

  return {
    title: stageTrace.stageName,
    summary: stageTrace.summaryText || '',
    status: stageTrace.stageState,
    startTime: stageTrace.startTime,
    endTime: stageTrace.endTime,
    durationMs: stageTrace.durationMs,
    summaryItems,
    listSections: normalizedListSections,
    tableSections: tableSections.filter((section) => section.rows && section.rows.length > 0),
    advancedItems,
  };
}

/** 模型用量阶段 Inspector：汇总本轮所有模型调用的 token / 成本 / 限制。 */
export function buildUsageStageInspector(exchange: ExchangeLike | null): StageInspector | null {
  if (!exchange) return null;

  const usageTraces = asList<Record<string, unknown>>(exchange?.debugTrace?.modelUsageTraces);
  const limitStats = (exchange?.debugTrace?.limitStats ?? null) as Record<string, unknown> | null;
  const totalPromptTokens = usageTraces.reduce((sum, item) => sum + Number(item?.promptTokens || 0), 0);
  const totalCompletionTokens = usageTraces.reduce((sum, item) => sum + Number(item?.completionTokens || 0), 0);
  const totalTokens = usageTraces.reduce((sum, item) => sum + Number(item?.totalTokens || 0), 0);
  const totalCost = usageTraces.reduce((sum, item) => sum + Number(item?.estimatedCost || 0), 0);

  const rows = usageTraces.map((item) => ({
    cells: [
      usageStageName(String(item?.stageName ?? '')),
      `${item?.provider || 'unknown'} / ${item?.model || 'unknown'}`,
      String(item?.promptTokens ?? 0),
      String(item?.completionTokens ?? 0),
      String(item?.totalTokens ?? 0),
      item?.estimatedCost ? `¥ ${Number(item.estimatedCost).toFixed(4)}` : '无',
      item?.durationMs ? `${item.durationMs} ms` : '无',
      String(item?.status || 'UNKNOWN'),
    ],
  }));

  return {
    title: '模型使用与限制',
    summary: '这一轮里每一次模型调用都按阶段分组列在下面，便于排查到底哪个阶段最耗 token 和成本。',
    status: limitStats?.limitTriggered ? 4 : 2,
    startTime: exchange.createTime,
    endTime: exchange.editTime,
    durationMs: exchange.totalResponseTimeMs,
    summaryItems: [
      { label: '模型调用次数', value: String(usageTraces.length) },
      { label: '输入 Token', value: String(totalPromptTokens) },
      { label: '输出 Token', value: String(totalCompletionTokens) },
      { label: '总 Token', value: String(totalTokens) },
      { label: '总成本', value: totalCost > 0 ? `¥ ${totalCost.toFixed(4)}` : '无' },
      { label: '模型运行上限', value: limitStats?.modelCallsRunLimit != null ? `${limitStats.modelCallsUsed || 0}/${limitStats.modelCallsRunLimit}` : '' },
      { label: '工具运行上限', value: limitStats?.toolCallsRunLimit != null ? `${limitStats.toolCallsUsed || 0}/${limitStats.toolCallsRunLimit}` : '' },
      { label: '限制触发', value: limitStats?.limitTriggered ? String(limitStats.limitReason || '已触发') : '未触发' },
    ].filter((item) => item.value),
    listSections: [],
    tableSections: rows.length
      ? [{ label: '按阶段分组的模型使用明细', columns: ['阶段', '模型', '输入 Token', '输出 Token', '总 Token', '成本', '耗时', '状态'], rows }]
      : [],
    advancedItems: [
      limitStats?.modelCallsThreadLimit != null ? { label: '线程级模型上限', value: String(limitStats.modelCallsThreadLimit) } : null,
      limitStats?.toolCallsThreadLimit != null ? { label: '线程级工具上限', value: String(limitStats.toolCallsThreadLimit) } : null,
    ].filter((item): item is InspectorPair => item != null),
  };
}

const USAGE_STAGE_NAME_MAP: Record<string, string> = {
  intent: '意图分析',
  rewrite: '问题改写',
  summary: '会话记忆压缩',
  rag_answer: '回答生成',
  recommendation: '推荐问题',
  react_agent_turn: 'Agent 推理',
};
function usageStageName(stageName: string): string {
  return USAGE_STAGE_NAME_MAP[stageName] || stageName || '未知阶段';
}
