import { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { DocumentUpload } from '../../components/documents/DocumentUpload';
import { DocumentList } from '../../components/documents/DocumentList';
import { DocumentDetail } from '../../components/documents/DocumentDetail';
import { AdminPage } from '../../components/admin/AdminLayout';
import { listScopes } from '../../api/knowledge';
import type { DocumentUploadVo } from '../../types/document';
import type { KnowledgeScopeItemVo } from '../../types/knowledge';

export function AdminDocumentsPage() {
  const [refreshKey, setRefreshKey] = useState(0);
  const [scopes, setScopes] = useState<KnowledgeScopeItemVo[]>([]);
  const nav = useNavigate();

  // 阶段：加载已有知识范围，供上传表单下拉选择
  useEffect(() => {
    let alive = true;
    listScopes()
      .then((list) => {
        if (alive) setScopes(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        // scope 未配置时表单退化为纯文本输入，不阻断上传
      });
    return () => {
      alive = false;
    };
  }, []);

  const handleUploaded = useCallback(
    (vo: DocumentUploadVo) => {
      setRefreshKey((k) => k + 1);
      nav(`/admin/documents/${vo.documentId}`);
    },
    [nav],
  );

  return (
    <AdminPage>
      <div className="mb-6">
        <h1 className="text-lg font-semibold text-neutral-100">文档接入</h1>
        <p className="text-sm text-neutral-500 mt-0.5">
          上传文档并查看解析、策略、切块、向量化与索引构建状态
        </p>
      </div>

      <div className="mb-6">
        <DocumentUpload onUploaded={handleUploaded} scopes={scopes} />
      </div>

      <DocumentList refreshKey={refreshKey} linkBase="/admin/documents" />
    </AdminPage>
  );
}

// 阶段：复用现有 DocumentDetail 组件（已含 strategy 确认 + 状态轮询）
export function AdminDocumentDetailPage() {
  return (
    <AdminPage>
      <DocumentDetail />
    </AdminPage>
  );
}
