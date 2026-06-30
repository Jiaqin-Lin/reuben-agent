import { cn } from '../../../lib/cn';
import { TaskStatus } from '../../../types/enums';
import type { BuildStageItem } from '../../../lib/buildTracker';

interface Props {
  stages: BuildStageItem[];
  taskId?: string;
  taskStatus?: number;
  costMillis?: number;
  inFlight: boolean;
}

export function BuildTracker({ stages, taskId, taskStatus, costMillis, inFlight }: Props) {
  return (
    <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-4">
      <div className="flex items-center justify-between mb-4">
        <div>
          <p className="text-sm font-medium text-neutral-200">索引构建轨迹</p>
          <p className="text-[11px] text-neutral-500 mt-0.5">
            {inFlight ? '系统正在执行构建，实时刷新进度' : '轨迹已保留'}
          </p>
        </div>
        <span
          className={cn(
            'text-[10px] font-mono px-2 py-0.5 rounded',
            inFlight
              ? 'bg-amber-500/15 text-amber-400 animate-pulse'
              : 'bg-neutral-800 text-neutral-500',
          )}
        >
          {inFlight ? '实时轮询中' : '已停止'}
        </span>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
        {stages.map((stage) => (
          <div
            key={stage.code}
            className={cn(
              'rounded-md border p-2.5',
              stage.status === 'current' && 'border-amber-500/40 bg-amber-500/5',
              stage.status === 'completed' && 'border-emerald-500/30 bg-emerald-500/5',
              stage.status === 'failed' && 'border-red-500/40 bg-red-500/5',
              stage.status === 'pending' && 'border-neutral-800 bg-neutral-900/40',
            )}
          >
            <div className="flex items-center gap-1.5 mb-1">
              <span
                className={cn(
                  'w-4 h-4 grid place-items-center rounded text-[10px] font-mono',
                  stage.status === 'current' && 'bg-amber-500/20 text-amber-400',
                  stage.status === 'completed' && 'bg-emerald-500/20 text-emerald-400',
                  stage.status === 'failed' && 'bg-red-500/20 text-red-400',
                  stage.status === 'pending' && 'bg-neutral-800 text-neutral-600',
                )}
              >
                {stage.status === 'current' ? (
                  <span className="w-1.5 h-1.5 rounded-full bg-current animate-pulse" />
                ) : (
                  stage.order
                )}
              </span>
              <span className="text-[11px] font-medium text-neutral-200">{stage.label}</span>
            </div>
            <p className="text-[10px] text-neutral-500 leading-tight">{stage.description}</p>
            <p
              className={cn(
                'text-[10px] font-mono mt-1',
                stage.status === 'current' && 'text-amber-400',
                stage.status === 'completed' && 'text-emerald-400',
                stage.status === 'failed' && 'text-red-400',
                stage.status === 'pending' && 'text-neutral-600',
              )}
            >
              {stage.statusLabel}
            </p>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-4 mt-3 pt-3 border-t border-neutral-800/60 text-[11px] font-mono text-neutral-500">
        <span>任务 {taskId ?? '-'}</span>
        <span>状态 {taskStatus != null ? TaskStatus[taskStatus] ?? '-' : '-'}</span>
        <span>耗时 {formatDuration(costMillis)}</span>
      </div>
    </div>
  );
}

function formatDuration(millis?: number): string {
  if (millis == null) return '-';
  if (millis < 1000) return `${millis}ms`;
  const s = millis / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  return `${Math.floor(s / 60)}m${Math.floor(s % 60)}s`;
}
