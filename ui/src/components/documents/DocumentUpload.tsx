import { useState, useRef, useCallback } from 'react';
import { Upload, X } from '@phosphor-icons/react';
import { motion, AnimatePresence } from 'motion/react';
import { uploadDocument } from '../../api/document';
import { useToast } from '../shared/Toast';
import { FILE_LIMITS, STORAGE_KEY } from '../../lib/constants';
import type { DocumentUploadVo, StoredDocument } from '../../types/document';

interface Props {
  onUploaded: (vo: DocumentUploadVo) => void;
}

export function DocumentUpload({ onUploaded }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [progress, setProgress] = useState(0);
  const [uploading, setUploading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const { toast } = useToast();

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const f = e.dataTransfer.files[0];
    if (f) setFile(f);
  }, []);

  const handleUpload = useCallback(async () => {
    if (!file) return;
    setUploading(true);
    setProgress(0);
    try {
      const vo = await uploadDocument(file, undefined, setProgress);
      toast(`上传成功: ${vo.documentName}`, 'success');
      // Store in localStorage
      const stored: StoredDocument = {
        documentId: vo.documentId,
        documentName: vo.documentName,
        uploadedAt: new Date().toISOString(),
      };
      const existing = JSON.parse(
        localStorage.getItem(STORAGE_KEY) ?? '[]',
      ) as StoredDocument[];
      const filtered = existing.filter((d) => d.documentId !== vo.documentId);
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify([stored, ...filtered]),
      );
      onUploaded(vo);
      setFile(null);
      setProgress(0);
    } catch (e) {
      toast(e instanceof Error ? e.message : '上传失败', 'error');
    } finally {
      setUploading(false);
    }
  }, [file, toast, onUploaded]);

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
                onClick={() => setFile(null)}
                className="p-1.5 rounded-md hover:bg-neutral-800 text-neutral-500 hover:text-neutral-300 transition-colors"
                disabled={uploading}
              >
                <X className="w-4 h-4" />
              </button>
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
