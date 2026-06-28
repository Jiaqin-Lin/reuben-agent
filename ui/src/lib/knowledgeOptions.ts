/**
 * 知识路由管理 —— 选项 / 标签映射。
 * 字段语义对齐 super-agent AdminKnowledgeRouteView。
 */

export interface OptionItem {
  value: string;
  label: string;
}

export const ANSWER_SHAPE_OPTIONS: OptionItem[] = [
  { value: 'list', label: '列表型回答' },
  { value: 'explain', label: '解释说明型回答' },
  { value: 'steps', label: '步骤型回答' },
];

export const EXECUTION_PREFERENCE_OPTIONS: OptionItem[] = [
  { value: 'retrieval', label: '普通检索优先' },
  { value: 'graph_assist', label: '图辅助优先' },
];

export const DOCUMENT_TYPE_OPTIONS: OptionItem[] = [
  { value: 'intro', label: '介绍型文档' },
  { value: 'manual', label: '操作手册' },
  { value: 'rule', label: '规则文档' },
  { value: 'faq', label: '常见问题' },
  { value: 'troubleshooting', label: '故障排查' },
  { value: 'spec', label: '规格说明' },
];

export const PROFILE_SOURCE_OPTIONS: OptionItem[] = [
  { value: 'auto', label: '自动生成' },
  { value: 'manual', label: '手动维护' },
  { value: 'mixed', label: '自动 + 手动' },
];

function buildLabelMap(options: OptionItem[]): Record<string, string> {
  return options.reduce<Record<string, string>>((map, item) => {
    map[item.value] = item.label;
    return map;
  }, {});
}

export const ANSWER_SHAPE_LABEL = buildLabelMap(ANSWER_SHAPE_OPTIONS);
export const EXECUTION_PREFERENCE_LABEL = buildLabelMap(EXECUTION_PREFERENCE_OPTIONS);
export const DOCUMENT_TYPE_LABEL = buildLabelMap(DOCUMENT_TYPE_OPTIONS);
export const PROFILE_SOURCE_LABEL = buildLabelMap(PROFILE_SOURCE_OPTIONS);

export function formatMappedLabel(value: unknown, labelMap: Record<string, string>): string {
  const normalized = String(value ?? '').trim();
  if (!normalized) return '未设置';
  return labelMap[normalized] || normalized;
}

/** 画像状态：1=待生成 2=生成中 3=成功 4=失败 */
export function profileStatusMeta(status?: number): { label: string; tone: 'success' | 'processing' | 'danger' | 'waiting' } {
  switch (status) {
    case 3:
      return { label: '成功', tone: 'success' };
    case 2:
      return { label: '生成中', tone: 'processing' };
    case 4:
      return { label: '失败', tone: 'danger' };
    default:
      return { label: '待生成', tone: 'waiting' };
  }
}

/** 图能力开关汇总文本。 */
export function graphCapabilityText(profile: { supportsGraphOutline?: number; supportsItemLookup?: number; supportsGraphAssist?: number }): string {
  const enabled: string[] = [];
  if (String(profile.supportsGraphOutline) === '1') enabled.push('大纲导航');
  if (String(profile.supportsItemLookup) === '1') enabled.push('条目定位');
  if (String(profile.supportsGraphAssist) === '1') enabled.push('图辅助检索');
  return enabled.length ? enabled.join(' / ') : '未开启';
}

/** 英文逗号分隔的文本列表。 */
export function parseTextList(value?: string): string[] {
  const normalized = String(value ?? '').trim();
  if (!normalized) return [];
  return normalized
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

/** JSON 数组字段解析。 */
export function parseJsonArray(value?: string): string[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.filter(Boolean) : [];
  } catch {
    return [];
  }
}
