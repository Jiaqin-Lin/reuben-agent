export const ParseStatus: Record<number, string> = {
  1: '待解析',
  2: '解析中',
  3: '已解析',
  4: '失败',
};

export const StrategyStatus: Record<number, string> = {
  1: '待推荐',
  2: '已推荐',
  3: '已确认',
  4: '已过期',
};

export const PlanStatus: Record<number, string> = {
  1: '待确认',
  2: '已确认',
  3: '已执行',
  4: '已废弃',
};

export const IndexStatus: Record<number, string> = {
  1: '待构建',
  2: '构建中',
  3: '已索引',
  4: '失败',
};

export const VectorStatus: Record<number, string> = {
  1: '待向量化',
  2: '向量化中',
  3: '已完成',
  4: '失败',
};

export const QualityLevel: Record<number, string> = {
  0: '未知',
  1: '低',
  2: '中低',
  3: '中',
  4: '中高',
  5: '高',
};

export const StructureLevel: Record<number, string> = {
  0: '未知',
  1: '低',
  2: '中',
  3: '高',
};

export const FileType: Record<number, string> = {
  1: 'PDF',
  2: 'DOCX',
  3: 'TXT',
  4: 'MD',
  5: 'HTML',
};

export type StatusType = 'parse' | 'strategy' | 'plan' | 'index' | 'vector';

export function getStatusStyle(type: StatusType, code: number): string {
  if (code === 2) return 'bg-amber-500/15 text-amber-400 border-amber-500/30';
  if (code === 3) return 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30';
  if (code === 4) return 'bg-red-500/15 text-red-400 border-red-500/30';
  return 'bg-neutral-800 text-neutral-400 border-neutral-700';
}
