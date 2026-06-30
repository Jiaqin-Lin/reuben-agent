import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Files, MagnifyingGlass, Trash, X } from '@phosphor-icons/react';
import { pageQueryDocuments, deleteDocument } from '../../api/document';
import { EmptyState } from '../shared/EmptyState';
import { useToast } from '../shared/Toast';
import { StatusBadge } from './StatusBadge';
import { FileType } from '../../types/enums';
import type { DocumentListItemVo } from '../../types/document';

interface Props {
  refreshKey: number;
  /** 详情跳转前缀，区分 admin 与非 admin */
  linkBase: '/documents' | '/admin/documents';
  onUploaded?: () => void;
}

export function DocumentList({ refreshKey, linkBase, onUploaded: _onUploaded }: Props) {
  const nav = useNavigate();
  const [records, setRecords] = useState<DocumentListItemVo[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNo, setPageNo] = useState(1);
  const [pageSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [committedKeyword, setCommittedKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const { toast } = useToast();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await pageQueryDocuments({ pageNo, pageSize, keyword: committedKeyword || undefined });
      setRecords(page.records ?? []);
      setTotal(page.total ?? 0);
    } catch (e) {
      toast(e instanceof Error ? e.message : '加载失败', 'error');
      setRecords([]);
    } finally {
      setLoading(false);
    }
  }, [pageNo, pageSize, committedKeyword, toast]);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const stats = {
    total: records.length,
    parsed: records.filter((d) => d.parseStatus === 3).length,
    confirmed: records.filter((d) => d.strategyStatus === 3).length,
    indexed: records.filter((d) => d.indexStatus === 3).length,
  };

  const handleDelete = async (id: string, name: string) => {
    setDeleting(id);
    try {
      const vo = await deleteDocument(id);
      toast(`已删除 ${name}（存储/向量/索引已级联清理）`, 'success');
      void vo;
      // 若删除后当前页空了且非第一页，回退一页
      if (records.length === 1 && pageNo > 1) {
        setPageNo((p) => p - 1);
      } else {
        load();
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : '删除失败', 'error');
    } finally {
      setDeleting(null);
      setConfirmId(null);
    }
  };

  if (loading && records.length === 0) {
    return (
      <div className="space-y-2">
        {[1, 2, 3].map((i) => (
          <div key={i} className="h-14 rounded-lg bg-neutral-900/30 border border-neutral-800 animate-pulse" />
        ))}
      </div>
    );
  }

  if (records.length === 0 && !committedKeyword) {
    return (
      <EmptyState icon={Files} title="暂无文档" description="上传一个文档开始 RAG 测试" />
    );
  }

  return (
    <div className="space-y-4">
      {/* 统计卡 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
        <StatCard label="当前页文档" value={stats.total} />
        <StatCard label="解析完成" value={stats.parsed} />
        <StatCard label="策略确认" value={stats.confirmed} />
        <StatCard label="索引可用" value={stats.indexed} />
      </div>

      {/* 搜索 */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1 max-w-sm">
          <MagnifyingGlass className="absolute left-2.5 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-600" />
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setCommittedKeyword(keyword.trim());
                setPageNo(1);
              }
            }}
            placeholder="搜索文档名..."
            className="w-full pl-8 pr-3 py-1.5 text-sm rounded-lg bg-neutral-900 border border-neutral-800 text-neutral-100 placeholder:text-neutral-600 focus:outline-none focus:border-amber-500/50 transition-colors"
          />
        </div>
        <button
          onClick={() => {
            setCommittedKeyword(keyword.trim());
            setPageNo(1);
          }}
          className="px-3 py-1.5 text-xs rounded-lg border border-neutral-800 text-neutral-300 hover:bg-neutral-800 transition-colors"
        >
          搜索
        </button>
        {committedKeyword && (
          <button
            onClick={() => {
              setKeyword('');
              setCommittedKeyword('');
              setPageNo(1);
            }}
            className="flex items-center gap-1 px-2 py-1.5 text-xs text-neutral-500 hover:text-neutral-300"
          >
            <X className="w-3.5 h-3.5" /> 清除
          </button>
        )}
      </div>

      {/* 列表 */}
      {records.length === 0 ? (
        <EmptyState icon={Files} title="无匹配文档" description="尝试更换关键词" />
      ) : (
        <div className="space-y-1.5">
          {records.map((doc) => (
            <DocumentListRow
              key={doc.documentId}
              doc={doc}
              onOpen={() => nav(`${linkBase}/${doc.documentId}`)}
              onDelete={() => setConfirmId(doc.documentId)}
              deleting={deleting === doc.documentId}
              confirmOpen={confirmId === doc.documentId}
              onConfirmDelete={() => handleDelete(doc.documentId, doc.documentName)}
              onCancelDelete={() => setConfirmId(null)}
            />
          ))}
        </div>
      )}

      {/* 分页 */}
      <div className="flex items-center justify-between pt-2">
        <span className="text-[11px] font-mono text-neutral-500">
          共 {total} 条 · 第 {pageNo}/{totalPages} 页
        </span>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setPageNo((p) => Math.max(1, p - 1))}
            disabled={pageNo <= 1 || loading}
            className="px-3 py-1.5 text-xs rounded-md border border-neutral-800 text-neutral-300 hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            上一页
          </button>
          <button
            onClick={() => setPageNo((p) => Math.min(totalPages, p + 1))}
            disabled={pageNo >= totalPages || loading}
            className="px-3 py-1.5 text-xs rounded-md border border-neutral-800 text-neutral-300 hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed"
          >
            下一页
          </button>
        </div>
      </div>
    </div>
  );
}

function DocumentListRow({
  doc,
  onOpen,
  onDelete,
  deleting,
  confirmOpen,
  onConfirmDelete,
  onCancelDelete,
}: {
  doc: DocumentListItemVo;
  onOpen: () => void;
  onDelete: () => void;
  deleting: boolean;
  confirmOpen: boolean;
  onConfirmDelete: () => void;
  onCancelDelete: () => void;
}) {
  const ext = FileType[doc.fileType] ?? 'FILE';
  const needsConfirm = doc.strategyStatus === 2;
  return (
    <div
      className={`flex items-center gap-3 px-4 py-3 rounded-lg border transition-all duration-150 ${
        needsConfirm
          ? 'border-amber-500/40 bg-amber-500/5'
          : 'border-neutral-800 bg-neutral-900/30'
      }`}
    >
      <button onClick={onOpen} className="flex-1 flex items-center gap-3 text-left min-w-0 group">
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-neutral-200 truncate group-hover:text-amber-300 transition-colors">
            {doc.documentName}
          </p>
          <p className="text-xs text-neutral-600 font-mono mt-0.5 truncate">
            {ext}
            {doc.fileSize ? ` · ${(doc.fileSize / 1024).toFixed(0)} KB` : ''}
            {doc.updateTime ? ` · ${new Date(doc.updateTime).toLocaleDateString('zh-CN')}` : ''}
          </p>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          <StatusBadge type="parse" code={doc.parseStatus} />
          <StatusBadge type="strategy" code={doc.strategyStatus} />
          <StatusBadge type="index" code={doc.indexStatus} />
        </div>
      </button>

      {confirmOpen ? (
        <div className="flex items-center gap-1.5 shrink-0">
          <button
            onClick={onConfirmDelete}
            disabled={deleting}
            className="px-2.5 py-1 text-[11px] rounded-md bg-red-500/20 text-red-400 hover:bg-red-500/30 disabled:opacity-50"
          >
            {deleting ? '删除中' : '确认删除'}
          </button>
          <button
            onClick={onCancelDelete}
            disabled={deleting}
            className="px-2.5 py-1 text-[11px] rounded-md border border-neutral-800 text-neutral-400 hover:bg-neutral-800"
          >
            取消
          </button>
        </div>
      ) : (
        <button
          onClick={onDelete}
          className="p-1.5 rounded-md text-neutral-600 hover:text-red-400 hover:bg-red-500/10 transition-colors shrink-0"
          title="删除文档"
        >
          <Trash className="w-4 h-4" />
        </button>
      )}
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 px-3 py-2">
      <p className="text-[10px] font-mono text-neutral-600">{label}</p>
      <p className="text-sm font-semibold text-neutral-200">{value}</p>
    </div>
  );
}
