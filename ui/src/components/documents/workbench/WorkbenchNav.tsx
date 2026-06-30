import { cn } from '../../../lib/cn';

export type WorkbenchSectionKey = 'overview' | 'strategy' | 'execution' | 'chunk' | 'tasks';

export interface WorkbenchNavItem {
  key: WorkbenchSectionKey;
  step: string;
  label: string;
  caption: string;
  status: string;
}

interface Props {
  items: WorkbenchNavItem[];
  active: WorkbenchSectionKey;
  onSelect: (key: WorkbenchSectionKey) => void;
}

export function WorkbenchNav({ items, active, onSelect }: Props) {
  return (
    <nav className="flex gap-1 overflow-x-auto pb-1 mb-6 border-b border-neutral-800">
      {items.map((item) => {
        const isActive = active === item.key;
        return (
          <button
            key={item.key}
            onClick={() => onSelect(item.key)}
            className={cn(
              'group flex items-center gap-2.5 px-3.5 py-2.5 rounded-t-md whitespace-nowrap transition-colors border-b-2 -mb-px',
              isActive
                ? 'border-amber-500 bg-neutral-900/40'
                : 'border-transparent hover:bg-neutral-900/30',
            )}
          >
            <span
              className={cn(
                'text-[10px] font-mono px-1.5 py-0.5 rounded',
                isActive ? 'bg-amber-500/15 text-amber-400' : 'bg-neutral-800 text-neutral-500',
              )}
            >
              {item.step}
            </span>
            <span className="text-left">
              <span
                className={cn(
                  'block text-xs font-medium',
                  isActive ? 'text-neutral-100' : 'text-neutral-400',
                )}
              >
                {item.label}
              </span>
              <span className="block text-[10px] text-neutral-600">{item.caption}</span>
            </span>
            <span
              className={cn(
                'text-[10px] font-mono px-1.5 py-0.5 rounded ml-1',
                isActive
                  ? 'bg-neutral-800 text-neutral-300'
                  : 'bg-neutral-900 text-neutral-600',
              )}
            >
              {item.status}
            </span>
          </button>
        );
      })}
    </nav>
  );
}
