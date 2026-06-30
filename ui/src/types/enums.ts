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

export const TaskStatus: Record<number, string> = {
  1: '新建',
  2: '进行中',
  3: '成功',
  4: '失败',
};

export const TaskStage: Record<number, string> = {
  4: '策略确认',
  5: '切块执行',
  6: '切块后处理',
  7: '向量化',
  8: '入库完成',
};

export const StrategyType: Record<number, string> = {
  1: '结构化切分',
  2: '递归切分',
  3: '语义切分',
  4: '大模型切分',
};

export const StrategyRole: Record<number, string> = {
  1: '主力',
  2: '优化',
  3: '兜底',
  4: '增强',
};

export const EventType: Record<number, string> = {
  1: '开始',
  2: '完成',
  3: '失败',
  4: '推荐',
  5: '用户调整',
  6: '用户确认',
};

export const LogLevel: Record<number, string> = {
  1: 'INFO',
  2: 'WARN',
  3: 'ERROR',
};

export const SourceType: Record<number, string> = {
  1: '原文',
  2: '后处理',
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

export type StatusType = 'parse' | 'strategy' | 'plan' | 'index' | 'vector' | 'task';

export function getStatusStyle(type: StatusType, code: number): string {
  // 进行中（code=2）一律 amber 脉冲；成功（3）emerald；失败（4）red；其余 neutral
  if (code === 2) return 'bg-amber-500/15 text-amber-400 border-amber-500/30';
  if (code === 3) return 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30';
  if (code === 4) return 'bg-red-500/15 text-red-400 border-red-500/30';
  return 'bg-neutral-800 text-neutral-400 border-neutral-700';
}
