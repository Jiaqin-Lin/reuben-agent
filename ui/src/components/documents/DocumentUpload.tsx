import { useState, useRef, useCallback, useEffect } from 'react';
import { Upload, X, Tag, FolderOpen, CaretDown } from '@phosphor-icons/react';
import { motion, AnimatePresence } from 'motion/react';
import { uploadDocument } from '../../api/document';
import { useToast } from '../shared/Toast';
import { FILE_LIMITS } from '../../lib/constants';
import type { DocumentUploadVo, DocumentUploadDto } from '../../types/document';
import type { KnowledgeScopeItemVo } from '../../types/knowledge';

interface Props {
  onUploaded: (vo: DocumentUploadVo) => void;
  /** 已有知识范围列表，传入后上传表单可下拉选择 scope；不传则纯文本输入 */
  scopes?: KnowledgeScopeItemVo[];
}

const inputCls =
  'w-full px-3 py-2 rounded-lg bg-neutral-950 border border-neutral-800 text-sm text-neutral-100 outline-none focus:border-amber-500/50 transition-colors placeholder:text-neutral-600';

export function DocumentUpload({ onUploaded, scopes }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [progress, setProgress] = useState(0);
  const [uploading, setUploading] = useState(false);

  // 元数据：documentName 留空则后端用原始文件名
  const [documentName, setDocumentName] = useState('');
  const [scopeCode, setScopeCode] = useState('');
  const [scopeName, setScopeName] = useState('');
  const [businessCategory, setBusinessCategory] = useState('');
  const [documentTags, setDocumentTags] = useState('');

  const [scopeMenuOpen, setScopeMenuOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const scopeRef = useRef<HTMLDivElement>(null);
  const { toast } = useToast();

  // 阶段：选 scope 时同步名称
  const pickScope = useCallback((s: KnowledgeScopeItemVo) => {
    setScopeCode(s.scopeCode);
    setScopeName(s.scopeName);
    setScopeMenuOpen(false);
  }, []);

  // 阶段：手动改 code 时清掉已选名称，避免 code/name 不一致
  const onScopeCodeChange = useCallback((v: string) => {
    setScopeCode(v);
    setScopeName('');
  }, []);

  // 阶段：点外部收起 scope 下拉
  useEffect(() => {
    if (!scopeMenuOpen) return;
    const handler = (e: MouseEvent) => {
      if (scopeRef.current && !scopeRef.current.contains(e.target as Node)) {
        setScopeMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [scopeMenuOpen]);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const f = e.dataTransfer.files[0];
    if (f) setFile(f);
  }, []);

  const resetMeta = useCallback(() => {
    setDocumentName('');
    setScopeCode('');
    setScopeName('');
    setBusinessCategory('');
    setDocumentTags('');
  }, []);

  const handleUpload = useCallback(async () => {
    if (!file) return;
    setUploading(true);
    setProgress(0);
    try {
      const meta: DocumentUploadDto = {
        documentName: documentName.trim() || undefined,
        knowledgeScopeCode: scopeCode.trim() || undefined,
        knowledgeScopeName: scopeName.trim() || undefined,
        businessCategory: businessCategory.trim() || undefined,
        documentTags: documentTags.trim() || undefined,
      };
      const hasMeta = Object.values(meta).some(Boolean);
      const vo = await uploadDocument(
        file,
        hasMeta ? meta : undefined,
        setProgress,
      );
      toast(`上传成功: ${vo.documentName}`, 'success');
      onUploaded(vo);
      setFile(null);
      resetMeta();
      setProgress(0);
    } catch (e) {
      toast(e instanceof Error ? e.message : '上传失败', 'error');
    } finally {
      setUploading(false);
    }
  }, [
    file,
    documentName,
    scopeCode,
    scopeName,
    businessCategory,
    documentTags,
    toast,
    onUploaded,
    resetMeta,
  ]);

  return (
    <div className="border border-neutral-800 rounded-lg bg-neutral-900/30 overflow-hidden">
      <AnimatePresence mode="wait">
        {!file ? (
          <motion.div
            key="dropzone"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={handleDrop}
            onClick={() => inputRef.current?.click()}
            className={`
              flex flex-col items-center justify-center py-12 px-6 cursor-pointer
              transition-all duration-200
              ${dragging ? 'bg-amber-500/5 border-amber-500/30' : 'hover:bg-neutral-800/30'}
            `}
          >
            <div
              className={`
                w-12 h-12 rounded-full flex items-center justify-center mb-3 transition-colors
                ${dragging ? 'bg-amber-500/10 text-amber-400' : 'bg-neutral-800 text-neutral-500'}
              `}
            >
              <Upload className="w-5 h-5" weight="bold" />
            </div>
            <p className="text-sm text-neutral-300 mb-1">
              拖拽文件到此处，或点击选择
            </p>
            <p className="text-xs text-neutral-500">
              支持 PDF, DOCX, TXT, MD, HTML (最大 50MB)
            </p>
            <input
              ref={inputRef}
              type="file"
              accept={FILE_LIMITS.accept}
              className="hidden"
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) setFile(f);
              }}
            />
          </motion.div>
        ) : (
          <motion.div
            key="preview"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="p-6"
          >
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-md bg-neutral-800 flex items-center justify-center">
                  <Upload className="w-4 h-4 text-neutral-400" />
                </div>
                <div>
                  <p className="text-sm font-medium text-neutral-200 truncate max-w-[400px]">
                    {file.name}
                  </p>
                  <p className="text-xs text-neutral-500">
                    {(file.size / 1024 / 1024).toFixed(1)} MB
                  </p>
                </div>
              </div>
              <button
                onClick={() => {
                  setFile(null);
                  resetMeta();
                }}
                className="p-1.5 rounded-md hover:bg-neutral-800 text-neutral-500 hover:text-neutral-300 transition-colors"
                disabled={uploading}
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* 元数据表单：可选，留空走后端默认 */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-4">
              <label className="flex flex-col gap-1.5">
                <span className="text-xs text-neutral-400">文档名称</span>
                <input
                  value={documentName}
                  onChange={(e) => setDocumentName(e.target.value)}
                  type="text"
                  placeholder="不填则使用原始文件名"
                  className={inputCls}
                />
              </label>

              {/* 知识范围：有 scopes 时下拉选择，否则纯文本 */}
              <label className="flex flex-col gap-1.5">
                <span className="text-xs text-neutral-400">知识范围编码</span>
                <div className="relative" ref={scopeRef}>
                  <input
                    value={scopeCode}
                    onChange={(e) => onScopeCodeChange(e.target.value)}
                    type="text"
                    placeholder="例如 operation_rule"
                    className={`${inputCls} ${scopes?.length ? 'pr-8' : ''}`}
                  />
                  {scopes?.length ? (
                    <button
                      type="button"
                      onClick={() => setScopeMenuOpen((v) => !v)}
                      className="absolute right-1.5 top-1/2 -translate-y-1/2 p-1 rounded text-neutral-500 hover:text-neutral-300 hover:bg-neutral-800 transition-colors"
                    >
                      <CaretDown className="w-3.5 h-3.5" />
                    </button>
                  ) : null}
                  <AnimatePresence>
                    {scopeMenuOpen && scopes?.length ? (
                      <motion.div
                        initial={{ opacity: 0, y: -4 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, y: -4 }}
                        transition={{ duration: 0.15 }}
                        className="absolute z-20 mt-1 w-full max-h-52 overflow-auto rounded-lg border border-neutral-800 bg-neutral-900 shadow-xl"
                      >
                        {scopes.map((s) => (
                          <button
                            key={s.id}
                            type="button"
                            onClick={() => pickScope(s)}
                            className="w-full flex items-center gap-2 px-3 py-2 text-left text-sm text-neutral-300 hover:bg-neutral-800 transition-colors"
                          >
                            <FolderOpen className="w-3.5 h-3.5 text-amber-400/70 shrink-0" />
                            <span className="font-mono text-xs text-neutral-400">
                              {s.scopeCode}
                            </span>
                            <span className="text-neutral-200 truncate">
                              {s.scopeName}
                            </span>
                          </button>
                        ))}
                      </motion.div>
                    ) : null}
                  </AnimatePresence>
                </div>
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-xs text-neutral-400">知识范围名称</span>
                <input
                  value={scopeName}
                  onChange={(e) => setScopeName(e.target.value)}
                  type="text"
                  placeholder="例如 运营规则"
                  className={inputCls}
                />
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-xs text-neutral-400">业务分类</span>
                <input
                  value={businessCategory}
                  onChange={(e) => setBusinessCategory(e.target.value)}
                  type="text"
                  placeholder="例如 手册 / 规则 / 介绍"
                  className={inputCls}
                />
              </label>

              <label className="flex flex-col gap-2 sm:col-span-2">
                <span className="text-xs text-neutral-400 flex items-center gap-1">
                  <Tag className="w-3 h-3" />
                  文档标签
                </span>
                <input
                  value={documentTags}
                  onChange={(e) => setDocumentTags(e.target.value)}
                  type="text"
                  placeholder="多个标签用英文逗号分隔，辅助检索和分类"
                  className={inputCls}
                />
              </label>
            </div>

            {uploading && (
              <div className="mb-4">
                <div className="h-1 bg-neutral-800 rounded-full overflow-hidden">
                  <motion.div
                    className="h-full bg-amber-500 rounded-full"
                    initial={{ width: 0 }}
                    animate={{ width: `${progress}%` }}
                    transition={{ duration: 0.15 }}
                  />
                </div>
                <p className="text-xs text-neutral-500 mt-1.5 font-mono">
                  {progress}%
                </p>
              </div>
            )}

            <button
              onClick={handleUpload}
              disabled={uploading}
              className="w-full py-2.5 px-4 text-sm font-medium bg-amber-500 text-black rounded-lg hover:bg-amber-400 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {uploading ? '上传中...' : '开始上传'}
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
