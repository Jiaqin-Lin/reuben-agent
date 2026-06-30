import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getDocument,
  getStrategyPlans,
  getTaskLogs,
} from '../../../api/document';
import type {
  DocumentDetailVo,
  DocumentStrategyPlanVo,
  DocumentTaskLogQueryVo,
} from '../../../types/document';
import { computeBuildInFlight } from '../../../lib/buildTracker';

const PARSE_POLL_MS = 5000;
const BUILD_POLL_MS = 3000;

export interface WorkbenchState {
  document: DocumentDetailVo | null;
  plans: DocumentStrategyPlanVo[];
  buildSnapshot: DocumentTaskLogQueryVo | null;
  loading: boolean;
  error: string | null;
  refetch: () => void;
}

export function useDocumentWorkbench(docId: string): WorkbenchState {
  const [document, setDocument] = useState<DocumentDetailVo | null>(null);
  const [plans, setPlans] = useState<DocumentStrategyPlanVo[]>([]);
  const [buildSnapshot, setBuildSnapshot] = useState<DocumentTaskLogQueryVo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const docRef = useRef<DocumentDetailVo | null>(null);

  const refetch = useCallback(() => setTick((t) => t + 1), []);

  const loadAll = useCallback(async () => {
    try {
      const [doc, planList] = await Promise.all([
        getDocument(docId),
        getStrategyPlans(docId),
      ]);
      setDocument(doc);
      docRef.current = doc;
      setPlans(planList ?? []);
      setError(null);

      // 拉取构建任务快照：优先 latestIndexTaskId，回退 latestTaskId（构建类型）
      const buildTaskId = doc.latestIndexTaskId ?? doc.latestTaskId;
      if (buildTaskId) {
        try {
          const snap = await getTaskLogs(buildTaskId, 1, 50);
          setBuildSnapshot(snap);
        } catch {
          setBuildSnapshot(null);
        }
      } else {
        setBuildSnapshot(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败');
    } finally {
      setLoading(false);
    }
  }, [docId]);

  // 初次 + 手动 refetch
  useEffect(() => {
    setLoading(true);
    loadAll();
  }, [loadAll, tick]);

  // 解析进行中轮询（parseStatus<3 或尚无方案）
  useEffect(() => {
    if (!document) return;
    const planReady = (plans ?? []).some((p) => p.planStatus >= 1 && p.steps && p.steps.length > 0);
    const parseInProgress = document.parseStatus < 3 || (!planReady && document.parseStatus === 3);
    if (!parseInProgress) return;
    const timer = setInterval(loadAll, PARSE_POLL_MS);
    return () => clearInterval(timer);
  }, [document, plans, loadAll]);

  // 构建在途轮询
  useEffect(() => {
    if (!document) return;
    const inFlight = computeBuildInFlight({
      taskStatus: buildSnapshot?.taskStatus,
      indexStatus: document.indexStatus,
      latestTaskType: document.latestTaskType,
      latestTaskStatus: document.latestTaskStatus,
    });
    if (!inFlight) return;
    const timer = setInterval(loadAll, BUILD_POLL_MS);
    return () => clearInterval(timer);
  }, [document, buildSnapshot, loadAll]);

  return { document, plans, buildSnapshot, loading, error, refetch };
}
