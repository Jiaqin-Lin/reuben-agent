import { FileType, QualityLevel, StructureLevel } from '../../types/enums';
import type { DocumentDetailVo } from '../../types/document';

interface Props {
  document: DocumentDetailVo;
}

function qualityLabel(level: number | null | undefined): string {
  if (level == null) return '-';
  const label = QualityLevel[level];
  return label ? `${label} (${level})` : `${level}`;
}

function structureLabel(level: number | null | undefined): string {
  if (level == null) return '-';
  const label = StructureLevel[level];
  return label ? `${label} (${level})` : `${level}`;
}

export function DocumentMeta({ document: doc }: Props) {
  const rows = [
    { label: '文档 ID', value: String(doc.documentId) },
    { label: '原始文件名', value: doc.originalFileName },
    { label: '文件类型', value: FileType[doc.fileType] ?? '未知' },
    {
      label: '文件大小',
      value: `${(doc.fileSize / 1024).toFixed(1)} KB (${doc.fileSize.toLocaleString()} B)`,
    },
    { label: '字符数', value: doc.charCount?.toLocaleString() ?? '-' },
    { label: '结构层级', value: structureLabel(doc.structureLevel) },
    { label: '内容质量', value: qualityLabel(doc.qualityLevel) },
    {
      label: '创建时间',
      value: doc.createTime ? new Date(doc.createTime).toLocaleString('zh-CN') : '-',
    },
  ];

  return (
    <div>
      <h2 className="text-sm font-medium text-neutral-400 uppercase tracking-wider mb-3">
        文档信息
      </h2>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {rows.map((r) => (
          <div
            key={r.label}
            className="rounded-lg border border-neutral-800 bg-neutral-900/30 px-3.5 py-2.5"
          >
            <div className="text-[11px] text-neutral-600 font-mono mb-0.5">
              {r.label}
            </div>
            <div className="text-sm text-neutral-200 font-medium truncate">
              {r.value}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
