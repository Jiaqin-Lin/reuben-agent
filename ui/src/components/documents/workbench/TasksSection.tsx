import { useCallback, useEffect, useState } from 'react';
import { getTaskLogs } from '../../../api/document';
import { Drawer } from '../../admin/Drawer';
import { StatusBadge } from '../StatusBadge';
import { EventType, LogLevel, TaskStage } from '../../../types/enums';
import type {
  DocumentTaskLogVo,
  DocumentTaskLogQueryVo,
} from '../../../types/document';

interface Props {
  documentId: string;
  latestTaskId?: string;
}

export function TasksSection({ documentId: _documentId, latestTaskId }: Props) {
  const [logs, setLogs] = useState<DocumentTaskLogVo[]>([]);
  const [snapshot, setSnapshot] = useState<DocumentTaskLogQueryVo | null>(null);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const load = useCallback(async () => {
    if (!latestTaskId) {
      setLogs([]);
      setSnapshot(null);
      return;
    }
    setLoading(true);
    try {
      const data = await getTaskLogs(latestTaskId, 1, 50);
      setSnapshot(data);
      setLogs(data.logs ?? []);
    } catch {
      setLogs([]);
      setSnapshot(null);
    } finally {
      setLoading(false);
    }
  }, [latestTaskId]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm font-medium text-neutral-200">任务记录</p>
          <p className="text-[11px] text-neutral-500 mt-0.5">
            {latestTaskId
              ? `最近任务 ${latestTaskId} · ${logs.length} 条日志`
              : '暂无任务记录'}
          </p>
        </div>
        <button
          onClick={() => setDrawerOpen(true)}
          disabled={!latestTaskId}
          className="px-3 py-1.5 text-xs rounded-md border border-neutral-800 text-neutral-300 hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          查看完整任务时间线
        </button>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-14 rounded-lg bg-neutral-900/30 border border-neutral-800 animate-pulse" />
          ))}
        </div>
      ) : logs.length === 0 ? (
        <div className="rounded-lg border border-neutral-800 bg-neutral-900/30 p-8 text-center">
          <p className="text-sm text-neutral-500">
            {latestTaskId ? '该任务暂无日志' : '当前文档还没有任务记录'}
          </p>
        </div>
      ) : (
        <div className="space-y-2">
          {logs.slice(0, 3).map((log) => (
            <LogItem key={log.id} log={log} />
          ))}
          {logs.length > 3 && (
            <button
              onClick={() => setDrawerOpen(true)}
              className="w-full text-center py-2 text-xs text-neutral-500 hover:text-neutral-300 transition-colors"
            >
              查看全部 {logs.length} 条日志 →
            </button>
          )}
        </div>
      )}

      <Drawer
        open={drawerOpen}
        title="任务执行详情"
        onClose={() => setDrawerOpen(false)}
      >
        {snapshot ? (
          <div className="space-y-4">
            <div className="flex gap-2 text-[11px] font-mono">
              <span className="px-2 py-0.5 rounded bg-neutral-800 text-neutral-300">
                任务 {snapshot.taskId}
              </span>
              {snapshot.taskType != null && (
                <span className="px-2 py-0.5 rounded bg-neutral-800 text-neutral-300">
                  类型 {snapshot.taskType === 2 ? '构建索引' : '解析路由'}
                </span>
              )}
              <StatusBadge type="task" code={snapshot.taskStatus} />
            </div>

            {snapshot.errorMsg && (
              <p className="text-xs text-red-400 rounded-md border border-red-500/30 bg-red-500/5 p-2.5">
                {snapshot.errorMsg}
              </p>
            )}

            <div className="space-y-2">
              {logs.map((log) => (
                <LogItem key={log.id} log={log} expanded />
              ))}
            </div>
          </div>
        ) : (
          <p className="text-sm text-neutral-500">无任务日志</p>
        )}
      </Drawer>
    </section>
  );
}

function LogItem({
  log,
  expanded = false,
}: {
  log: DocumentTaskLogVo;
  expanded?: boolean;
}) {
  const levelColor =
    log.logLevel === 3
      ? 'text-red-400'
      : log.logLevel === 2
        ? 'text-amber-400'
        : 'text-neutral-500';
  return (
    <div className="rounded-md border border-neutral-800 bg-neutral-900/30 p-3">
      <div className="flex items-center justify-between mb-1">
        <p className="text-xs font-medium text-neutral-200">
          {TaskStage[log.stageType] ?? `阶段${log.stageType}`} ·{' '}
          {EventType[log.eventType] ?? `事件${log.eventType}`}
        </p>
        <span className={`text-[10px] font-mono ${levelColor}`}>
          {LogLevel[log.logLevel] ?? ''}
        </span>
      </div>
      <p className="text-xs text-neutral-400 leading-relaxed">{log.content}</p>
      {expanded && log.detailJson && (
        <pre className="mt-2 text-[10px] text-neutral-500 font-mono whitespace-pre-wrap bg-neutral-950/50 rounded p-2 border border-neutral-800/50 max-h-40 overflow-y-auto">
          {log.detailJson}
        </pre>
      )}
      <p className="text-[10px] text-neutral-600 font-mono mt-1">
        {log.createTime ? new Date(log.createTime).toLocaleString('zh-CN') : ''}
      </p>
    </div>
  );
}
