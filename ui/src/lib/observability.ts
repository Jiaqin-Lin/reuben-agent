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
