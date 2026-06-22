import { useState, useEffect, useCallback, useRef } from 'react';

export function useApi<T>(
  fetcher: () => Promise<T>,
  deps: unknown[],
): {
  data: T | null;
  loading: boolean;
  error: string | null;
  refetch: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);

  const fetch = useCallback(() => {
    setLoading(true);
    setError(null);
    fetcher()
      .then((res) => {
        if (mounted.current) setData(res);
      })
      .catch((e) => {
        if (mounted.current) setError(e instanceof Error ? e.message : '请求失败');
      })
      .finally(() => {
        if (mounted.current) setLoading(false);
      });
  }, deps);

  useEffect(() => {
    mounted.current = true;
    fetch();
    return () => {
      mounted.current = false;
    };
  }, [fetch]);

  return { data, loading, error, refetch: fetch };
}

export function useMutation<T, Args extends unknown[]>(
  fn: (...args: Args) => Promise<T>,
): {
  execute: (...args: Args) => Promise<T>;
  loading: boolean;
  error: string | null;
  data: T | null;
} {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [data, setData] = useState<T | null>(null);

  const execute = useCallback(async (...args: Args) => {
    setLoading(true);
    setError(null);
    try {
      const res = await fn(...args);
      setData(res);
      return res;
    } catch (e) {
      const msg = e instanceof Error ? e.message : '请求失败';
      setError(msg);
      throw e;
    } finally {
      setLoading(false);
    }
  }, []);

  return { execute, loading, error, data };
}
