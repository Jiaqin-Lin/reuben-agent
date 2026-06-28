import { useCallback, useEffect, useState } from 'react';
import { House, ChatCircle, Files, Database, Warning, CircleNotch } from '@phosphor-icons/react';
import type { Icon } from '@phosphor-icons/react';
import { fetchDashboardMetrics, type DashboardMetric } from '../../api/admin';
import { getDocument } from '../../api/document';
import { STORAGE_KEY } from '../../lib/constants';
import type { StoredDocument, DocumentDetailVo } from '../../types/document';
import { ApiError } from '../../types/api';
import { AdminPage } from '../../components/admin/AdminLayout';

interface MetricCardProps {
  icon: Icon;
  label: string;
  value: string | number;
  hint?: string;
  tone?: DashboardMetric['tone'];
  loading?: boolean;
}

const TONE_TEXT: Record<NonNullable<DashboardMetric['tone']>, string> = {
  success: 'text-emerald-400',
  warning: 'text-amber-400',
  danger: 'text-red-400',
  neutral: 'text-neutral-100',
};

function MetricCard({ icon: Icon, label, value, hint, tone, loading }: MetricCardProps) {
  return (
    <div className="p-5 rounded-xl border border-neutral-800 bg-neutral-900/40">
      <div className="flex items-center justify-between">
        <span className="text-xs text-neutral-500">{label}</span>
        <Icon className={`w-4 h-4 ${tone ? TONE_TEXT[tone] : 'text-amber-400'}`} weight="regular" />
      </div>
      {loading ? (
        <div className="mt-3 flex items-center gap-2 text-neutral-500">
          <CircleNotch className="w-4 h-4 animate-spin" />
          <span className="text-xs">加载中</span>
        </div>
      ) : (
        <p className={`mt-3 text-2xl font-semibold ${tone ? TONE_TEXT[tone] : 'text-neutral-100'}`}>
          {value}
        </p>
      )}
      {hint && <p className="mt-1 text-[11px] text-neutral-600">{hint}</p>}
    </div>
  );
}

export function AdminDashboardPage() {
  const [sessionCount, setSessionCount] = useState<DashboardMetric | null>(null);
  const [docCount, setDocCount] = useState<DashboardMetric | null>(null);
  const [indexRate, setIndexRate] = useState<DashboardMetric | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);

    // 会话总数 —— 直接走 dashboard 聚合（内部组合 /chat/session/list）
    try {
      const metrics = await fetchDashboardMetrics();
      setSessionCount(metrics[0] ?? null);
    } catch (e) {
      setSessionCount({
        label: '会话总数',
        value: '-',
        hint: e instanceof ApiError ? e.message : '加载失败',
        tone: 'danger',
      });
    }

    // 文档总数 / 索引成功率 —— 从 localStorage 已接入文档聚合（与文档列表同源）
    try {
      const stored: StoredDocument[] = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]');
      const details = await Promise.allSettled(stored.map((s) => getDocument(s.documentId)));
      const docs = details
        .filter((r): r is PromiseFulfilledResult<DocumentDetailVo> => r.status === 'fulfilled')
        .map((r) => r.value);

      setDocCount({
        label: '文档总数',
        value: docs.length,
        hint: '已接入文档',
      });

      const indexed = docs.filter((d) => d.indexStatus === 3).length;
      const finished = docs.filter((d) => d.indexStatus >= 3).length;
      const rate = finished > 0 ? Math.round((indexed / finished) * 100) : null;
      setIndexRate({
        label: '索引成功率',
        value: rate == null ? '-' : `${rate}%`,
        hint: `${indexed} 已索引 / ${finished} 已结束`,
        tone: rate == null ? 'neutral' : rate >= 80 ? 'success' : rate >= 50 ? 'warning' : 'danger',
      });
    } catch {
      setDocCount({ label: '文档总数', value: '-', hint: '加载失败', tone: 'danger' });
      setIndexRate({ label: '索引成功率', value: '-', hint: '加载失败', tone: 'danger' });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <AdminPage>
      <div className="mb-6">
        <h1 className="text-lg font-semibold text-neutral-100">运营总览</h1>
        <p className="text-sm text-neutral-500 mt-0.5">查看会话、文档接入与索引运行的整体状态</p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          icon={ChatCircle}
          label={sessionCount?.label ?? '会话总数'}
          value={sessionCount?.value ?? '-'}
          hint={sessionCount?.hint}
          tone={sessionCount?.tone}
          loading={loading}
        />
        <MetricCard
          icon={Files}
          label={docCount?.label ?? '文档总数'}
          value={docCount?.value ?? '-'}
          hint={docCount?.hint}
          tone={docCount?.tone}
          loading={loading}
        />
        <MetricCard
          icon={Database}
          label={indexRate?.label ?? '索引成功率'}
          value={indexRate?.value ?? '-'}
          hint={indexRate?.hint}
          tone={indexRate?.tone}
          loading={loading}
        />
        <MetricCard
          icon={Warning}
          label="后端聚合 API"
          value="待补齐"
          hint="仪表盘当前组合现有列表接口计算"
          tone="warning"
        />
      </div>

      <div className="mt-6 p-5 rounded-xl border border-neutral-800 bg-neutral-900/40">
        <div className="flex items-center gap-2 mb-2">
          <House weight="fill" className="w-4 h-4 text-amber-400" />
          <h2 className="text-sm font-semibold text-neutral-200">说明</h2>
        </div>
        <p className="text-xs text-neutral-500 leading-relaxed">
          当前仪表盘指标由前端组合 <code className="px-1 py-0.5 rounded bg-neutral-800 text-neutral-300 font-mono">/chat/session/list</code> 与已接入文档详情计算得出。
          若需更丰富的聚合统计（今日会话数、平均延迟、各阶段 P50/P90 等），可在后端新增专门的 dashboard 聚合端点后在此对接。
        </p>
      </div>
    </AdminPage>
  );
}
