import { useMemo, useState } from 'react';
import { ArrowLeft } from '@phosphor-icons/react';
import { useNavigate, useParams } from 'react-router-dom';
import { useDocumentWorkbench } from './workbench/useDocumentWorkbench';
import {
  WorkbenchNav,
  type WorkbenchSectionKey,
  type WorkbenchNavItem,
} from './workbench/WorkbenchNav';
import { OverviewSection } from './workbench/OverviewSection';
import { StrategyTuningSection } from './workbench/StrategyTuningSection';
import { ExecutionSection } from './workbench/ExecutionSection';
import { ChunkWorkbench } from './workbench/ChunkWorkbench';
import { TasksSection } from './workbench/TasksSection';
import { BuildBlockingOverlay } from './workbench/BuildBlockingOverlay';
import {
  computeBuildInFlight,
  computeBuildStageItems,
  computeActiveStageLabel,
} from '../../lib/buildTracker';
import {
  buildStrategySignature,
  extractPipelineStrategyTypes,
} from '../../lib/documentStrategy';

export function DocumentDetail() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const docId = id!;
  const backPath = location.pathname.startsWith('/admin') ? '/admin/documents' : '/documents';

  const { document, plans, buildSnapshot, loading, error, refetch } =
    useDocumentWorkbench(docId);

  const [active, setActive] = useState<WorkbenchSectionKey>('overview');
  const [parentTypes, setParentTypes] = useState<number[]>([]);
  const [childTypes, setChildTypes] = useState<number[]>([]);
  const [adjustNote, setAdjustNote] = useState('');

  // 取最新方案（WAIT_CONFIRM 优先，否则取第一条）
  const plan = useMemo(() => {
    if (!plans || plans.length === 0) return null;
    return (
      plans.find((p) => p.planStatus === 1) ?? plans[0]
    );
  }, [plans]);

  const recommendedParent = extractPipelineStrategyTypes(plan, 'parent');
  const recommendedChild = extractPipelineStrategyTypes(plan, 'child');
  const dirty =
    buildStrategySignature(parentTypes) !== buildStrategySignature(recommendedParent) ||
    buildStrategySignature(childTypes) !== buildStrategySignature(recommendedChild) ||
    adjustNote.trim().length > 0;

  const inFlight = document
    ? computeBuildInFlight({
        taskStatus: buildSnapshot?.taskStatus,
        indexStatus: document.indexStatus,
        latestTaskType: document.latestTaskType,
        latestTaskStatus: document.latestTaskStatus,
      })
    : false;

  const stages = useMemo(
    () => computeBuildStageItems({ buildSnapshot, inFlight }),
    [buildSnapshot, inFlight],
  );
  const activeStageLabel = document
    ? computeActiveStageLabel(stages, {
        inFlight,
        currentStage: buildSnapshot?.currentStage,
        taskStatus: buildSnapshot?.taskStatus,
        indexStatus: document.indexStatus,
      })
    : '';

  const planReady = Boolean(plan && plan.steps && plan.steps.length > 0);

  const navItems: WorkbenchNavItem[] = [
    { key: 'overview', step: '00', label: '文档概览', caption: '阶段与关键指标', status: workflowLabel(document?.indexStatus) },
    { key: 'strategy', step: '01', label: '配置策略', caption: '推荐 + 双流水线调整', status: planReady ? (dirty ? '待重新确认' : '已就绪') : '等待推荐' },
    { key: 'execution', step: '02', label: '确认并构建', caption: '确认方案并执行索引', status: inFlight ? activeStageLabel || '执行中' : (document?.strategyStatus === 3 ? '已确认' : '待确认') },
    { key: 'chunk', step: '03', label: '验证 Chunk', caption: '检查分块结果', status: document?.indexStatus === 3 ? `${document?.indexStatus ?? 0}` : '暂无' },
    { key: 'tasks', step: '04', label: '查看任务', caption: '复盘日志与时间线', status: buildSnapshot?.logs?.length ? `${buildSnapshot.logs.length} 条` : '暂无' },
  ];

  if (loading && !document) {
    return (
      <div className="space-y-6 animate-pulse">
        <div className="h-6 w-48 bg-neutral-800 rounded" />
        <div className="grid grid-cols-4 gap-3">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-16 bg-neutral-800 rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  if (error || !document) {
    return (
      <div className="text-center py-20">
        <p className="text-red-400 text-sm">{error ?? '文档不存在'}</p>
        <button
          onClick={() => nav(backPath)}
          className="mt-4 text-sm text-neutral-400 hover:text-neutral-200 transition-colors"
        >
          返回文档列表
        </button>
      </div>
    );
  }

  return (
    <div className="relative">
      {/* Header */}
      <div className="flex items-center gap-3 mb-5">
        <button
          onClick={() => nav(backPath)}
          className="p-1.5 rounded-md hover:bg-neutral-800 text-neutral-500 hover:text-neutral-300 transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div className="flex-1 min-w-0">
          <h1 className="text-lg font-semibold text-neutral-100 truncate">
            {document.documentName}
          </h1>
          <p className="text-xs text-neutral-500 mt-0.5">文档工作台</p>
        </div>
      </div>

      <WorkbenchNav items={navItems} active={active} onSelect={setActive} />

      <div className="space-y-8">
        {active === 'overview' && (
          <OverviewSection document={document} onRefresh={refetch} refreshing={loading} />
        )}

        {active === 'strategy' && (
          <StrategyTuningSection
            plan={plan}
            planLoading={false}
            parentTypes={parentTypes}
            childTypes={childTypes}
            adjustNote={adjustNote}
            onParentTypes={setParentTypes}
            onChildTypes={setChildTypes}
            onAdjustNote={setAdjustNote}
          />
        )}

        {active === 'execution' && (
          <ExecutionSection
            document={document}
            plan={plan}
            parentTypes={parentTypes}
            childTypes={childTypes}
            adjustNote={adjustNote}
            dirty={dirty}
            inFlight={inFlight}
            stages={stages}
            activeStageLabel={activeStageLabel}
            buildSnapshotTaskId={buildSnapshot?.taskId ?? document.latestTaskId}
            buildSnapshotTaskStatus={buildSnapshot?.taskStatus}
            buildSnapshotCostMillis={buildSnapshot?.costMillis}
            onAfterAction={refetch}
          />
        )}

        {active === 'chunk' && (
          <ChunkWorkbench documentId={docId} indexReady={document.indexStatus === 3} />
        )}

        {active === 'tasks' && (
          <TasksSection documentId={docId} latestTaskId={document.latestTaskId ?? buildSnapshot?.taskId} />
        )}
      </div>

      {/* 构建期间锁页遮罩 */}
      <BuildBlockingOverlay
        open={inFlight}
        title={inFlight ? `正在${activeStageLabel || '构建索引'}` : ''}
        description={
          inFlight
            ? `当前执行到「${activeStageLabel || '索引构建中'}」，页面已暂时锁定并会实时刷新步骤进度。`
            : ''
        }
        taskId={buildSnapshot?.taskId ?? document.latestIndexTaskId}
        activeStageLabel={activeStageLabel}
        stages={stages}
      />
    </div>
  );
}

function workflowLabel(indexStatus?: number): string {
  if (indexStatus == null) return '-';
  if (indexStatus === 2) return '构建中';
  if (indexStatus === 3) return '已索引';
  if (indexStatus === 4) return '失败';
  return '待构建';
}
