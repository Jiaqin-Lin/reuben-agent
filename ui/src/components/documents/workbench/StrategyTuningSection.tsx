import { useEffect } from 'react';
import { CheckCircle, ArrowUp, ArrowDown } from '@phosphor-icons/react';
import { cn } from '../../../lib/cn';
import { EmptyState } from '../../shared/EmptyState';
import {
  STRATEGY_LIBRARY,
  STRATEGY_PIPELINE_LIBRARY,
  resolvePipelineSteps,
  extractPipelineStrategyTypes,
  buildStrategyPreview,
  buildStrategySignature,
  strategyLabel,
  type PreviewItem,
} from '../../../lib/documentStrategy';
import type { DocumentStrategyPlanVo } from '../../../types/document';

interface Props {
  plan: DocumentStrategyPlanVo | null;
  planLoading: boolean;
  parentTypes: number[];
  childTypes: number[];
  adjustNote: string;
  onParentTypes: (types: number[]) => void;
  onChildTypes: (types: number[]) => void;
  onAdjustNote: (note: string) => void;
}

export interface StrategyTuningState {
  parentTypes: number[];
  childTypes: number[];
  adjustNote: string;
  dirty: boolean;
}

export function StrategyTuningSection({
  plan,
  planLoading,
  parentTypes,
  childTypes,
  adjustNote,
  onParentTypes,
  onChildTypes,
  onAdjustNote,
}: Props) {
  const recommendedParent = extractPipelineStrategyTypes(plan, 'parent');
  const recommendedChild = extractPipelineStrategyTypes(plan, 'child');
  const confirmedParentSig = buildStrategySignature(recommendedParent);
  const confirmedChildSig = buildStrategySignature(recommendedChild);

  // 从 plan 步骤初始化本地调优态
  useEffect(() => {
    if (plan) {
      onParentTypes(extractPipelineStrategyTypes(plan, 'parent'));
      onChildTypes(extractPipelineStrategyTypes(plan, 'child'));
      onAdjustNote('');
    }
  }, [plan?.planId]); // eslint-disable-line react-hooks/exhaustive-deps

  const dirty =
    buildStrategySignature(parentTypes) !== confirmedParentSig ||
    buildStrategySignature(childTypes) !== confirmedChildSig ||
    adjustNote.trim().length > 0;

  const toggleStrategy = (type: number, key: 'parent' | 'child') => {
    const setter = key === 'parent' ? onParentTypes : onChildTypes;
    const current = key === 'parent' ? parentTypes : childTypes;
    if (current.includes(type)) {
      setter(current.filter((t) => t !== type));
    } else {
      setter([...current, type]);
    }
  };

  const moveStrategy = (type: number, direction: -1 | 1, key: 'parent' | 'child') => {
    const setter = key === 'parent' ? onParentTypes : onChildTypes;
    const current = key === 'parent' ? parentTypes : childTypes;
    const index = current.indexOf(type);
    const target = index + direction;
    if (target < 0 || target >= current.length) return;
    const next = [...current];
    [next[index], next[target]] = [next[target], next[index]];
    setter(next);
  };

  if (planLoading) {
    return <div className="h-32 rounded-lg bg-neutral-900/30 border border-neutral-800 animate-pulse" />;
  }

  if (!plan || !plan.steps || plan.steps.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-800 bg-neutral-900/30">
        <EmptyState
          title="暂无策略方案"
          description="文档解析完成后系统将自动推荐索引策略，可点击刷新查看"
        />
      </div>
    );
  }

  return (
    <section className="space-y-5">
      {plan.recommendReason && (
        <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-4">
          <p className="text-[11px] font-mono uppercase tracking-wider text-amber-400/70 mb-1">
            推荐说明
          </p>
          <p className="text-xs text-neutral-400 leading-relaxed">{plan.recommendReason}</p>
        </div>
      )}

      {/* 推荐双流水线展示 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {STRATEGY_PIPELINE_LIBRARY.map((pipeline) => {
          const steps = resolvePipelineSteps(plan, pipeline.key);
          return (
            <div key={`rec-${pipeline.key}`} className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-4">
              <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600 mb-2">
                {pipeline.label} · 推荐
              </p>
              {steps.length === 0 ? (
                <p className="text-xs text-neutral-600">暂无推荐步骤</p>
              ) : (
                <ol className="space-y-2">
                  {steps.map((s, i) => (
                    <li key={s.stepId} className="flex items-start gap-2.5">
                      <span className="text-[10px] font-mono text-amber-500 mt-0.5">
                        {String(s.stepNo).padStart(2, '0')}
                      </span>
                      <div className="flex-1 min-w-0">
                        <p className="text-xs font-medium text-neutral-200">
                          {strategyLabel(s.strategyType)}
                        </p>
                        {s.recommendReason && (
                          <p className="text-[11px] text-neutral-500 mt-0.5">{s.recommendReason}</p>
                        )}
                      </div>
                      {i < steps.length - 1 && (
                        <span className="text-neutral-700 text-xs">↓</span>
                      )}
                    </li>
                  ))}
                </ol>
              )}
            </div>
          );
        })}
      </div>

      {/* 调优工作台 */}
      <div className="rounded-lg border border-neutral-800 bg-neutral-900/20 p-4">
        <div className="flex items-center justify-between mb-3">
          <div>
            <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600">
              Adjustment Workspace
            </p>
            <p className="text-sm font-medium text-neutral-200 mt-0.5">双流水线调整</p>
          </div>
          {dirty && (
            <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/15 text-amber-400">
              待重新确认
            </span>
          )}
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {STRATEGY_PIPELINE_LIBRARY.map((pipeline) => {
            const selected = pipeline.key === 'parent' ? parentTypes : childTypes;
            const preview = buildStrategyPreview(selected);
            return (
              <div key={`edit-${pipeline.key}`}>
                <p className="text-xs text-neutral-400 mb-2">{pipeline.label}</p>

                {/* 当前配置 + 上下移 */}
                <div className="space-y-1.5 mb-3 min-h-[2rem]">
                  {preview.length === 0 ? (
                    <p className="text-[11px] text-neutral-600 italic py-2">
                      至少选择一个拆分策略
                    </p>
                  ) : (
                    preview.map((item: PreviewItem) => (
                      <div
                        key={item.type}
                        className="flex items-center gap-2 rounded-md border border-neutral-800 bg-neutral-900/50 px-2.5 py-1.5"
                      >
                        <span className="text-[10px] font-mono text-amber-500">{item.order}</span>
                        <span className="text-xs text-neutral-200 flex-1">{item.label}</span>
                        <button
                          onClick={() => moveStrategy(item.type, -1, pipeline.key)}
                          disabled={item.index === 0}
                          className="p-0.5 rounded text-neutral-500 hover:text-neutral-200 hover:bg-neutral-800 disabled:opacity-30 disabled:cursor-not-allowed"
                        >
                          <ArrowUp className="w-3.5 h-3.5" />
                        </button>
                        <button
                          onClick={() => moveStrategy(item.type, 1, pipeline.key)}
                          disabled={item.index === preview.length - 1}
                          className="p-0.5 rounded text-neutral-500 hover:text-neutral-200 hover:bg-neutral-800 disabled:opacity-30 disabled:cursor-not-allowed"
                        >
                          <ArrowDown className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    ))
                  )}
                </div>

                {/* 策略 chip 选择器 */}
                <div className="flex flex-wrap gap-1.5 mb-2">
                  {STRATEGY_LIBRARY.map((item) => {
                    const active = selected.includes(item.type);
                    return (
                      <button
                        key={item.type}
                        onClick={() => toggleStrategy(item.type, pipeline.key)}
                        className={cn(
                          'flex items-center gap-1 px-2 py-1 rounded-md text-[11px] border transition-colors',
                          active
                            ? 'border-amber-500/40 bg-amber-500/10 text-amber-300'
                            : 'border-neutral-800 bg-neutral-900/50 text-neutral-400 hover:border-neutral-700',
                        )}
                      >
                        {active && <CheckCircle className="w-3 h-3" weight="fill" />}
                        {item.label}
                      </button>
                    );
                  })}
                </div>

                {/* 最终提交顺序预览 */}
                <div className="rounded-md border border-neutral-800/60 bg-neutral-950/40 px-2.5 py-1.5">
                  <p className="text-[10px] font-mono text-neutral-600 mb-1">最终提交顺序</p>
                  {preview.length === 0 ? (
                    <p className="text-[11px] text-neutral-600">尚未选中策略</p>
                  ) : (
                    <p className="text-[11px] text-neutral-300">
                      {preview.map((p) => strategyLabel(p.type)).join(' → ')}
                    </p>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        <input
          value={adjustNote}
          onChange={(e) => onAdjustNote(e.target.value)}
          placeholder="补充说明，例如：增加大模型智能切块用于复杂段落"
          className="mt-4 w-full px-3 py-2 rounded-lg bg-neutral-900 border border-neutral-800 text-sm text-neutral-100 placeholder:text-neutral-600 focus:outline-none focus:border-amber-500/50 transition-colors"
        />
      </div>
    </section>
  );
}
