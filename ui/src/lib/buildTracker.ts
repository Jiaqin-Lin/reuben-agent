import type { DocumentTaskLogQueryVo } from '../types/document';
import { TaskStage } from '../types/enums';

export interface BuildStageItem {
  code: number;
  order: string;
  label: string;
  description: string;
  status: 'pending' | 'current' | 'completed' | 'failed';
  statusLabel: string;
}

/** 索引构建 4 阶段（stage code 5-8） */
export const BUILD_STAGE_LIBRARY: Omit<BuildStageItem, 'status' | 'statusLabel'>[] = [
  { code: 5, order: '01', label: '切块执行', description: '按照当前策略链路生成原始 chunk' },
  { code: 6, order: '02', label: '切块后处理', description: '清洗空块并整理最终可入库片段' },
  { code: 7, order: '03', label: '向量化', description: '生成 embedding 并写入 PGVector' },
  { code: 8, order: '04', label: '入库完成', description: '回写状态并将本次索引标记为可用' },
];

const BUILD_STAGE_CODES = new Set(BUILD_STAGE_LIBRARY.map((s) => s.code));

/** 是否处于构建在途状态 */
export function computeBuildInFlight(opts: {
  buildLoading?: boolean;
  taskStatus?: number;
  indexStatus?: number;
  latestTaskType?: number;
  latestTaskStatus?: number;
}): boolean {
  const { buildLoading, taskStatus, indexStatus, latestTaskType, latestTaskStatus } = opts;
  if (buildLoading) return true;
  if (taskStatus === 1 || taskStatus === 2) return true;
  if (indexStatus === 2) return true;
  if (latestTaskType === 2 && (latestTaskStatus === 1 || latestTaskStatus === 2)) return true;
  return false;
}

/** 推导 4 阶段状态（移植 Vue buildStageItems computed） */
export function computeBuildStageItems(opts: {
  buildSnapshot: DocumentTaskLogQueryVo | null;
  inFlight: boolean;
}): BuildStageItem[] {
  const { buildSnapshot, inFlight } = opts;
  const taskStatus = buildSnapshot?.taskStatus;
  const currentStage = buildSnapshot?.currentStage;
  const activeStage =
    currentStage && BUILD_STAGE_CODES.has(currentStage)
      ? currentStage
      : inFlight
        ? BUILD_STAGE_LIBRARY[0].code
        : 0;

  const logs = buildSnapshot?.logs ?? [];
  const completedStages = new Set<number>();
  const failedStages = new Set<number>();
  const touchedStages = new Set<number>();
  for (const log of logs) {
    if (!BUILD_STAGE_CODES.has(log.stageType)) continue;
    touchedStages.add(log.stageType);
    if (log.eventType === 2) completedStages.add(log.stageType);
    if (log.eventType === 3) failedStages.add(log.stageType);
  }

  const currentIndex = BUILD_STAGE_LIBRARY.findIndex((s) => s.code === activeStage);

  return BUILD_STAGE_LIBRARY.map((stage, index) => {
    let status: BuildStageItem['status'] = 'pending';
    let statusLabel = '等待执行';
    if (failedStages.has(stage.code) || (taskStatus === 4 && activeStage === stage.code)) {
      status = 'failed';
      statusLabel = '执行失败';
    } else if (taskStatus === 3) {
      status = 'completed';
      statusLabel = '已完成';
    } else if (
      (taskStatus === 1 || taskStatus === 2 || (inFlight && !currentStage)) &&
      activeStage === stage.code
    ) {
      status = 'current';
      statusLabel = '当前阶段';
    } else if (completedStages.has(stage.code) || ((taskStatus === 1 || taskStatus === 2) && currentIndex > index)) {
      status = 'completed';
      statusLabel = '已完成';
    } else if (touchedStages.has(stage.code)) {
      status = 'completed';
      statusLabel = '已完成';
    }
    return { ...stage, status, statusLabel };
  });
}

export function computeActiveStageLabel(
  items: BuildStageItem[],
  opts: { inFlight: boolean; currentStage?: number; taskStatus?: number; indexStatus?: number },
): string {
  const current = items.find((i) => i.status === 'current');
  if (current) return current.label;
  if (opts.inFlight) {
    return (opts.currentStage && TaskStage[opts.currentStage]) || BUILD_STAGE_LIBRARY[0].label;
  }
  if (opts.taskStatus === 3 || opts.indexStatus === 3) {
    return BUILD_STAGE_LIBRARY[BUILD_STAGE_LIBRARY.length - 1].label;
  }
  return '';
}
