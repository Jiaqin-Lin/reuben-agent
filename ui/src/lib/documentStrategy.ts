import type {
  DocumentStrategyPlanVo,
  DocumentStrategyStepVo,
  StrategyStepInput,
} from '../types/document';

export interface StrategyLibraryItem {
  type: number;
  label: string;
  description: string;
}

export interface PipelineLibraryItem {
  key: 'parent' | 'child';
  label: string;
  description: string;
}

/** 4 种切分策略 */
export const STRATEGY_LIBRARY: StrategyLibraryItem[] = [
  { type: 1, label: '结构化切分', description: '优先保留标题和章节边界' },
  { type: 2, label: '递归分块', description: '对超长内容继续裁剪兜底' },
  { type: 3, label: '语义分块', description: '优化主题边界和段落完整性' },
  { type: 4, label: '大模型智能切块', description: '处理复杂内容和低质量文本' },
];

export const STRATEGY_PIPELINE_LIBRARY: PipelineLibraryItem[] = [
  { key: 'parent', label: '父块流水线', description: '决定回答阶段看到的父块边界' },
  { key: 'child', label: '子块流水线', description: '决定检索召回使用的子块边界' },
];

export function strategyLabel(type: number | undefined): string {
  if (type == null) return '-';
  return STRATEGY_LIBRARY.find((s) => s.type === type)?.label ?? `策略${type}`;
}

export function strategyDescription(type: number | undefined): string {
  if (type == null) return '';
  return STRATEGY_LIBRARY.find((s) => s.type === type)?.description ?? '';
}

/** 取某管道的步骤（按 stepNo 升序） */
export function resolvePipelineSteps(
  plan: DocumentStrategyPlanVo | null | undefined,
  pipelineKey: 'parent' | 'child',
): DocumentStrategyStepVo[] {
  if (!plan?.steps) return [];
  return plan.steps
    .filter((s) => pipelineKeyToCode(pipelineKey) === s.pipelineType)
    .sort((a, b) => a.stepNo - b.stepNo);
}

/** 从 plan 步骤里提取某管道的策略类型顺序列表 */
export function extractPipelineStrategyTypes(
  plan: DocumentStrategyPlanVo | null | undefined,
  pipelineKey: 'parent' | 'child',
): number[] {
  return resolvePipelineSteps(plan, pipelineKey).map((s) => s.strategyType);
}

/** 调优后预览（带顺序号） */
export interface PreviewItem {
  type: number;
  index: number;
  order: string;
  label: string;
  description: string;
}

export function buildStrategyPreview(types: number[]): PreviewItem[] {
  const seen = new Set<number>();
  const ordered: number[] = [];
  for (const t of types) {
    if (!t || seen.has(t)) continue;
    const exists = STRATEGY_LIBRARY.some((s) => s.type === t);
    if (!exists) continue;
    seen.add(t);
    ordered.push(t);
  }
  return ordered.map((type, index) => {
    const item = STRATEGY_LIBRARY.find((s) => s.type === type)!;
    return {
      type,
      index,
      order: String(index + 1).padStart(2, '0'),
      label: item.label,
      description: item.description,
    };
  });
}

/** 签名：用于对比调优前后是否变化 */
export function buildStrategySignature(types: number[]): string {
  return buildStrategyPreview(types)
    .map((i) => i.type)
    .join('|');
}

/** 构造提交给后端的步骤入参 */
export function buildPipelineStepPayload(types: number[]): StrategyStepInput[] {
  return buildStrategyPreview(types).map((item, index) => ({
    stepNo: index + 1,
    strategyType: item.type,
  }));
}

/** 解析 strategySnapshot（"PARENT:1;CHILD:3,2"）→ { parent:[], child:[] } */
export function parseStrategySnapshot(
  snapshot: string | undefined,
): { parent: number[]; child: number[] } {
  const result = { parent: [] as number[], child: [] as number[] };
  if (!snapshot) return result;
  for (const part of snapshot.split(';')) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const colon = trimmed.indexOf(':');
    if (colon < 0) continue;
    const key = trimmed.slice(0, colon).trim().toLowerCase();
    const nums = trimmed
      .slice(colon + 1)
      .split(',')
      .map((n) => Number(n.trim()))
      .filter((n) => Number.isFinite(n) && n > 0);
    if (key === 'parent') result.parent = nums;
    else if (key === 'child') result.child = nums;
  }
  return result;
}

export function pipelineKeyToCode(key: 'parent' | 'child'): string {
  return key === 'parent' ? 'PARENT' : 'CHILD';
}
