import { cn } from '../../lib/cn';

export type SharedStatusVariant = 'success' | 'processing' | 'danger' | 'waiting' | 'neutral';

interface Props {
  variant: SharedStatusVariant;
  label: string;
  pulse?: boolean;
  className?: string;
}

const VARIANT_STYLES: Record<SharedStatusVariant, string> = {
  success: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  processing: 'bg-amber-500/15 text-amber-400 border-amber-500/30',
  danger: 'bg-red-500/15 text-red-400 border-red-500/30',
  waiting: 'bg-neutral-800 text-neutral-300 border-neutral-700',
  neutral: 'bg-neutral-800/60 text-neutral-400 border-neutral-700',
};

/** 通用状态徽章 —— success/processing/danger/waiting 变体，处理中可脉冲。 */
export function SharedStatusBadge({ variant, label, pulse, className }: Props) {
  const isActive = variant === 'processing';
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono border',
        VARIANT_STYLES[variant],
        (pulse ?? isActive) && 'animate-pulse',
        className,
      )}
    >
      {isActive && (
        <span className="w-1.5 h-1.5 rounded-full bg-current shrink-0" />
      )}
      {label}
    </span>
  );
}
