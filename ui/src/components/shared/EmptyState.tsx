import { Clock } from '@phosphor-icons/react';

export function EmptyState({
  icon: Icon = Clock,
  title,
  description,
  action,
}: {
  icon?: typeof Clock;
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <div className="w-12 h-12 rounded-full bg-neutral-800 flex items-center justify-center mb-4">
        <Icon className="w-6 h-6 text-neutral-500" />
      </div>
      <h3 className="text-sm font-medium text-neutral-300 mb-1">{title}</h3>
      {description && (
        <p className="text-sm text-neutral-500 max-w-xs">{description}</p>
      )}
      {action && (
        <button
          onClick={action.onClick}
          className="mt-4 px-4 py-2 text-sm font-medium bg-amber-500 text-black rounded-lg hover:bg-amber-400 transition-colors"
        >
          {action.label}
        </button>
      )}
    </div>
  );
}
