import { useCallback, useEffect, useState } from 'react';
import {
  House,
  ChatCircle,
  Files,
  Database,
  CircleNotch,
  ArrowsClockwise,
  ArrowRight,
  CheckCircle,
} from '@phosphor-icons/react';
import type { Icon } from '@phosphor-icons/react';
import { useNavigate } from 'react-router-dom';
import { fetchDashboardMetrics, type DashboardMetric } from '../../api/admin';
import { pageQueryDocuments } from '../../api/document';
import type { DocumentListItemVo } from '../../types/document';
import { ApiError } from '../../types/api';
import { AdminPage } from '../../components/admin/AdminLayout';
import { StatusBadge } from '../../components/documents/StatusBadge';

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

const DEMO_FLOW: { title: string; desc: string; to: string }[] = [
  { title: '上传文档', desc: '在管理台上传 PDF / Word / Markdown 文档。', to: '/admin/documents' },
  { title: '查看系统推荐策略', desc: '观察结构切块、递归分块、语义分块和智能切块的组合。', to: '/admin/documents' },
  { title: '确认并构建索引', desc: '在推荐结果基础上补充或移除策略，再触发异步构建索引。', to: '/admin/documents' },
  { title: '做对话观测', desc: '查看真实会话在文档问答与开放式提问下的执行轨迹。', to: '/admin/observability' },
];

export function AdminDashboardPage() {
  const nav = useNavigate();
  const [sessionCount, setSessionCount] = useState<DashboardMetric | null>(null);
  const [docSummary, setDocSummary] = useState<{
    total: number;
    parseSuccess: number;
    strategyConfirmed: number;
    indexSuccess: number;
  } | null>(null);
  const [recentDocs, setRecentDocs] = useState<DocumentListItemVo[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setRefreshing(true);

    // 会话总数 —— 走 /chat/session/list 取 total
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

    // 文档聚合 —— 走 /document/page 拉首页，按 parseStatus/strategyStatus/indexStatus 统计
    try {
      const page = await pageQueryDocuments({ pageNo: 1, pageSize: 50 });
      const docs = page.records ?? [];
      setDocSummary({
        total: page.total ?? docs.length,
        parseSuccess: docs.filter((d) => d.parseStatus === 3).length,
        strategyConfirmed: docs.filter((d) => d.strategyStatus === 3).length,
        indexSuccess: docs.filter((d) => d.indexStatus === 3).length,
      });
      setRecentDocs(docs.slice(0, 6));
    } catch {
      setDocSummary({ total: 0, parseSuccess: 0, strategyConfirmed: 0, indexSuccess: 0 });
      setRecentDocs([]);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const indexRate =
    docSummary && docSummary.total > 0
      ? Math.round((docSummary.indexSuccess / docSummary.total) * 100)
      : null;

  return (
    <AdminPage>
      <div className="mb-6 flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold text-neutral-100">运营总览</h1>
          <p className="text-sm text-neutral-500 mt-0.5">查看会话、文档接入与索引运行的整体状态</p>
        </div>
        <button
          onClick={() => load()}
          disabled={refreshing}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-neutral-700 text-neutral-300 text-xs hover:bg-neutral-800 disabled:opacity-50"
        >
          {refreshing ? <CircleNotch className="w-3.5 h-3.5 animate-spin" /> : <ArrowsClockwise className="w-3.5 h-3.5" />}
          刷新
        </button>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          icon={Files}
          label="文档总数"
          value={docSummary?.total ?? '-'}
          hint="已接入管理台的文档记录"
          loading={loading}
        />
        <MetricCard
          icon={CheckCircle}
          label="解析成功"
          value={docSummary?.parseSuccess ?? '-'}
          hint="可进入策略确认阶段"
          tone={docSummary && docSummary.parseSuccess > 0 ? 'success' : 'neutral'}
          loading={loading}
        />
        <MetricCard
          icon={Database}
          label="索引成功率"
          value={indexRate == null ? '-' : `${indexRate}%`}
          hint={`${docSummary?.indexSuccess ?? 0} 已索引 / ${docSummary?.total ?? 0} 总数`}
          tone={indexRate == null ? 'neutral' : indexRate >= 80 ? 'success' : indexRate >= 50 ? 'warning' : 'danger'}
          loading={loading}
        />
        <MetricCard
          icon={ChatCircle}
          label={sessionCount?.label ?? '会话总数'}
          value={sessionCount?.value ?? '-'}
          hint={sessionCount?.hint ?? '历史对话会话'}
          tone={sessionCount?.tone}
          loading={loading}
        />
      </div>

      <div className="mt-6 grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* 建议演示路径 */}
        <div className="p-5 rounded-xl border border-neutral-800 bg-neutral-900/40">
          <div className="flex items-center gap-2 mb-3">
            <House weight="fill" className="w-4 h-4 text-amber-400" />
            <h2 className="text-sm font-semibold text-neutral-200">建议演示路径</h2>
          </div>
          <ol className="space-y-3">
            {DEMO_FLOW.map((step, i) => (
              <li key={step.title} className="flex gap-3">
                <span className="shrink-0 w-6 h-6 rounded-full bg-amber-500/15 text-amber-400 text-xs font-semibold grid place-items-center">
                  {i + 1}
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-neutral-200">{step.title}</p>
                  <p className="text-xs text-neutral-500 mt-0.5 leading-relaxed">{step.desc}</p>
                </div>
              </li>
            ))}
          </ol>
          <button
            onClick={() => nav('/admin/documents')}
            className="mt-4 inline-flex items-center gap-1.5 text-xs text-amber-400 hover:text-amber-300"
          >
            前往文档接入 <ArrowRight className="w-3.5 h-3.5" />
          </button>
        </div>

        {/* 最近接入文档 */}
        <div className="p-5 rounded-xl border border-neutral-800 bg-neutral-900/40">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-neutral-200">最近接入文档</h2>
            <button
              onClick={() => load()}
              disabled={refreshing}
              className="text-xs text-neutral-400 hover:text-amber-400 disabled:opacity-50"
            >
              刷新
            </button>
          </div>
          {loading ? (
            <div className="flex items-center gap-2 text-neutral-500 text-xs py-6">
              <CircleNotch className="w-4 h-4 animate-spin" /> 正在加载后台概览...
            </div>
          ) : recentDocs.length === 0 ? (
            <p className="text-xs text-neutral-600 py-6">
              当前还没有文档，先去「文档接入」页面上传一份资料。
            </p>
          ) : (
            <div className="space-y-2">
              {recentDocs.map((doc) => (
                <button
                  key={doc.documentId}
                  onClick={() => nav(`/admin/documents/${doc.documentId}`)}
                  className="w-full flex items-center justify-between gap-3 p-2.5 rounded-lg border border-neutral-800 bg-neutral-900/40 hover:border-amber-500/30 hover:bg-neutral-900/70 transition-colors text-left"
                >
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-neutral-200 truncate">{doc.documentName}</p>
                    {doc.originalFileName && doc.originalFileName !== doc.documentName && (
                      <p className="text-[11px] text-neutral-600 truncate">{doc.originalFileName}</p>
                    )}
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    <StatusBadge type="parse" code={doc.parseStatus} />
                    <StatusBadge type="index" code={doc.indexStatus} />
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="mt-6 p-5 rounded-xl border border-neutral-800 bg-neutral-900/40">
        <div className="flex items-center gap-2 mb-2">
          <House weight="fill" className="w-4 h-4 text-amber-400" />
          <h2 className="text-sm font-semibold text-neutral-200">说明</h2>
        </div>
        <p className="text-xs text-neutral-500 leading-relaxed">
          仪表盘指标由前端组合 <code className="px-1 py-0.5 rounded bg-neutral-800 text-neutral-300 font-mono">/document/page</code> 与 <code className="px-1 py-0.5 rounded bg-neutral-800 text-neutral-300 font-mono">/chat/session/list</code> 计算。
          若需今日会话数、平均延迟、各阶段 P50/P90 等更丰富统计，可在后端新增专门 dashboard 聚合端点后对接。
        </p>
      </div>
    </AdminPage>
  );
}
