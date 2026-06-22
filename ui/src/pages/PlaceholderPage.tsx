import { EmptyState } from '../components/shared/EmptyState';

export function PlaceholderPage({ label }: { label: string }) {
  return (
    <div>
      <h1 className="text-lg font-semibold text-neutral-100 mb-6">{label}</h1>
      <div className="border border-neutral-800 rounded-lg bg-neutral-900/30">
        <EmptyState
          title="即将上线"
          description={`${label}功能正在开发中，敬请期待。`}
        />
      </div>
    </div>
  );
}
