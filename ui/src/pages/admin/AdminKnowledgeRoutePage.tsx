import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Plus,
  ArrowsClockwise,
  PencilSimple,
  Trash,
  Sparkle,
  CircleNotch,
  CaretDown,
} from '@phosphor-icons/react';
import { AdminPage } from '../../components/admin/AdminLayout';
import { Drawer, Field, inputClass } from '../../components/admin/Drawer';
import { useToast } from '../../components/shared/Toast';
import { ApiError } from '../../types/api';
import {
  listScopes,
  saveScope,
  deleteScope,
  listTopics,
  saveTopic,
  deleteTopic,
  listRelations,
  saveRelation,
  removeRelation,
  getProfile,
  regenerateProfile,
  batchRegenerateProfiles,
} from '../../api/knowledge';
import { pageQueryDocuments } from '../../api/document';
import type {
  KnowledgeScopeItemVo,
  KnowledgeTopicItemVo,
  TopicDocumentRelationItemVo,
  DocumentProfileVo,
  KnowledgeScopeSaveDto,
  KnowledgeTopicSaveDto,
  TopicDocumentRelationSaveDto,
} from '../../types/knowledge';
import type { DocumentListItemVo } from '../../types/document';
import {
  ANSWER_SHAPE_OPTIONS,
  EXECUTION_PREFERENCE_OPTIONS,
  ANSWER_SHAPE_LABEL,
  EXECUTION_PREFERENCE_LABEL,
  DOCUMENT_TYPE_LABEL,
  PROFILE_SOURCE_LABEL,
  formatMappedLabel,
  profileStatusMeta,
  graphCapabilityText,
  parseTextList,
  parseJsonArray,
} from '../../lib/knowledgeOptions';
import { cn } from '../../lib/cn';

type TabKey = 'scope' | 'topic' | 'profile' | 'relation';

const TAB_LIST: { key: TabKey; step: number; label: string; hint: string }[] = [
  { key: 'scope', step: 1, label: '知识范围', hint: '定义知识领域边界' },
  { key: 'topic', step: 2, label: '知识主题', hint: '范围下的可回答单元' },
  { key: 'profile', step: 3, label: '文档画像', hint: '文档类型与能力分析' },
  { key: 'relation', step: 4, label: '主题文档关联', hint: '主题与文档的绑定关系' },
];

type DrawerKind = 'scope' | 'topic' | 'relation' | 'profile';
type DrawerMode = 'view' | 'edit';

function errMsg(e: unknown, fallback: string): string {
  if (e instanceof ApiError && e.message) return e.message;
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

function PrimaryButton({
  children,
  loading,
  disabled,
  onClick,
}: {
  children: React.ReactNode;
  loading?: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || loading}
      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-amber-500 text-black text-xs font-semibold hover:bg-amber-400 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
    >
      {loading && <CircleNotch className="w-3.5 h-3.5 animate-spin" />}
      {children}
    </button>
  );
}

function GhostButton({
  children,
  onClick,
  disabled,
  danger,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed',
        danger
          ? 'border-red-500/30 text-red-400 hover:bg-red-500/10'
          : 'border-neutral-700 text-neutral-300 hover:bg-neutral-800',
      )}
    >
      {children}
    </button>
  );
}

function StatCard({ label, value, hint }: { label: string; value: string | number; hint: string }) {
  return (
    <div className="p-4 rounded-xl border border-neutral-800 bg-neutral-900/40">
      <p className="text-xs text-neutral-500">{label}</p>
      <p className="mt-1.5 text-2xl font-semibold text-neutral-100 tabular-nums">{value}</p>
      <p className="mt-1 text-[11px] text-neutral-600">{hint}</p>
    </div>
  );
}

function Chip({ tone = 'neutral', children }: { tone?: 'neutral' | 'soft' | 'warning'; children: React.ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium',
        tone === 'neutral' && 'bg-amber-500/10 text-amber-400',
        tone === 'soft' && 'bg-neutral-800 text-neutral-300',
        tone === 'warning' && 'bg-amber-500/15 text-amber-400',
      )}
    >
      {children}
    </span>
  );
}

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid gap-1">
      <span className="text-[11px] text-neutral-500">{label}</span>
      <span className="text-sm text-neutral-200 break-words">{value || '-'}</span>
    </div>
  );
}

export function AdminKnowledgeRoutePage() {
  const { toast } = useToast();
  const [activeTab, setActiveTab] = useState<TabKey>('scope');

  const [scopes, setScopes] = useState<KnowledgeScopeItemVo[]>([]);
  const [topics, setTopics] = useState<KnowledgeTopicItemVo[]>([]);
  const [documents, setDocuments] = useState<DocumentListItemVo[]>([]);
  const [relations, setRelations] = useState<TopicDocumentRelationItemVo[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [batchLoading, setBatchLoading] = useState(false);

  // 筛选
  const [scopeKeyword, setScopeKeyword] = useState('');
  const [topicKeyword, setTopicKeyword] = useState('');
  const [activeScopeCode, setActiveScopeCode] = useState('');
  const [activeTopicCode, setActiveTopicCode] = useState('');
  const [documentKeyword, setDocumentKeyword] = useState('');
  const [relationKeyword, setRelationKeyword] = useState('');

  // 抽屉
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerKind, setDrawerKind] = useState<DrawerKind>('scope');
  const [drawerMode, setDrawerMode] = useState<DrawerMode>('view');
  const [drawerTarget, setDrawerTarget] = useState<
    KnowledgeScopeItemVo | KnowledgeTopicItemVo | TopicDocumentRelationItemVo | DocumentListItemVo | null
  >(null);
  const [profile, setProfile] = useState<DocumentProfileVo | null>(null);
  const [profileLoading, setProfileLoading] = useState(false);

  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      const [scopeList, topicList, docPage, relList] = await Promise.all([
        listScopes(),
        listTopics(),
        pageQueryDocuments({ pageNo: 1, pageSize: 200 }),
        listRelations('').catch(() => [] as TopicDocumentRelationItemVo[]),
      ]);
      setScopes(Array.isArray(scopeList) ? scopeList : []);
      setTopics(Array.isArray(topicList) ? topicList : []);
      setDocuments(docPage?.records ?? []);
      setRelations(Array.isArray(relList) ? relList : []);
    } catch (e) {
      toast(errMsg(e, '加载知识路由数据失败'), 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const refreshRelations = useCallback(async () => {
    try {
      const list = await listRelations('');
      setRelations(Array.isArray(list) ? list : []);
    } catch {
      // 静默
    }
  }, []);

  // ============ Scope ============
  const filteredScopes = useMemo(() => {
    const kw = scopeKeyword.trim().toLowerCase();
    if (!kw) return scopes;
    return scopes.filter((s) =>
      [s.scopeCode, s.scopeName, s.description, s.aliases].filter(Boolean).join(' ').toLowerCase().includes(kw),
    );
  }, [scopes, scopeKeyword]);

  const openScopeCreate = () => {
    setDrawerTarget(null);
    setDrawerKind('scope');
    setDrawerMode('edit');
    setDrawerOpen(true);
  };

  const openScopeView = (item: KnowledgeScopeItemVo) => {
    setDrawerTarget(item);
    setDrawerKind('scope');
    setDrawerMode('view');
    setDrawerOpen(true);
  };

  const handleSaveScope = async (dto: KnowledgeScopeSaveDto) => {
    setActionLoading(true);
    try {
      await saveScope(dto);
      toast('知识范围已保存', 'success');
      await loadAll();
      setDrawerOpen(false);
    } catch (e) {
      toast(errMsg(e, '保存失败'), 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const handleDeleteScope = async (scopeCode: string, scopeName: string) => {
    if (!window.confirm(`确认删除范围「${scopeName}」吗？`)) return;
    setActionLoading(true);
    try {
      await deleteScope({ scopeCode });
      toast('知识范围已删除', 'success');
      await loadAll();
      setDrawerOpen(false);
    } catch (e) {
      toast(errMsg(e, '删除失败'), 'error');
    } finally {
      setActionLoading(false);
    }
  };

  // ============ Topic ============
  const filteredTopics = useMemo(() => {
    const kw = topicKeyword.trim().toLowerCase();
    return topics.filter((t) => {
      if (activeScopeCode && t.scopeCode !== activeScopeCode) return false;
      if (!kw) return true;
      return [t.topicCode, t.topicName, t.description, t.aliases].filter(Boolean).join(' ').toLowerCase().includes(kw);
    });
  }, [topics, topicKeyword, activeScopeCode]);

  const openTopicCreate = () => {
    setDrawerTarget(null);
    setDrawerKind('topic');
    setDrawerMode('edit');
    setDrawerOpen(true);
  };

  const openTopicView = (item: KnowledgeTopicItemVo) => {
    setDrawerTarget(item);
    setDrawerKind('topic');
    setDrawerMode('view');
    setDrawerOpen(true);
  };

  const handleSaveTopic = async (dto: KnowledgeTopicSaveDto) => {
    setActionLoading(true);
    try {
      await saveTopic(dto);
      toast('知识主题已保存', 'success');
      await loadAll();
      setDrawerOpen(false);
    } catch (e) {
      toast(errMsg(e, '保存失败'), 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const handleDeleteTopic = async (topicCode: string, topicName: string) => {
    if (!window.confirm(`确认删除主题「${topicName}」吗？`)) return;
    setActionLoading(true);
    try {
      await deleteTopic({ topicCode });
      toast('知识主题已删除', 'success');
      await loadAll();
      setDrawerOpen(false);
    } catch (e) {
      toast(errMsg(e, '删除失败'), 'error');
    } finally {
      setActionLoading(false);
    }
  };

  // ============ Profile ============
  const filteredDocuments = useMemo(() => {
    const kw = documentKeyword.trim().toLowerCase();
    return documents.filter((d) => {
      if (activeScopeCode && d.knowledgeScopeCode !== activeScopeCode) return false;
      if (!kw) return true;
      return [d.documentName, d.knowledgeScopeName, d.knowledgeScopeCode, d.businessCategory, d.documentTags]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
        .includes(kw);
    });
  }, [documents, documentKeyword, activeScopeCode]);

  const openProfileView = async (item: DocumentListItemVo) => {
    setDrawerTarget(item);
    setDrawerKind('profile');
    setDrawerMode('view');
    setDrawerOpen(true);
    setProfile(null);
    await loadProfileItem(item.documentId);
  };

  const loadProfileItem = async (documentId: string) => {
    setProfileLoading(true);
    try {
      const p = await getProfile(Number(documentId));
      setProfile(p);
    } catch {
      setProfile(null);
    } finally {
      setProfileLoading(false);
    }
  };

  const handleRegenerateProfile = async (documentId: string) => {
    setActionLoading(true);
    try {
      await regenerateProfile(Number(documentId));
      toast('文档画像已重新生成', 'success');
      await loadProfileItem(documentId);
    } catch (e) {
      toast(errMsg(e, '重新生成失败'), 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const handleBatchRegenerate = async () => {
    if (!documents.length || !window.confirm(`确认批量重建 ${documents.length} 份文档画像吗？`)) return;
    setBatchLoading(true);
    try {
      await batchRegenerateProfiles(documents.map((d) => Number(d.documentId)));
      toast(`已触发 ${documents.length} 份文档的画像重建`, 'success');
    } catch (e) {
      toast(errMsg(e, '批量重建失败'), 'error');
    } finally {
      setBatchLoading(false);
    }
  };

  // ============ Relation ============
  const filteredRelations = useMemo(() => {
    const kw = relationKeyword.trim().toLowerCase();
    return relations.filter((r) => {
      const topic = topics.find((t) => t.topicCode === r.topicCode);
      if (activeScopeCode && topic?.scopeCode !== activeScopeCode) return false;
      if (activeTopicCode && r.topicCode !== activeTopicCode) return false;
      if (!kw) return true;
      return [r.topicCode, r.documentName, r.reason, r.scopeName]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
        .includes(kw);
    });
  }, [relations, relationKeyword, activeScopeCode, activeTopicCode, topics]);

  const openRelationCreate = () => {
    setDrawerTarget(null);
    setDrawerKind('relation');
    setDrawerMode('edit');
    setDrawerOpen(true);
  };

  const openRelationView = (item: TopicDocumentRelationItemVo) => {
    setDrawerTarget(item);
    setDrawerKind('relation');
    setDrawerMode('view');
    setDrawerOpen(true);
  };

  const handleSaveRelation = async (dto: TopicDocumentRelationSaveDto) => {
    setActionLoading(true);
    try {
      await saveRelation(dto);
      toast('主题文档关联已保存', 'success');
      await refreshRelations();
      setDrawerOpen(false);
    } catch (e) {
      toast(errMsg(e, '保存失败'), 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const handleRemoveRelation = async (item: TopicDocumentRelationItemVo) => {
    if (!window.confirm(`确认移除「${item.documentName}」与主题 ${item.topicCode} 的关联吗？`)) return;
    setActionLoading(true);
    try {
      await removeRelation({ topicCode: item.topicCode, documentId: item.documentId });
      toast('主题文档关联已移除', 'success');
      await refreshRelations();
      setDrawerOpen(false);
    } catch (e) {
      toast(errMsg(e, '移除失败'), 'error');
    } finally {
      setActionLoading(false);
    }
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setDrawerTarget(null);
    setProfile(null);
  };

  const drawerTitle = (() => {
    const creating = drawerMode === 'edit' && !drawerTarget;
    if (drawerKind === 'scope') return creating ? '新建知识范围' : '知识范围详情';
    if (drawerKind === 'topic') return creating ? '新建知识主题' : '知识主题详情';
    if (drawerKind === 'relation') return creating ? '新建主题文档关联' : '关联详情';
    return '文档画像详情';
  })();

  const documentWithMetaCount = documents.filter(
    (d) => d.knowledgeScopeCode || d.knowledgeScopeName || d.businessCategory || d.documentTags,
  ).length;
  const pendingTopicCount = topics.filter((t) => !relations.some((r) => r.topicCode === t.topicCode)).length;

  // ============ Scope Coverage / Profile Anomalies ============
  const scopeCoverageRows = useMemo(() => {
    return scopes.map((scope) => {
      const scopeTopics = topics.filter((t) => t.scopeCode === scope.scopeCode);
      const topicCodes = new Set(scopeTopics.map((t) => t.topicCode));
      const scopeRelations = relations.filter((r) => topicCodes.has(r.topicCode));
      const coveredTopicCodes = new Set(scopeRelations.map((r) => r.topicCode));
      const scopeDocuments = documents.filter((d) => d.knowledgeScopeCode === scope.scopeCode);
      const coverageRate = scopeTopics.length ? (coveredTopicCodes.size / scopeTopics.length) * 100 : 0;
      return {
        scopeCode: scope.scopeCode,
        scopeName: scope.scopeName,
        topicCount: scopeTopics.length,
        coveredTopicCount: coveredTopicCodes.size,
        pendingTopicCount: Math.max(0, scopeTopics.length - coveredTopicCodes.size),
        documentCount: scopeDocuments.length,
        coverageRate,
        coverageRateText: `${coverageRate.toFixed(0)}%`,
      };
    });
  }, [scopes, topics, relations, documents]);

  const overallCoverageRateText = useMemo(() => {
    if (!topics.length) return '0%';
    const covered = new Set(relations.map((r) => r.topicCode));
    return `${((covered.size / topics.length) * 100).toFixed(0)}%`;
  }, [topics, relations]);

  const profileAnomalyRows = useMemo(() => {
    const scopeCodes = new Set(scopes.map((s) => s.scopeCode));
    const linkedDocumentIds = new Set(relations.map((r) => String(r.documentId)));
    return documents
      .map((d) => {
        const problems: string[] = [];
        if (!d.knowledgeScopeCode && !d.knowledgeScopeName) problems.push('缺少知识范围');
        if (d.knowledgeScopeCode && !scopeCodes.has(d.knowledgeScopeCode)) problems.push('范围未建节点');
        if (!d.businessCategory) problems.push('缺少业务分类');
        if (!d.documentTags) problems.push('缺少标签');
        if (!linkedDocumentIds.has(String(d.documentId))) problems.push('未绑定主题');
        const scopeText = d.knowledgeScopeName || d.knowledgeScopeCode || '未分配范围';
        return {
          documentId: d.documentId,
          documentName: d.documentName,
          scopeText,
          problems,
          tone: problems.length >= 3 ? 'danger' : 'warning',
        };
      })
      .filter((row) => row.problems.length > 0);
  }, [scopes, documents, relations]);

  const [anomalyCollapsed, setAnomalyCollapsed] = useState(false);
  const [coverageCollapsed, setCoverageCollapsed] = useState(false);
  const [selectedRepairIds, setSelectedRepairIds] = useState<string[]>([]);
  const allAnomaliesSelected =
    profileAnomalyRows.length > 0 &&
    profileAnomalyRows.every((row) => selectedRepairIds.includes(row.documentId));

  const toggleRepair = (id: string) => {
    setSelectedRepairIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  };
  const toggleAllAnomalies = () => {
    if (allAnomaliesSelected) setSelectedRepairIds([]);
    else setSelectedRepairIds(profileAnomalyRows.map((row) => row.documentId));
  };

  const handleBatchRepairAnomalies = async () => {
    if (!selectedRepairIds.length) return;
    if (!window.confirm(`确认批量重建 ${selectedRepairIds.length} 份异常文档画像吗？`)) return;
    setBatchLoading(true);
    try {
      await batchRegenerateProfiles(selectedRepairIds.map((id) => Number(id)));
      toast(`已触发 ${selectedRepairIds.length} 份画像重建`, 'success');
      setSelectedRepairIds([]);
    } catch (e) {
      toast(errMsg(e, '批量重建失败'), 'error');
    } finally {
      setBatchLoading(false);
    }
  };

  return (
    <AdminPage>
      <div className="flex items-start justify-between gap-4 mb-5">
        <div>
          <h1 className="text-lg font-semibold text-neutral-100">知识路由配置</h1>
          <p className="text-sm text-neutral-500 mt-0.5">
            按 范围 → 主题 → 画像 → 关联 的顺序逐步配置，构建自动知识问答的候选预选体系
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <GhostButton onClick={() => loadAll()} disabled={loading}>
            <ArrowsClockwise className="w-3.5 h-3.5" />
            刷新
          </GhostButton>
          <PrimaryButton onClick={handleBatchRegenerate} loading={batchLoading} disabled={!documents.length}>
            <Sparkle className="w-3.5 h-3.5" />
            批量重建画像
          </PrimaryButton>
        </div>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
        <StatCard label="知识范围" value={scopes.length} hint="自动路由第一层收敛边界" />
        <StatCard label="知识主题" value={topics.length} hint="范围里的可回答单元" />
        <StatCard label="已保存关联" value={relations.length} hint="所有主题的文档关联数" />
        <StatCard label="未关联主题" value={pendingTopicCount} hint="还没绑定任何文档的主题" />
      </div>

      {/* Scope Coverage 面板 */}
      <section className="mb-4 rounded-xl border border-neutral-800 bg-neutral-900/30 overflow-hidden">
        <button
          type="button"
          onClick={() => setCoverageCollapsed((v) => !v)}
          className="w-full flex items-center justify-between gap-3 p-4 hover:bg-neutral-900/50 transition-colors"
        >
          <div className="text-left">
            <p className="text-[11px] font-mono uppercase tracking-wider text-amber-400">Scope Coverage</p>
            <h3 className="text-sm font-semibold text-neutral-200 mt-0.5">范围覆盖率统计</h3>
          </div>
          <div className="flex items-center gap-3 shrink-0">
            <span className="px-2 py-0.5 rounded-full bg-neutral-800 text-neutral-300 text-[11px]">
              整体覆盖率 {overallCoverageRateText}
            </span>
            <CaretDown className={cn('w-4 h-4 text-neutral-500 transition-transform', coverageCollapsed && 'rotate-180')} />
          </div>
        </button>
        {!coverageCollapsed && (
          <div className="px-4 pb-4 grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
            {scopeCoverageRows.length === 0 ? (
              <p className="text-xs text-neutral-600 col-span-full py-4 text-center">还没有知识范围</p>
            ) : (
              scopeCoverageRows.map((row) => (
                <div
                  key={row.scopeCode}
                  className={cn(
                    'p-3 rounded-lg border',
                    row.pendingTopicCount > 0
                      ? 'border-amber-500/30 bg-amber-500/5'
                      : 'border-neutral-800 bg-neutral-900/40',
                  )}
                >
                  <div className="flex items-center justify-between gap-2">
                    <div className="min-w-0">
                      <strong className="text-sm text-neutral-200 truncate block">{row.scopeName}</strong>
                      <span className="text-[11px] text-neutral-600 font-mono">{row.scopeCode}</span>
                    </div>
                    <span className="text-sm font-semibold text-amber-400 tabular-nums shrink-0">
                      {row.coverageRateText}
                    </span>
                  </div>
                  <div className="h-1.5 mt-2 rounded-full bg-neutral-800 overflow-hidden">
                    <div
                      className="h-full bg-amber-500 rounded-full transition-all"
                      style={{ width: `${Math.min(100, row.coverageRate)}%` }}
                    />
                  </div>
                  <div className="flex flex-wrap gap-x-3 gap-y-1 mt-2 text-[11px] text-neutral-500">
                    <span>主题 {row.topicCount}</span>
                    <span>已覆盖 {row.coveredTopicCount}</span>
                    <span>未关联 {row.pendingTopicCount}</span>
                    <span>文档 {row.documentCount}</span>
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </section>

      {/* Profile Anomalies 面板 */}
      {profileAnomalyRows.length > 0 && (
        <section className="mb-4 rounded-xl border border-neutral-800 bg-neutral-900/30 overflow-hidden">
          <button
            type="button"
            onClick={() => setAnomalyCollapsed((v) => !v)}
            className="w-full flex items-center justify-between gap-3 p-4 hover:bg-neutral-900/50 transition-colors"
          >
            <div className="text-left">
              <p className="text-[11px] font-mono uppercase tracking-wider text-amber-400">Profile Anomalies</p>
              <h3 className="text-sm font-semibold text-neutral-200 mt-0.5">
                画像异常清单（{profileAnomalyRows.length}）
              </h3>
            </div>
            <div className="flex items-center gap-3 shrink-0">
              <span
                role="button"
                tabIndex={0}
                onClick={(e) => {
                  e.stopPropagation();
                  handleBatchRepairAnomalies();
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.stopPropagation();
                    handleBatchRepairAnomalies();
                  }
                }}
                className={cn(
                  'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-medium transition-colors',
                  selectedRepairIds.length
                    ? 'border-amber-500/40 text-amber-400 hover:bg-amber-500/10'
                    : 'border-neutral-700 text-neutral-500 cursor-not-allowed',
                )}
              >
                {batchLoading ? '修复中...' : `批量重建 ${selectedRepairIds.length} 份`}
              </span>
              <CaretDown className={cn('w-4 h-4 text-neutral-500 transition-transform', anomalyCollapsed && 'rotate-180')} />
            </div>
          </button>
          {!anomalyCollapsed && (
            <div className="px-4 pb-4">
              <label className="inline-flex items-center gap-2 mb-3 text-xs text-neutral-400 cursor-pointer">
                <input
                  type="checkbox"
                  checked={allAnomaliesSelected}
                  onChange={toggleAllAnomalies}
                  className="accent-amber-500"
                />
                全选异常
              </label>
              <div className="space-y-2">
                {profileAnomalyRows.map((row) => (
                  <div
                    key={row.documentId}
                    className={cn(
                      'flex items-start gap-3 p-3 rounded-lg border',
                      row.tone === 'danger'
                        ? 'border-red-500/30 bg-red-500/5'
                        : 'border-amber-500/30 bg-amber-500/5',
                    )}
                  >
                    <input
                      type="checkbox"
                      checked={selectedRepairIds.includes(row.documentId)}
                      onChange={() => toggleRepair(row.documentId)}
                      className="mt-1 accent-amber-500"
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <strong className="text-sm text-neutral-200 truncate">{row.documentName}</strong>
                        <span className="text-[11px] text-neutral-600 shrink-0">{row.scopeText}</span>
                      </div>
                      <div className="flex flex-wrap gap-1.5 mt-1.5">
                        {row.problems.map((p) => (
                          <span key={p} className="px-2 py-0.5 rounded-full bg-amber-500/15 text-amber-400 text-[11px]">
                            {p}
                          </span>
                        ))}
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        const doc = documents.find((d) => d.documentId === row.documentId);
                        if (doc) openProfileView(doc);
                      }}
                      className="shrink-0 px-2.5 py-1 rounded-lg border border-neutral-700 text-neutral-300 text-[11px] hover:bg-neutral-800"
                    >
                      查看
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>
      )}

      <nav className="flex gap-1 p-1 rounded-xl border border-neutral-800 bg-neutral-900/40 mb-4 overflow-x-auto">
        {TAB_LIST.map((tab) => {
          const active = activeTab === tab.key;
          return (
            <button
              key={tab.key}
              type="button"
              onClick={() => setActiveTab(tab.key)}
              className={cn(
                'flex items-center gap-2.5 px-4 py-2 rounded-lg text-sm transition-colors whitespace-nowrap',
                active ? 'bg-amber-500/10 text-amber-400 font-medium' : 'text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800/50',
              )}
            >
              <span
                className={cn(
                  'inline-flex items-center justify-center w-5 h-5 rounded-full text-[11px] font-bold',
                  active ? 'bg-amber-500 text-black' : 'bg-neutral-800 text-neutral-500',
                )}
              >
                {tab.step}
              </span>
              <span>{tab.label}</span>
              <span className="hidden lg:inline text-[11px] text-neutral-600">{tab.hint}</span>
            </button>
          );
        })}
      </nav>

      {loading ? (
        <div className="flex items-center justify-center py-20 text-neutral-500">
          <CircleNotch className="w-5 h-5 animate-spin mr-2" />
          加载中...
        </div>
      ) : (
        <>
          {activeTab === 'scope' && (
            <ScopeTab
              scopes={filteredScopes}
              topics={topics}
              documents={documents}
              keyword={scopeKeyword}
              onKeyword={setScopeKeyword}
              onCreate={openScopeCreate}
              onView={openScopeView}
            />
          )}
          {activeTab === 'topic' && (
            <TopicTab
              topics={filteredTopics}
              scopes={scopes}
              activeScopeCode={activeScopeCode}
              onScope={setActiveScopeCode}
              keyword={topicKeyword}
              onKeyword={setTopicKeyword}
              onCreate={openTopicCreate}
              onView={openTopicView}
            />
          )}
          {activeTab === 'profile' && (
            <ProfileTab
              documents={filteredDocuments}
              keyword={documentKeyword}
              onKeyword={setDocumentKeyword}
              onView={openProfileView}
              withMetaCount={documentWithMetaCount}
            />
          )}
          {activeTab === 'relation' && (
            <RelationTab
              relations={filteredRelations}
              scopes={scopes}
              topics={topics}
              activeScopeCode={activeScopeCode}
              activeTopicCode={activeTopicCode}
              onScope={setActiveScopeCode}
              onTopic={setActiveTopicCode}
              keyword={relationKeyword}
              onKeyword={setRelationKeyword}
              onCreate={openRelationCreate}
              onView={openRelationView}
              onRemove={handleRemoveRelation}
              actionLoading={actionLoading}
              onRefresh={refreshRelations}
            />
          )}
        </>
      )}

      <Drawer open={drawerOpen} title={drawerTitle} onClose={closeDrawer}>
        {drawerKind === 'scope' && (
          <ScopeDrawer
            mode={drawerMode}
            target={drawerTarget as KnowledgeScopeItemVo | null}
            actionLoading={actionLoading}
            onSave={handleSaveScope}
            onEdit={() => setDrawerMode('edit')}
            onDelete={() => {
              const t = drawerTarget as KnowledgeScopeItemVo | null;
              if (t) handleDeleteScope(t.scopeCode, t.scopeName);
            }}
          />
        )}
        {drawerKind === 'topic' && (
          <TopicDrawer
            mode={drawerMode}
            target={drawerTarget as KnowledgeTopicItemVo | null}
            scopes={scopes}
            actionLoading={actionLoading}
            onSave={handleSaveTopic}
            onEdit={() => setDrawerMode('edit')}
            onDelete={() => {
              const t = drawerTarget as KnowledgeTopicItemVo | null;
              if (t) handleDeleteTopic(t.topicCode, t.topicName);
            }}
          />
        )}
        {drawerKind === 'relation' && (
          <RelationDrawer
            mode={drawerMode}
            target={drawerTarget as TopicDocumentRelationItemVo | null}
            topics={topics}
            documents={documents}
            actionLoading={actionLoading}
            onSave={handleSaveRelation}
            onEdit={() => setDrawerMode('edit')}
            onRemove={() => {
              const t = drawerTarget as TopicDocumentRelationItemVo | null;
              if (t) handleRemoveRelation(t);
            }}
          />
        )}
        {drawerKind === 'profile' && (
          <ProfileDrawer
            document={drawerTarget as DocumentListItemVo | null}
            profile={profile}
            loading={profileLoading}
            actionLoading={actionLoading}
            onReload={(id) => loadProfileItem(id)}
            onRegenerate={handleRegenerateProfile}
          />
        )}
      </Drawer>
    </AdminPage>
  );
}



function PanelCard({
  title,
  desc,
  action,
  children,
}: {
  title: string;
  desc: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="p-5 rounded-xl border border-neutral-800 bg-neutral-900/30">
      <div className="flex items-start justify-between gap-3 mb-3">
        <div>
          <h3 className="text-sm font-semibold text-neutral-100">{title}</h3>
          <p className="text-xs text-neutral-500 mt-0.5">{desc}</p>
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

function CardGrid({ children }: { children: React.ReactNode }) {
  return <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">{children}</div>;
}

function DataCard({
  title,
  meta,
  desc,
  onClick,
  chips,
}: {
  title: string;
  meta?: React.ReactNode;
  desc?: string;
  onClick: () => void;
  chips?: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="text-left p-4 rounded-lg border border-neutral-800 bg-neutral-900/40 hover:border-amber-500/30 hover:bg-neutral-900/70 transition-colors"
    >
      <div className="flex items-center justify-between gap-2">
        <strong className="text-sm text-neutral-100 truncate">{title}</strong>
      </div>
      {chips && <div className="flex flex-wrap gap-1.5 mt-2">{chips}</div>}
      {desc && <p className="text-xs text-neutral-500 mt-2 line-clamp-2">{desc}</p>}
      {meta && <div className="flex gap-3 mt-2 text-[11px] text-neutral-500">{meta}</div>}
    </button>
  );
}

function ScopeTab({
  scopes,
  topics,
  documents,
  keyword,
  onKeyword,
  onCreate,
  onView,
}: {
  scopes: KnowledgeScopeItemVo[];
  topics: KnowledgeTopicItemVo[];
  documents: DocumentListItemVo[];
  keyword: string;
  onKeyword: (v: string) => void;
  onCreate: () => void;
  onView: (item: KnowledgeScopeItemVo) => void;
}) {
  return (
    <PanelCard
      title="知识范围"
      desc="先把大范围定清楚，自动知识问答才能稳定地在正确文档池里预选"
      action={<PrimaryButton onClick={onCreate}><Plus className="w-3.5 h-3.5" />新建范围</PrimaryButton>}
    >
      <input
        value={keyword}
        onChange={(e) => onKeyword(e.target.value)}
        placeholder="按范围编码、名称或描述筛选"
        className={inputClass + ' mb-3'}
      />
      {scopes.length === 0 ? (
        <p className="text-sm text-neutral-600 py-6 text-center">没有匹配的知识范围</p>
      ) : (
        <CardGrid>
          {scopes.map((s) => (
            <DataCard
              key={s.scopeCode}
              title={s.scopeName}
              desc={s.description || '暂无描述'}
              onClick={() => onView(s)}
              meta={
                <>
                  <span>主题 {topics.filter((t) => t.scopeCode === s.scopeCode).length}</span>
                  <span>文档 {documents.filter((d) => d.knowledgeScopeCode === s.scopeCode).length}</span>
                </>
              }
            />
          ))}
        </CardGrid>
      )}
    </PanelCard>
  );
}

function TopicTab({
  topics,
  scopes,
  activeScopeCode,
  onScope,
  keyword,
  onKeyword,
  onCreate,
  onView,
}: {
  topics: KnowledgeTopicItemVo[];
  scopes: KnowledgeScopeItemVo[];
  activeScopeCode: string;
  onScope: (v: string) => void;
  keyword: string;
  onKeyword: (v: string) => void;
  onCreate: () => void;
  onView: (item: KnowledgeTopicItemVo) => void;
}) {
  return (
    <PanelCard
      title="知识主题"
      desc="主题是范围里的可回答单元，后续通过主题文档关联把文档候选进一步收窄"
      action={<PrimaryButton onClick={onCreate}><Plus className="w-3.5 h-3.5" />新建主题</PrimaryButton>}
    >
      <div className="flex flex-col sm:flex-row gap-2 mb-3">
        <select
          value={activeScopeCode}
          onChange={(e) => onScope(e.target.value)}
          className={inputClass + ' sm:w-48'}
        >
          <option value="">全部范围</option>
          {scopes.map((s) => (
            <option key={s.scopeCode} value={s.scopeCode}>{s.scopeName}</option>
          ))}
        </select>
        <input
          value={keyword}
          onChange={(e) => onKeyword(e.target.value)}
          placeholder="按主题编码、名称、别名或描述筛选"
          className={inputClass}
        />
      </div>
      {topics.length === 0 ? (
        <p className="text-sm text-neutral-600 py-6 text-center">当前范围下还没有主题</p>
      ) : (
        <CardGrid>
          {topics.map((t) => (
            <DataCard
              key={t.topicCode}
              title={t.topicName}
              desc={t.description || '暂无描述'}
              onClick={() => onView(t)}
              chips={
                <>
                  <Chip tone="soft">{formatMappedLabel(t.answerShape, ANSWER_SHAPE_LABEL)}</Chip>
                  <Chip tone="soft">{formatMappedLabel(t.executionPreference, EXECUTION_PREFERENCE_LABEL)}</Chip>
                </>
              }
            />
          ))}
        </CardGrid>
      )}
    </PanelCard>
  );
}

function ProfileTab({
  documents,
  keyword,
  onKeyword,
  onView,
  withMetaCount,
}: {
  documents: DocumentListItemVo[];
  keyword: string;
  onKeyword: (v: string) => void;
  onView: (item: DocumentListItemVo) => void;
  withMetaCount: number;
}) {
  return (
    <PanelCard
      title="文档画像"
      desc="查看文档的类型、摘要、核心主题和图能力开关，判断自动路由是否有足够信息"
      action={
        <span className="text-[11px] text-neutral-500">
          已补元数据 <span className="text-amber-400 font-semibold">{withMetaCount}</span> / {documents.length}
        </span>
      }
    >
      <input
        value={keyword}
        onChange={(e) => onKeyword(e.target.value)}
        placeholder="按文档名、范围、业务分类或标签筛选文档"
        className={inputClass + ' mb-3'}
      />
      {documents.length === 0 ? (
        <p className="text-sm text-neutral-600 py-6 text-center">没有匹配的文档</p>
      ) : (
        <CardGrid>
          {documents.map((d) => {
            const metaLine = [d.knowledgeScopeName || d.knowledgeScopeCode, d.businessCategory, d.documentTags]
              .filter(Boolean)
              .join(' · ');
            return (
              <DataCard
                key={d.documentId}
                title={d.documentName}
                desc={metaLine || '还没有范围 / 类目 / 标签元数据'}
                onClick={() => onView(d)}
              />
            );
          })}
        </CardGrid>
      )}
    </PanelCard>
  );
}

function RelationTab({
  relations,
  scopes,
  topics,
  activeScopeCode,
  activeTopicCode,
  onScope,
  onTopic,
  keyword,
  onKeyword,
  onCreate,
  onView,
  onRemove,
  actionLoading,
  onRefresh,
}: {
  relations: TopicDocumentRelationItemVo[];
  scopes: KnowledgeScopeItemVo[];
  topics: KnowledgeTopicItemVo[];
  activeScopeCode: string;
  activeTopicCode: string;
  onScope: (v: string) => void;
  onTopic: (v: string) => void;
  keyword: string;
  onKeyword: (v: string) => void;
  onCreate: () => void;
  onView: (item: TopicDocumentRelationItemVo) => void;
  onRemove: (item: TopicDocumentRelationItemVo) => void;
  actionLoading: boolean;
  onRefresh: () => void;
}) {
  return (
    <PanelCard
      title="主题文档关联"
      desc="把「哪个主题该优先看哪份文档」显式维护下来，低置信自动路由时会直接受益"
      action={
        <div className="flex gap-2">
          <GhostButton onClick={onRefresh} disabled={actionLoading}>
            <ArrowsClockwise className="w-3.5 h-3.5" />
            刷新
          </GhostButton>
          <PrimaryButton onClick={onCreate}><Plus className="w-3.5 h-3.5" />新建关联</PrimaryButton>
        </div>
      }
    >
      <div className="flex flex-col sm:flex-row gap-2 mb-3">
        <select value={activeScopeCode} onChange={(e) => onScope(e.target.value)} className={inputClass + ' sm:w-44'}>
          <option value="">全部范围</option>
          {scopes.map((s) => (
            <option key={s.scopeCode} value={s.scopeCode}>{s.scopeName}</option>
          ))}
        </select>
        <select value={activeTopicCode} onChange={(e) => onTopic(e.target.value)} className={inputClass + ' sm:w-44'}>
          <option value="">全部主题</option>
          {topics.map((t) => (
            <option key={t.topicCode} value={t.topicCode}>{t.topicName}</option>
          ))}
        </select>
        <input
          value={keyword}
          onChange={(e) => onKeyword(e.target.value)}
          placeholder="按主题、文档、原因筛选关联结果"
          className={inputClass}
        />
      </div>
      <div className="flex items-center gap-2 mb-3">
        <Chip tone="soft">{relations.length} 条可见关联</Chip>
      </div>
      {relations.length === 0 ? (
        <p className="text-sm text-neutral-600 py-6 text-center">当前筛选下还没有保存的文档关联</p>
      ) : (
        <div className="space-y-2">
          {relations.map((r) => (
            <div
              key={`${r.topicCode}-${r.documentId}`}
              className="flex items-center justify-between gap-3 p-3 rounded-lg border border-neutral-800 bg-neutral-900/40 hover:bg-neutral-900/70 cursor-pointer transition-colors"
              onClick={() => onView(r)}
            >
              <div className="min-w-0">
                <strong className="text-sm text-neutral-100 truncate block">{r.documentName}</strong>
                <span className="text-[11px] text-neutral-500 font-mono">
                  {r.topicCode} · 分数 {r.relationScore ?? '-'} · {r.scopeName || '未分范围'}
                </span>
                {r.reason && <p className="text-xs text-neutral-500 mt-0.5 line-clamp-1">{r.reason}</p>}
              </div>
              <button
                type="button"
                disabled={actionLoading}
                onClick={(e) => {
                  e.stopPropagation();
                  onRemove(r);
                }}
                className="shrink-0 px-2.5 py-1 rounded-md text-[11px] text-red-400 border border-red-500/30 hover:bg-red-500/10 transition-colors disabled:opacity-50"
              >
                移除
              </button>
            </div>
          ))}
        </div>
      )}
    </PanelCard>
  );
}

function TagSection({ title, items }: { title: string; items: string[] }) {
  if (!items.length) return null;
  return (
    <div>
      <p className="text-[11px] text-neutral-500 mb-1.5">{title}</p>
      <div className="flex flex-wrap gap-1.5">
        {items.map((t, i) => (
          <Chip key={`${title}-${i}`} tone="soft">{t}</Chip>
        ))}
      </div>
    </div>
  );
}

function ScopeDrawer({
  mode,
  target,
  actionLoading,
  onSave,
  onEdit,
  onDelete,
}: {
  mode: DrawerMode;
  target: KnowledgeScopeItemVo | null;
  actionLoading: boolean;
  onSave: (dto: KnowledgeScopeSaveDto) => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const isEdit = mode === 'edit';
  const [form, setForm] = useState<KnowledgeScopeSaveDto>({
    scopeCode: '',
    scopeName: '',
    parentScopeCode: '',
    description: '',
    aliases: '',
    examples: '',
    sortOrder: 0,
  });

  useEffect(() => {
    if (target) {
      setForm({
        id: target.id,
        scopeCode: target.scopeCode,
        scopeName: target.scopeName,
        parentScopeCode: target.parentScopeCode ?? '',
        description: target.description ?? '',
        aliases: target.aliases ?? '',
        examples: target.examples ?? '',
        sortOrder: target.sortOrder ?? 0,
      });
    } else {
      setForm({ scopeCode: '', scopeName: '', parentScopeCode: '', description: '', aliases: '', examples: '', sortOrder: 0 });
    }
  }, [target]);

  if (isEdit) {
    return (
      <div className="space-y-3">
        <Field label="范围编码">
          <input value={form.scopeCode} onChange={(e) => setForm({ ...form, scopeCode: e.target.value })} placeholder="例如 operation_rule" className={inputClass} />
        </Field>
        <Field label="范围名称">
          <input value={form.scopeName} onChange={(e) => setForm({ ...form, scopeName: e.target.value })} placeholder="例如 运营规则" className={inputClass} />
        </Field>
        <Field label="父级编码（可空）">
          <input value={form.parentScopeCode ?? ''} onChange={(e) => setForm({ ...form, parentScopeCode: e.target.value })} className={inputClass} />
        </Field>
        <Field label="别名（英文逗号分隔）">
          <input value={form.aliases ?? ''} onChange={(e) => setForm({ ...form, aliases: e.target.value })} className={inputClass} />
        </Field>
        <Field label="排序值">
          <input type="number" value={form.sortOrder ?? 0} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) })} className={inputClass} />
        </Field>
        <Field label="描述">
          <textarea value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={3} className={inputClass} />
        </Field>
        <Field label="典型问题 JSON">
          <textarea value={form.examples ?? ''} onChange={(e) => setForm({ ...form, examples: e.target.value })} rows={3} placeholder='例如 ["上线观察多久"]' className={inputClass} />
        </Field>
        <div className="flex gap-2 pt-2">
          <PrimaryButton loading={actionLoading} disabled={!form.scopeCode || !form.scopeName} onClick={() => onSave(form)}>保存</PrimaryButton>
        </div>
      </div>
    );
  }

  if (!target) return null;
  return (
    <div className="space-y-4">
      <DetailRow label="范围编码" value={target.scopeCode} />
      <DetailRow label="范围名称" value={target.scopeName} />
      <DetailRow label="父级编码" value={target.parentScopeCode} />
      <DetailRow label="排序值" value={target.sortOrder} />
      <DetailRow label="描述" value={target.description} />
      <TagSection title="别名" items={parseTextList(target.aliases)} />
      <TagSection title="典型问题" items={parseJsonArray(target.examples)} />
      <div className="flex gap-2 pt-3 border-t border-neutral-800">
        <GhostButton onClick={onEdit}><PencilSimple className="w-3.5 h-3.5" />编辑</GhostButton>
        <GhostButton danger onClick={onDelete} disabled={actionLoading}><Trash className="w-3.5 h-3.5" />删除</GhostButton>
      </div>
    </div>
  );
}

function TopicDrawer({
  mode,
  target,
  scopes,
  actionLoading,
  onSave,
  onEdit,
  onDelete,
}: {
  mode: DrawerMode;
  target: KnowledgeTopicItemVo | null;
  scopes: KnowledgeScopeItemVo[];
  actionLoading: boolean;
  onSave: (dto: KnowledgeTopicSaveDto) => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const isEdit = mode === 'edit';
  const [form, setForm] = useState<KnowledgeTopicSaveDto>({
    topicCode: '',
    topicName: '',
    scopeCode: '',
    description: '',
    aliases: '',
    examples: '',
    answerShape: '',
    executionPreference: '',
    sortOrder: 0,
  });

  useEffect(() => {
    if (target) {
      setForm({
        id: target.id,
        topicCode: target.topicCode,
        topicName: target.topicName,
        scopeCode: target.scopeCode,
        description: target.description ?? '',
        aliases: target.aliases ?? '',
        examples: target.examples ?? '',
        answerShape: target.answerShape ?? '',
        executionPreference: target.executionPreference ?? '',
        sortOrder: target.sortOrder ?? 0,
      });
    } else {
      setForm({ topicCode: '', topicName: '', scopeCode: '', description: '', aliases: '', examples: '', answerShape: '', executionPreference: '', sortOrder: 0 });
    }
  }, [target]);

  if (isEdit) {
    return (
      <div className="space-y-3">
        <Field label="主题编码">
          <input value={form.topicCode} onChange={(e) => setForm({ ...form, topicCode: e.target.value })} className={inputClass} />
        </Field>
        <Field label="主题名称">
          <input value={form.topicName} onChange={(e) => setForm({ ...form, topicName: e.target.value })} className={inputClass} />
        </Field>
        <Field label="所属范围">
          <select value={form.scopeCode} onChange={(e) => setForm({ ...form, scopeCode: e.target.value })} className={inputClass}>
            <option value="">选择所属范围</option>
            {scopes.map((s) => (
              <option key={s.scopeCode} value={s.scopeCode}>{s.scopeName}</option>
            ))}
          </select>
        </Field>
        <Field label="别名（英文逗号分隔）">
          <input value={form.aliases ?? ''} onChange={(e) => setForm({ ...form, aliases: e.target.value })} className={inputClass} />
        </Field>
        <Field label="回答形态">
          <select value={form.answerShape ?? ''} onChange={(e) => setForm({ ...form, answerShape: e.target.value })} className={inputClass}>
            <option value="">选择回答形态</option>
            {ANSWER_SHAPE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </Field>
        <Field label="执行偏好">
          <select value={form.executionPreference ?? ''} onChange={(e) => setForm({ ...form, executionPreference: e.target.value })} className={inputClass}>
            <option value="">选择执行偏好</option>
            {EXECUTION_PREFERENCE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </Field>
        <Field label="排序值">
          <input type="number" value={form.sortOrder ?? 0} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) })} className={inputClass} />
        </Field>
        <Field label="描述">
          <textarea value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} rows={3} className={inputClass} />
        </Field>
        <Field label="典型问题 JSON">
          <textarea value={form.examples ?? ''} onChange={(e) => setForm({ ...form, examples: e.target.value })} rows={3} className={inputClass} />
        </Field>
        <div className="flex gap-2 pt-2">
          <PrimaryButton loading={actionLoading} disabled={!form.topicCode || !form.topicName || !form.scopeCode} onClick={() => onSave(form)}>保存</PrimaryButton>
        </div>
      </div>
    );
  }

  if (!target) return null;
  return (
    <div className="space-y-4">
      <DetailRow label="主题编码" value={target.topicCode} />
      <DetailRow label="主题名称" value={target.topicName} />
      <DetailRow label="所属范围" value={target.scopeCode} />
      <DetailRow label="回答形态" value={formatMappedLabel(target.answerShape, ANSWER_SHAPE_LABEL)} />
      <DetailRow label="执行偏好" value={formatMappedLabel(target.executionPreference, EXECUTION_PREFERENCE_LABEL)} />
      <DetailRow label="排序值" value={target.sortOrder} />
      <DetailRow label="描述" value={target.description} />
      <TagSection title="别名" items={parseTextList(target.aliases)} />
      <TagSection title="典型问题" items={parseJsonArray(target.examples)} />
      <div className="flex gap-2 pt-3 border-t border-neutral-800">
        <GhostButton onClick={onEdit}><PencilSimple className="w-3.5 h-3.5" />编辑</GhostButton>
        <GhostButton danger onClick={onDelete} disabled={actionLoading}><Trash className="w-3.5 h-3.5" />删除</GhostButton>
      </div>
    </div>
  );
}

function RelationDrawer({
  mode,
  target,
  topics,
  documents,
  actionLoading,
  onSave,
  onEdit,
  onRemove,
}: {
  mode: DrawerMode;
  target: TopicDocumentRelationItemVo | null;
  topics: KnowledgeTopicItemVo[];
  documents: DocumentListItemVo[];
  actionLoading: boolean;
  onSave: (dto: TopicDocumentRelationSaveDto) => void;
  onEdit: () => void;
  onRemove: () => void;
}) {
  const isEdit = mode === 'edit';
  const [form, setForm] = useState<TopicDocumentRelationSaveDto>({
    topicCode: '',
    documentId: 0,
    relationScore: 0.9,
    relationSource: 'manual',
    reason: '',
  });

  useEffect(() => {
    if (target) {
      setForm({
        topicCode: target.topicCode,
        documentId: target.documentId,
        relationScore: target.relationScore ?? 0.9,
        relationSource: target.relationSource || 'manual',
        reason: target.reason ?? '',
      });
    } else {
      setForm({ topicCode: '', documentId: 0, relationScore: 0.9, relationSource: 'manual', reason: '' });
    }
  }, [target]);

  if (isEdit) {
    return (
      <div className="space-y-3">
        <Field label="主题">
          <select value={form.topicCode} onChange={(e) => setForm({ ...form, topicCode: e.target.value })} className={inputClass}>
            <option value="">选择主题</option>
            {topics.map((t) => (
              <option key={t.topicCode} value={t.topicCode}>{t.topicName}</option>
            ))}
          </select>
        </Field>
        <Field label="文档">
          <select
            value={form.documentId || ''}
            onChange={(e) => setForm({ ...form, documentId: Number(e.target.value) })}
            className={inputClass}
          >
            <option value="">选择文档</option>
            {documents.map((d) => (
              <option key={d.documentId} value={d.documentId}>{d.documentName}</option>
            ))}
          </select>
        </Field>
        <Field label="关联分数">
          <input
            type="number"
            step="0.0001"
            value={form.relationScore ?? 0.9}
            onChange={(e) => setForm({ ...form, relationScore: Number(e.target.value) })}
            className={inputClass}
          />
        </Field>
        <Field label="关联原因">
          <input value={form.reason ?? ''} onChange={(e) => setForm({ ...form, reason: e.target.value })} className={inputClass} />
        </Field>
        <div className="flex gap-2 pt-2">
          <PrimaryButton
            loading={actionLoading}
            disabled={!form.topicCode || !form.documentId}
            onClick={() => onSave(form)}
          >
            保存
          </PrimaryButton>
        </div>
      </div>
    );
  }

  if (!target) return null;
  return (
    <div className="space-y-4">
      <DetailRow label="主题编码" value={target.topicCode} />
      <DetailRow label="文档名称" value={target.documentName} />
      <DetailRow label="关联分数" value={target.relationScore} />
      <DetailRow label="关联来源" value={target.relationSource} />
      <DetailRow label="原因" value={target.reason} />
      <div className="flex gap-2 pt-3 border-t border-neutral-800">
        <GhostButton onClick={onEdit}><PencilSimple className="w-3.5 h-3.5" />编辑</GhostButton>
        <GhostButton danger onClick={onRemove} disabled={actionLoading}><Trash className="w-3.5 h-3.5" />移除</GhostButton>
      </div>
    </div>
  );
}

function ProfileDrawer({
  document: doc,
  profile,
  loading,
  actionLoading,
  onReload,
  onRegenerate,
}: {
  document: DocumentListItemVo | null;
  profile: DocumentProfileVo | null;
  loading: boolean;
  actionLoading: boolean;
  onReload: (documentId: string) => void;
  onRegenerate: (documentId: string) => void;
}) {
  const statusMeta = profile ? profileStatusMeta(profile.profileStatus) : null;

  return (
    <div className="space-y-4">
      {doc && (
        <>
          <DetailRow label="文档名称" value={doc.documentName} />
          <DetailRow
            label="元数据"
            value={[doc.knowledgeScopeName || doc.knowledgeScopeCode, doc.businessCategory, doc.documentTags].filter(Boolean).join(' · ') || '还没有范围 / 类目 / 标签元数据'}
          />
          <div className="flex gap-2">
            <GhostButton onClick={() => onReload(doc.documentId)} disabled={loading}>
              <ArrowsClockwise className="w-3.5 h-3.5" />
              {loading ? '加载中' : '查看画像'}
            </GhostButton>
            <PrimaryButton loading={actionLoading} onClick={() => onRegenerate(doc.documentId)}>
              <Sparkle className="w-3.5 h-3.5" />
              重新生成
            </PrimaryButton>
          </div>
        </>
      )}

      {loading && (
        <div className="flex items-center gap-2 text-neutral-500 text-sm py-4">
          <CircleNotch className="w-4 h-4 animate-spin" />
          正在加载画像...
        </div>
      )}

      {!loading && profile && statusMeta && (
        <div className="space-y-4 p-4 rounded-lg border border-neutral-800 bg-neutral-900/40">
          <div className="flex items-center justify-between gap-2">
            <strong className="text-sm text-neutral-100">{doc?.documentName ?? `文档 ${profile.documentId}`}</strong>
            <span
              className={cn(
                'inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold border',
                statusMeta.tone === 'success' && 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
                statusMeta.tone === 'processing' && 'bg-amber-500/15 text-amber-400 border-amber-500/30',
                statusMeta.tone === 'danger' && 'bg-red-500/15 text-red-400 border-red-500/30',
                statusMeta.tone === 'waiting' && 'bg-neutral-800 text-neutral-300 border-neutral-700',
              )}
            >
              {statusMeta.label}
            </span>
          </div>
          <p className="text-xs text-neutral-300 leading-relaxed">{profile.documentSummary || '当前画像还没有生成摘要'}</p>

          <div className="grid grid-cols-2 gap-2">
            <MiniCard label="文档类型" value={formatMappedLabel(profile.documentType, DOCUMENT_TYPE_LABEL)} />
            <MiniCard label="画像来源" value={formatMappedLabel(profile.profileSource, PROFILE_SOURCE_LABEL)} />
            <MiniCard label="图能力" value={graphCapabilityText(profile)} />
            <MiniCard label="核心主题数" value={String(parseJsonArray(profile.coreTopics).length)} />
          </div>

          <TagSection title="核心主题" items={parseJsonArray(profile.coreTopics)} />
          <TagSection title="示例问题" items={parseJsonArray(profile.exampleQuestions)} />
          {profile.errorMsg && (
            <div className="text-xs text-red-400 p-2 rounded-md bg-red-500/10 border border-red-500/30">
              {profile.errorMsg}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function MiniCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="p-2.5 rounded-lg border border-neutral-800 bg-neutral-900/40">
      <p className="text-[11px] text-neutral-500">{label}</p>
      <p className="text-sm text-neutral-200 mt-0.5">{value}</p>
    </div>
  );
}
