import { useState } from 'react';
import { ArrowRight } from '@phosphor-icons/react';
import { cn } from '../../../lib/cn';
import { confirmStrategy, buildIndex } from '../../../api/document';
import { useToast } from '../../shared/Toast';
import { buildPipelineStepPayload } from '../../../lib/documentStrategy';
import type {
  DocumentDetailVo,
  DocumentStrategyPlanVo,
} from '../../../types/document';
import { BuildTracker } from './BuildTracker';
import type { BuildStageItem } from '../../../lib/buildTracker';

interface Props {
  document: DocumentDetailVo;
  plan: DocumentStrategyPlanVo | null;
  parentTypes: number[];
  childTypes: number[];
  adjustNote: string;
  dirty: boolean;
  inFlight: boolean;
  stages: BuildStageItem[];
  activeStageLabel: string;
  buildSnapshotTaskId?: string;
  buildSnapshotTaskStatus?: number;
  buildSnapshotCostMillis?: number;
  onAfterAction: () => void;
}

export function ExecutionSection({
  document: doc,
  plan,
  parentTypes,
  childTypes,
  adjustNote,
  dirty,
  inFlight,
  stages,
  activeStageLabel,
  buildSnapshotTaskId,
  buildSnapshotTaskStatus,
  buildSnapshotCostMillis,
  onAfterAction,
}: Props) {
  const [confirming, setConfirming] = useState(false);
  const [building, setBuilding] = useState(false);
  const { toast } = useToast();

  const hasSelected = parentTypes.length > 0 && childTypes.length > 0;
  const hasConfirmed = Boolean(doc.currentPlanId) && doc.strategyStatus === 3;

  const confirmBadge = confirming
    ? '确认中'
    : !hasSelected
      ? '请先选择'
      : hasConfirmed && !dirty
        ? '已确认'
        : dirty
          ? '待重新确认'
          : '待确认';

  const buildBadge = building
    ? '启动中'
    : inFlight
      ? activeStageLabel || '执行中'
      : !hasSelected || !hasConfirmed
        ? '已锁定'
        : dirty
          ? '待重新确认'
          : doc.indexStatus === 3
            ? '可再次执行'
            : '已解锁';

  const canConfirm = hasSelected && !confirming && !inFlight && (!hasConfirmed || dirty);
  const canBuild =
    hasSelected && hasConfirmed && !dirty && !inFlight && !building;

  const handleConfirm = async () => {
    if (!plan) {
      toast('当前没有可确认的策略方案', 'error');
      return;
    }
    if (!hasSelected) {
      toast('请先分别配置父块和子块流水线', 'error');
      return;
    }
    if (inFlight) {
      toast('索引构建执行中，暂时不能确认策略方案', 'error');
      return;
    }
    setConfirming(true);
    try {
      const vo = await confirmStrategy({
        documentId: doc.documentId,
        planId: plan.planId,
        basePlanId: plan.planId,
        adjustNote: adjustNote || undefined,
        parentSteps: buildPipelineStepPayload(parentTypes),
        childSteps: buildPipelineStepPayload(childTypes),
      });
      toast(`策略已确认 (Task: ${vo.taskId})，索引构建已投递`, 'success');
      onAfterAction();
    } catch (e) {
      toast(e instanceof Error ? e.message : '确认失败', 'error');
    } finally {
      setConfirming(false);
    }
  };

  const handleBuild = async () => {
    if (!doc.currentPlanId) {
      toast('请先确认策略方案', 'error');
      return;
    }
    if (dirty) {
      toast('当前有未确认的改动，请先重新确认', 'error');
      return;
    }
    if (inFlight) {
      toast('索引构建正在执行中', 'info');
      return;
    }
    setBuilding(true);
    try {
      const vo = await buildIndex({
        documentId: doc.documentId,
        planId: doc.currentPlanId,
      });
      toast(`索引任务 ${vo.taskId} 已创建`, 'success');
      onAfterAction();
    } catch (e) {
      toast(e instanceof Error ? e.message : '构建失败', 'error');
    } finally {
      setBuilding(false);
    }
  };

  return (
    <section className="space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <SummaryCard label="策略确认" value={confirmBadge} />
        <SummaryCard label="构建执行" value={buildBadge} />
        <SummaryCard
          label="当前任务"
          value={buildSnapshotTaskId ?? doc.latestTaskId ?? '-'}
          hint={activeStageLabel || '当前没有正在执行的构建任务'}
        />
      </div>

      <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-4">
        <div className="flex flex-col md:flex-row md:items-end gap-4">
          <div className="flex-1">
            <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600 mb-1">
              01 · 先确认策略方案
            </p>
            <p className="text-xs text-neutral-400 leading-relaxed">
              {dirty
                ? '你调整了双流水线或补充了说明，确认后会落库为新方案版本并触发构建。'
                : hasConfirmed
                  ? '当前方案已确认，可直接执行或调整后重新确认。'
                  : '推荐双流水线已生成，确认后系统将自动构建索引。'}
            </p>
          </div>
          <button
            onClick={handleConfirm}
            disabled={!canConfirm}
            className={cn(
              'shrink-0 px-4 py-2 text-xs font-medium rounded-lg transition-colors',
              canConfirm
                ? 'bg-amber-500 text-black hover:bg-amber-400'
                : 'bg-neutral-800 text-neutral-600 cursor-not-allowed',
            )}
          >
            {confirmBadge}
          </button>
        </div>

        <div className="flex flex-col md:flex-row md:items-end gap-4 mt-4 pt-4 border-t border-neutral-800/60">
          <div className="flex-1">
            <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600 mb-1">
              02 · 执行索引构建
            </p>
            <p className="text-xs text-neutral-400 leading-relaxed">
              {inFlight
                ? '系统正在执行构建，页面已锁定并实时刷新进度。'
                : hasConfirmed
                  ? '可手动重新发起索引构建。'
                  : '确认完成后将自动构建；如需手动重建，先确认策略。'}
            </p>
          </div>
          <button
            onClick={handleBuild}
            disabled={!canBuild}
            className={cn(
              'shrink-0 flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded-lg transition-colors',
              canBuild
                ? 'bg-amber-500 text-black hover:bg-amber-400'
                : 'bg-neutral-800 text-neutral-600 cursor-not-allowed',
            )}
          >
            {buildBadge}
            <ArrowRight className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <BuildTracker
        stages={stages}
        taskId={buildSnapshotTaskId}
        taskStatus={buildSnapshotTaskStatus}
        costMillis={buildSnapshotCostMillis}
        inFlight={inFlight}
      />
    </section>
  );
}

function SummaryCard({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string;
}) {
  return (
    <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-4">
      <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600 mb-1">
        {label}
      </p>
      <p className="text-sm font-semibold text-neutral-100 truncate">{value}</p>
      {hint && <p className="text-[11px] text-neutral-500 mt-1 truncate">{hint}</p>}
    </div>
  );
}
