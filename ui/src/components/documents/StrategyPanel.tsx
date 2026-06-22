import { useState } from 'react';
import { CheckCircle, Lightbulb, Gear } from '@phosphor-icons/react';
import { confirmStrategy } from '../../api/document';
import { StatusBadge } from './StatusBadge';
import { EmptyState } from '../shared/EmptyState';
import { useToast } from '../shared/Toast';
import type { DocumentStrategyPlanVo } from '../../types/document';

interface Props {
  documentId: string;
  plans: DocumentStrategyPlanVo[];
  onConfirmed: () => void;
}

export function StrategyPanel({ documentId, plans, onConfirmed }: Props) {
  const [confirming, setConfirming] = useState<string | null>(null);
  const { toast } = useToast();

  const handleConfirm = async (planId: string) => {
    setConfirming(planId);
    try {
      const vo = await confirmStrategy({
        documentId,
        planId,
      });
      toast(`策略已确认 (Task: ${vo.taskId})`, 'success');
      onConfirmed();
    } catch (e) {
      toast(e instanceof Error ? e.message : '确认失败', 'error');
    } finally {
      setConfirming(null);
    }
  };

  if (plans.length === 0) {
    return (
      <div>
        <h2 className="text-sm font-medium text-neutral-400 uppercase tracking-wider mb-3">
          索引策略
        </h2>
        <div className="border border-neutral-800 rounded-lg bg-neutral-900/30">
          <EmptyState
            icon={Lightbulb}
            title="暂无策略方案"
            description="文档解析完成后系统将自动推荐索引策略"
          />
        </div>
      </div>
    );
  }

  return (
    <div>
      <h2 className="text-sm font-medium text-neutral-400 uppercase tracking-wider mb-3">
        索引策略 ({plans.length} 个方案)
      </h2>
      <div className="space-y-3">
        {plans.map((plan) => {
          // planStatus: 1=待确认 2=已确认 3=已执行 4=已废弃
          const canConfirm = plan.planStatus === 1;
          const isConfirming = confirming === plan.planId;

          // Parse strategy snapshot for display
          let steps: string[] = [];
          try {
            // Format: "PARENT:1;CHILD:3,2" or similar
            const parts = plan.strategySnapshot?.split(';') ?? [];
            steps = parts.map((p) => p.trim()).filter(Boolean);
          } catch {
            steps = [plan.strategySnapshot ?? '未知'];
          }

          return (
            <div
              key={plan.planId}
              className="border border-neutral-800 rounded-lg bg-neutral-900/30 p-4"
            >
              <div className="flex items-start justify-between mb-3">
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-md bg-amber-500/10 flex items-center justify-center">
                    <Gear className="w-4 h-4 text-amber-400" weight="fill" />
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-neutral-200">
                        方案 v{plan.planVersion}
                      </span>
                      <StatusBadge type="plan" code={plan.planStatus} />
                    </div>
                    <p className="text-xs text-neutral-500 mt-0.5">
                      {plan.strategyCount} 个策略步骤
                    </p>
                  </div>
                </div>

                {canConfirm && (
                  <button
                    onClick={() => handleConfirm(plan.planId)}
                    disabled={isConfirming}
                    className="px-4 py-2 text-xs font-medium bg-amber-500 text-black rounded-lg hover:bg-amber-400 transition-colors disabled:opacity-50 disabled:cursor-not-allowed shrink-0"
                  >
                    {isConfirming ? '确认中...' : '确认策略'}
                  </button>
                )}

                {(plan.planStatus === 2 || plan.planStatus === 3) && (
                  <div className="flex items-center gap-1.5 text-xs text-emerald-400 font-medium">
                    <CheckCircle className="w-4 h-4" weight="fill" />
                    已确认
                  </div>
                )}
              </div>

              {/* Strategy steps */}
              <div className="flex flex-wrap gap-1.5 mb-3">
                {steps.map((step, i) => (
                  <span
                    key={i}
                    className="px-2 py-0.5 text-[10px] font-mono rounded bg-neutral-800 text-neutral-300"
                  >
                    {step}
                  </span>
                ))}
              </div>

              {/* Recommend reason */}
              {plan.recommendReason && (
                <p className="text-xs text-neutral-500 leading-relaxed">
                  {plan.recommendReason}
                </p>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
