import { ArrowClockwise } from '@phosphor-icons/react';
import { StatusBadge } from '../StatusBadge';
import type { DocumentDetailVo } from '../../../types/document';

interface Props {
  document: DocumentDetailVo;
  onRefresh: () => void;
  refreshing: boolean;
}

export function OverviewSection({ document: doc, onRefresh, refreshing }: Props) {
  const phase = derivePhase(doc);

  return (
    <section className="space-y-5">
      <div className="flex items-start gap-4 rounded-lg border border-neutral-800 bg-neutral-900/30 p-5">
        <div className="flex-1 min-w-0">
          <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600 mb-1">
            Current Document
          </p>
          <h3 className="text-base font-semibold text-neutral-100 truncate">
            {doc.documentName}
          </h3>
          {doc.originalFileName && doc.originalFileName !== doc.documentName && (
            <p className="text-xs text-neutral-500 mt-0.5 truncate">{doc.originalFileName}</p>
          )}
          {doc.parseErrorMsg && (
            <p className="text-xs text-red-400 mt-2">{doc.parseErrorMsg}</p>
          )}
        </div>
        <div className="flex flex-col items-end gap-2 shrink-0">
          <div className="flex items-center gap-1.5">
            <StatusBadge type="parse" code={doc.parseStatus} />
            <StatusBadge type="strategy" code={doc.strategyStatus} />
            <StatusBadge type="index" code={doc.indexStatus} />
          </div>
          <button
            onClick={onRefresh}
            disabled={refreshing}
            className="flex items-center gap-1.5 text-xs text-neutral-500 hover:text-neutral-300 transition-colors disabled:opacity-50"
          >
            <ArrowClockwise className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-4">
          <p className="text-[11px] font-mono uppercase tracking-wider text-amber-400/70 mb-1">
            当前阶段
          </p>
          <p className="text-sm font-semibold text-neutral-100">{phase.title}</p>
          <p className="text-xs text-neutral-400 mt-1 leading-relaxed">{phase.description}</p>
        </div>
        <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-4">
          <p className="text-[11px] font-mono uppercase tracking-wider text-neutral-600 mb-1">
            下一步建议
          </p>
          <p className="text-sm font-semibold text-neutral-100">{phase.nextTitle}</p>
          <p className="text-xs text-neutral-400 mt-1 leading-relaxed">{phase.nextDescription}</p>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {[
          { label: '字符数', value: doc.charCount?.toLocaleString() ?? '-' },
          { label: 'Token', value: doc.tokenCount?.toLocaleString() ?? '-' },
          { label: '结构层级', value: doc.structureLevel ?? '-' },
          { label: '当前方案', value: doc.currentPlanId ? `v${doc.currentPlanId.slice(-4)}` : '无' },
        ].map((m) => (
          <div
            key={m.label}
            className="rounded-lg border border-neutral-800 bg-neutral-900/30 px-3.5 py-2.5"
          >
            <div className="text-[11px] text-neutral-600 font-mono mb-0.5">{m.label}</div>
            <div className="text-sm text-neutral-200 font-medium">{m.value}</div>
          </div>
        ))}
      </div>
    </section>
  );
}

function derivePhase(doc: DocumentDetailVo): {
  title: string;
  description: string;
  nextTitle: string;
  nextDescription: string;
} {
  if (doc.parseStatus < 3) {
    return {
      title: '解析中',
      description: '系统正在提取文档纯文本与结构，完成后自动推荐索引策略。',
      nextTitle: '等待策略推荐',
      nextDescription: '解析完成后可在「配置策略」查看推荐的双流水线方案。',
    };
  }
  if (doc.parseStatus === 4) {
    return {
      title: '解析失败',
      description: doc.parseErrorMsg ?? '文档解析失败，请检查文件或重新上传。',
      nextTitle: '请重新上传',
      nextDescription: '修复源文件后重新上传以继续。',
    };
  }
  if (doc.strategyStatus < 3) {
    return {
      title: '策略待确认',
      description: '系统已推荐索引策略，可在「配置策略」调整后确认。',
      nextTitle: '前往确认与构建',
      nextDescription: '确认策略方案后系统将自动构建索引。',
    };
  }
  if (doc.indexStatus === 2) {
    return {
      title: '索引构建中',
      description: '系统正在切块、向量化与入库，页面已锁定。',
      nextTitle: '等待构建完成',
      nextDescription: '完成后可在「验证 Chunk 结果」检查分块。',
    };
  }
  if (doc.indexStatus === 3) {
    return {
      title: '索引就绪',
      description: '文档已成功索引，可用于检索与对话。',
      nextTitle: '验证或重建',
      nextDescription: '可检查 Chunk 结果，或在需要时重新构建索引。',
    };
  }
  if (doc.indexStatus === 4) {
    return {
      title: '构建失败',
      description: '索引构建失败，可在「查看任务记录」查看错误日志。',
      nextTitle: '排查并重建',
      nextDescription: '查看任务日志定位失败阶段后重新构建。',
    };
  }
  return {
    title: '策略已确认',
    description: '策略方案已确认，可发起索引构建。',
    nextTitle: '执行索引构建',
    nextDescription: '在「确认并构建」发起构建。',
  };
}
