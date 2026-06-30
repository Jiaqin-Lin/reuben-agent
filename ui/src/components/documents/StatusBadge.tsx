import { cn } from '../../lib/cn';
import {
  ParseStatus,
  StrategyStatus,
  IndexStatus,
  PlanStatus,
  VectorStatus,
  TaskStatus,
  getStatusStyle,
  type StatusType,
} from '../../types/enums';

const LABELS: Record<StatusType, Record<number, string>> = {
  parse: ParseStatus,
  strategy: StrategyStatus,
  plan: PlanStatus,
  index: IndexStatus,
  vector: VectorStatus,
  task: TaskStatus,
};

interface Props {
  type: StatusType;
  code: number;
  className?: string;
}

export function StatusBadge({ type, code, className }: Props) {
  const label = LABELS[type]?.[code] ?? `未知(${code})`;
  const style = getStatusStyle(type, code);
  const isActive = code === 2;

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono border',
        style,
        isActive && 'animate-pulse',
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
