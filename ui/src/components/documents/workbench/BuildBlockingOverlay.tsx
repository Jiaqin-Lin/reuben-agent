import { motion, AnimatePresence } from 'motion/react';
import { cn } from '../../../lib/cn';
import type { BuildStageItem } from '../../../lib/buildTracker';

interface Props {
  open: boolean;
  title: string;
  description: string;
  taskId?: string;
  activeStageLabel?: string;
  stages: BuildStageItem[];
}

export function BuildBlockingOverlay({
  open,
  title,
  description,
  taskId,
  activeStageLabel,
  stages,
}: Props) {
  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.18 }}
        >
          <motion.div
            className="w-full max-w-lg rounded-xl border border-neutral-800 bg-neutral-950 p-6 shadow-2xl"
            initial={{ y: 12, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: 12, opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeOut' }}
          >
            <div className="flex items-start gap-3 mb-4">
              <span className="mt-1 w-4 h-4 rounded-full border-2 border-amber-400 border-t-transparent animate-spin shrink-0" />
              <div>
                <h3 className="text-sm font-semibold text-neutral-100">{title}</h3>
                <p className="text-xs text-neutral-400 mt-1 leading-relaxed">{description}</p>
              </div>
            </div>

            <div className="flex items-center gap-4 text-[11px] font-mono text-neutral-500 mb-4 pb-4 border-b border-neutral-800">
              <span>任务 {taskId ?? '创建中'}</span>
              <span>当前阶段 {activeStageLabel || '准备启动'}</span>
            </div>

            <div className="space-y-1.5 mb-4">
              {stages.map((stage) => (
                <div
                  key={stage.code}
                  className={cn(
                    'flex items-center gap-2.5 rounded-md border px-3 py-2',
                    stage.status === 'current' && 'border-amber-500/40 bg-amber-500/5',
                    stage.status === 'completed' && 'border-emerald-500/30 bg-emerald-500/5',
                    stage.status === 'failed' && 'border-red-500/40 bg-red-500/5',
                    stage.status === 'pending' && 'border-neutral-800 bg-neutral-900/40',
                  )}
                >
                  <span
                    className={cn(
                      'w-5 h-5 grid place-items-center rounded text-[10px] font-mono shrink-0',
                      stage.status === 'current' && 'bg-amber-500/20 text-amber-400',
                      stage.status === 'completed' && 'bg-emerald-500/20 text-emerald-400',
                      stage.status === 'failed' && 'bg-red-500/20 text-red-400',
                      stage.status === 'pending' && 'bg-neutral-800 text-neutral-600',
                    )}
                  >
                    {stage.status === 'current' ? (
                      <span className="w-2 h-2 rounded-full bg-current animate-pulse" />
                    ) : (
                      stage.order
                    )}
                  </span>
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-medium text-neutral-200">{stage.label}</p>
                    <p className="text-[10px] text-neutral-500">{stage.statusLabel}</p>
                  </div>
                </div>
              ))}
            </div>

            <p className="text-[11px] text-neutral-600 leading-relaxed">
              执行期间页面已暂时锁定，避免重复发起构建或误改当前策略链路。
            </p>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
