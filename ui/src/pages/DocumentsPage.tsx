import { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { DocumentUpload } from '../components/documents/DocumentUpload';
import { DocumentList } from '../components/documents/DocumentList';
import type { DocumentUploadVo } from '../types/document';

export function DocumentsPage() {
  const [refreshKey, setRefreshKey] = useState(0);
  const nav = useNavigate();

  const handleUploaded = useCallback((vo: DocumentUploadVo) => {
    setRefreshKey((k) => k + 1);
    nav(`/documents/${vo.documentId}`);
  }, [nav]);

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-lg font-semibold text-neutral-100">文档管理</h1>
          <p className="text-sm text-neutral-500 mt-0.5">
            上传文档并查看解析、切块、向量化状态
          </p>
        </div>
      </div>

      <div className="mb-6">
        <DocumentUpload onUploaded={handleUploaded} />
      </div>

      <DocumentList refreshKey={refreshKey} />
    </div>
  );
}
